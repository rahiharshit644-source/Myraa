package com.soltini.app.security

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.soltini.app.overlay.OverlayService
import com.soltini.app.settings.AppSettings
import com.soltini.app.ui.theme.GeminiVoiceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * VoiceUnlockActivity
 *
 * Biometric Voice Authentication and Multi-User Voice Profile Enrollment Activity.
 *
 * Guarantees:
 * - 10-step phonetic sentences enrollment in Hindi, Hinglish, and English.
 * - Hardware Keystore encryption for biometric embeddings.
 * - Instant erasure of raw PCM audio buffers.
 * - Speaker identification with ambiguity and unknown speaker rejection.
 * - Explicitly enforces: Voice biometrics identifies the Myra user and controls
 *   Myra application-level permissions. It does NOT bypass Android system lock screen.
 */
class VoiceUnlockActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val MODE_VERIFY_BANKING = "verify_banking"
        const val MODE_VERIFY_DESTRUCTIVE = "verify_destructive"
        const val MODE_ENROLL = "enroll"

        fun startForBankingToggle(context: Context) {
            val intent = Intent(context, VoiceUnlockActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_VERIFY_BANKING)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }

        fun startEnrollment(context: Context, profileId: String = VoiceProfileManager.DEFAULT_OWNER_ID) {
            val intent = Intent(context, VoiceUnlockActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_ENROLL)
                putExtra(EXTRA_PROFILE_ID, profileId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VERIFY_BANKING
        val targetProfileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: VoiceProfileManager.DEFAULT_OWNER_ID
        val voiceManager = VoiceBiometricsManager.getInstance(this)
        val profileManager = VoiceProfileManager.getInstance(this)

        setContent {
            GeminiVoiceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.75f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        VoiceLockContent(
                            mode = mode,
                            targetProfileId = targetProfileId,
                            voiceManager = voiceManager,
                            profileManager = profileManager,
                            onDismiss = {
                                profileManager.cancelEnrollmentSession()
                                setResult(Activity.RESULT_CANCELED)
                                finish()
                            },
                            onSuccess = {
                                if (mode == MODE_VERIFY_BANKING) {
                                    val settings = AppSettings(this@VoiceUnlockActivity)
                                    val newPaused = !settings.isBankingModePaused
                                    settings.isBankingModePaused = newPaused
                                    OverlayService.getInstance()?.setHiddenForBanking(newPaused)
                                    Toast.makeText(
                                        this@VoiceUnlockActivity,
                                        if (newPaused) "Banking Mode Paused: Overlay Hidden" else "Voice Verified: Overlay Restored",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                setResult(Activity.RESULT_OK)
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceLockContent(
    mode: String,
    targetProfileId: String,
    voiceManager: VoiceBiometricsManager,
    profileManager: VoiceProfileManager,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf(1) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val totalSteps = VoiceProfileEnrollmentPhrases.REQUIRED_SAMPLES_COUNT
    val currentPhrase = VoiceProfileEnrollmentPhrases.getPhraseForStep(currentStep)

    val profile = remember(targetProfileId) { profileManager.getProfile(targetProfileId) }
    val profileName = profile?.name ?: "User"

    var statusText by remember {
        mutableStateOf(
            if (mode == VoiceUnlockActivity.MODE_ENROLL)
                "Enroll Voice for $profileName (Step 1 of $totalSteps)"
            else "Tap Mic to authenticate voice"
        )
    }
    var similarityScore by remember { mutableStateOf<Float?>(null) }
    var identifiedProfileName by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf<Boolean?>(null) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        statusText = if (granted) {
            if (mode == VoiceUnlockActivity.MODE_ENROLL)
                "Mic access granted. Enroll Voice for $profileName (Step $currentStep of $totalSteps)"
            else "Mic access granted. Tap to authenticate voice"
        } else {
            "Microphone permission zaroori hai voice ke liye. Settings me jaake allow karein."
        }
    }

    LaunchedEffect(targetProfileId) {
        if (mode == VoiceUnlockActivity.MODE_ENROLL) {
            profileManager.startEnrollmentSession(targetProfileId)
        }
        if (!hasMicPermission) {
            statusText = "Microphone permission zaroori hai. Neeche button dabayein."
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141724)),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF4A68FF).copy(0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = Color(0xFF4A68FF),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (mode == VoiceUnlockActivity.MODE_ENROLL) "Voice Profile Enrollment" else "Voice Identification",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                }
            }

            Spacer(Modifier.height(14.dp))

            // Profile Tag
            Surface(
                color = Color(0xFF222A45),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF7C98FF), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (mode == VoiceUnlockActivity.MODE_ENROLL) "Target: $profileName (${profile?.role?.name ?: "FAMILY"})"
                        else "Profile Security System",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Pulsing Mic / Lock Orb
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                if (isSuccess == true) Color(0xFF00E676).copy(0.4f)
                                else if (isSuccess == false) Color(0xFFFF5252).copy(0.4f)
                                else Color(0xFF4A68FF).copy(if (isRecording) 0.5f else 0.2f),
                                Color.Transparent
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size((56 * pulseScale).dp)
                        .clip(CircleShape)
                        .background(
                            if (isSuccess == true) Color(0xFF00E676)
                            else if (isSuccess == false) Color(0xFFFF5252)
                            else if (isRecording) Color(0xFF4A68FF)
                            else Color(0xFF242C48)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isSuccess == true) Icons.Default.CheckCircle
                        else if (isSuccess == false) Icons.Default.Warning
                        else if (isRecording) Icons.Default.Mic
                        else Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (mode == VoiceUnlockActivity.MODE_ENROLL) {
                // Enrollment Progress Bar
                LinearProgressIndicator(
                    progress = { (currentStep - 1) / totalSteps.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF4A68FF),
                    trackColor = Color(0xFF232B45)
                )

                Spacer(Modifier.height(12.dp))

                // Prompt Card
                Surface(
                    color = Color(0xFF1B2035),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333F6B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Phrase $currentStep of $totalSteps", color = Color(0xFF7C98FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(currentPhrase.language, color = Color.Gray, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "\"${currentPhrase.phrase}\"",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "💡 ${currentPhrase.guidance}",
                            color = Color(0xFFA5B4FC),
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                Text(
                    statusText,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium
                )

                if (similarityScore != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Match Score: ${"%.1f".format(similarityScore!! * 100)}% (Threshold: ${"%.0f".format(VoiceProfileManager.VERIFICATION_THRESHOLD * 100)}%)",
                        color = if (isSuccess == true) Color(0xFF00E676) else Color(0xFFFF5252),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (identifiedProfileName != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Identified Speaker: $identifiedProfileName",
                        color = Color(0xFF7C98FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // Action Button
            if (mode == VoiceUnlockActivity.MODE_ENROLL) {
                Button(
                    onClick = {
                        if (isRecording) return@Button
                        if (!hasMicPermission) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@Button
                        }
                        coroutineScope.launch {
                            isRecording = true
                            statusText = "Listening... Speak phrase $currentStep now"

                            when (val outcome = recordAudioBuffer(context, durationMs = 2800L)) {
                                is RecordAudioOutcome.Success -> {
                                    val pcm = outcome.pcm
                                    val countBefore = profileManager.getCurrentEnrollmentSampleCount()
                                    val countAfter = profileManager.addEnrollmentSample(pcm)
                                    if (countAfter == countBefore) {
                                        // extractAcousticEmbedding rejected the sample (too quiet / no speech)
                                        statusText = "Sample capture nahi hua (bahut quiet ya background noise). Phrase $currentStep dobara bolein."
                                    } else if (currentStep < totalSteps) {
                                        currentStep++
                                        statusText = "Sample ${currentStep - 1} captured! Now read phrase $currentStep."
                                    } else {
                                        val finalized = profileManager.finalizeEnrollment()
                                        if (finalized) {
                                            // Also update legacy single-profile manager for banking mode
                                            voiceManager.finalizeEnrollment()
                                            isSuccess = true
                                            statusText = "Voice Enrollment Complete for $profileName!"
                                            delay(1200)
                                            onSuccess()
                                        } else {
                                            statusText = "Could not finalize enrollment (kam se kam 3 valid samples chahiye). Please retry."
                                        }
                                    }
                                }
                                RecordAudioOutcome.PermissionMissing -> {
                                    hasMicPermission = false
                                    statusText = "Microphone permission missing. Button dobara dabayein permission dene ke liye."
                                }
                                RecordAudioOutcome.MicUnavailable -> {
                                    statusText = "Microphone use nahi ho pa raha (kisi aur app me busy ho sakta hai). Dobara try karein."
                                }
                                RecordAudioOutcome.TooShort -> {
                                    statusText = "Recording bahut short thi. Phrase $currentStep dobara bolein."
                                }
                            }
                            isRecording = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A68FF)),
                    enabled = !isRecording && isSuccess != true
                ) {
                    Text(
                        if (isRecording) "Recording phrase $currentStep..."
                        else if (!hasMicPermission) "Grant Microphone Permission"
                        else "Record Phrase $currentStep of $totalSteps",
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Button(
                    onClick = {
                        if (isRecording) return@Button
                        if (!hasMicPermission) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@Button
                        }
                        coroutineScope.launch {
                            isRecording = true
                            isSuccess = null
                            similarityScore = null
                            identifiedProfileName = null
                            statusText = "Listening... Speak any command or sentence"

                            val idResult = profileManager.recordAndIdentify(durationMs = 2500L)
                            isRecording = false

                            when (idResult) {
                                is SpeakerIdentificationResult.Identified -> {
                                    isSuccess = true
                                    similarityScore = idResult.similarity
                                    identifiedProfileName = "${idResult.profile.name} (${idResult.profile.role.name})"
                                    statusText = "Voice Verified: ${idResult.profile.name}!"
                                    voiceManager.recordSuccessfulAuth()
                                    delay(1000)
                                    onSuccess()
                                }
                                is SpeakerIdentificationResult.AmbiguousMatch -> {
                                    isSuccess = false
                                    similarityScore = idResult.candidateA.second
                                    statusText = "Ambiguous Voice: Score too close between ${idResult.candidateA.first.name} and ${idResult.candidateB.first.name}. Password required."
                                }
                                is SpeakerIdentificationResult.UnknownSpeaker -> {
                                    isSuccess = false
                                    similarityScore = idResult.topScore
                                    statusText = idResult.message
                                }
                                is SpeakerIdentificationResult.InsufficientAudio -> {
                                    isSuccess = false
                                    statusText = idResult.reason
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSuccess == true) Color(0xFF00E676) else Color(0xFF4A68FF)
                    ),
                    enabled = !isRecording && isSuccess != true
                ) {
                    Text(
                        if (isRecording) "Identifying Speaker..."
                        else if (!hasMicPermission) "Grant Microphone Permission"
                        else "Authenticate Voice",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Non-bypass Security Guarantee notice
            Text(
                "🔒 Myra voice biometrics identifies the speaker to enforce app-level permissions. It does NOT bypass Android device lock screen or system BiometricPrompt.",
                color = Color.Gray,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}

/**
 * Outcome of a raw microphone capture attempt. Kept distinct from the acoustic
 * analysis outcome (too quiet / too short) so the UI can tell the user the real
 * reason a recording failed instead of always blaming "audio quality".
 */
sealed class RecordAudioOutcome {
    data class Success(val pcm: ByteArray) : RecordAudioOutcome()
    object PermissionMissing : RecordAudioOutcome()
    object MicUnavailable : RecordAudioOutcome()
    object TooShort : RecordAudioOutcome()
}

/**
 * Records a short audio PCM buffer safely on IO thread.
 * Caller MUST verify RECORD_AUDIO permission before invoking this; this function
 * also defends against a race where permission is revoked mid-flow.
 */
@SuppressLint("MissingPermission")
private suspend fun recordAudioBuffer(
    context: Context,
    durationMs: Long = 2800L
): RecordAudioOutcome = withContext(Dispatchers.IO) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED
    ) {
        return@withContext RecordAudioOutcome.PermissionMissing
    }

    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(sampleRate)

    val recorder: AudioRecord
    try {
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext RecordAudioOutcome.MicUnavailable
        }
    } catch (_: Exception) {
        return@withContext RecordAudioOutcome.MicUnavailable
    }

    val totalBytesToRead = (sampleRate * 2 * (durationMs / 1000.0)).toInt()
    val pcmAccumulator = ByteArray(totalBytesToRead)
    var totalRead = 0

    try {
        recorder.startRecording()
        val chunk = ByteArray(2048)
        val startTime = System.currentTimeMillis()

        while (totalRead < totalBytesToRead && (System.currentTimeMillis() - startTime) < (durationMs + 1000)) {
            val read = recorder.read(chunk, 0, min(chunk.size, totalBytesToRead - totalRead))
            if (read > 0) {
                System.arraycopy(chunk, 0, pcmAccumulator, totalRead, read)
                totalRead += read
            }
        }
    } catch (_: Exception) {
    } finally {
        try {
            recorder.stop()
            recorder.release()
        } catch (_: Exception) {}
    }

    if (totalRead < 3200) return@withContext RecordAudioOutcome.TooShort
    RecordAudioOutcome.Success(pcmAccumulator.copyOf(totalRead))
}
