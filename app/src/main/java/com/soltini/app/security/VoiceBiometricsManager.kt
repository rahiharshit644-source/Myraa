package com.soltini.app.security

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.soltini.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.*

/**
 * VoiceBiometricsManager
 *
 * Provides biometric voice enrollment, acoustic feature embedding extraction,
 * and cosine-similarity verification for Banking Mode and sensitive operations.
 */
class VoiceBiometricsManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VoiceBiometricsManager"
        private const val PREFS_NAME = "myra_voice_biometrics"
        private const val KEY_ENROLLED_EMBEDDING = "enrolled_voice_embedding"
        private const val KEY_ENROLLMENT_SAMPLE_COUNT = "enrollment_sample_count"
        private const val KEY_LAST_AUTH_TIME = "last_voice_auth_timestamp"
        private const val KEY_VOICE_LOCK_ENABLED = "voice_lock_banking_enabled"

        const val VECTOR_DIM = 34
        const val DEFAULT_THRESHOLD = 0.76f
        const val REQUIRED_ENROLLMENT_SAMPLES = 3

        @Volatile
        private var instance: VoiceBiometricsManager? = null

        fun getInstance(context: Context): VoiceBiometricsManager =
            instance ?: synchronized(this) {
                instance ?: VoiceBiometricsManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val tempEnrollmentSamples = mutableListOf<FloatArray>()

    data class VerificationResult(
        val isMatch: Boolean,
        val similarity: Float,
        val threshold: Float,
        val reason: String
    )

    fun isEnrolled(): Boolean {
        val jsonStr = prefs.getString(KEY_ENROLLED_EMBEDDING, null)
        return !jsonStr.isNullOrBlank()
    }

    fun isVoiceLockEnabled(): Boolean {
        return prefs.getBoolean(KEY_VOICE_LOCK_ENABLED, isEnrolled())
    }

    fun setVoiceLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_LOCK_ENABLED, enabled).apply()
    }

    fun getEnrolledSampleCount(): Int {
        return prefs.getInt(KEY_ENROLLMENT_SAMPLE_COUNT, if (isEnrolled()) REQUIRED_ENROLLMENT_SAMPLES else 0)
    }

    fun getCurrentEnrollmentProgress(): Int = tempEnrollmentSamples.size

    fun resetEnrollmentSession() {
        tempEnrollmentSamples.clear()
    }

    fun clearEnrollment() {
        tempEnrollmentSamples.clear()
        prefs.edit()
            .remove(KEY_ENROLLED_EMBEDDING)
            .remove(KEY_ENROLLMENT_SAMPLE_COUNT)
            .remove(KEY_LAST_AUTH_TIME)
            .putBoolean(KEY_VOICE_LOCK_ENABLED, false)
            .apply()
        AppLogger.i(TAG, "Voice biometric enrollment wiped.")
    }

    /**
     * Adds an audio sample PCM byte array during enrollment.
     * Returns total samples collected in current session.
     */
    fun addEnrollmentSample(audioPcm: ByteArray): Int {
        val vector = extractAcousticEmbedding(audioPcm) ?: return tempEnrollmentSamples.size
        tempEnrollmentSamples.add(vector)
        AppLogger.i(TAG, "Added enrollment sample #${tempEnrollmentSamples.size}")
        return tempEnrollmentSamples.size
    }

    /**
     * Finalizes enrollment by averaging collected sample vectors into a master biometric profile.
     */
    fun finalizeEnrollment(): Boolean {
        if (tempEnrollmentSamples.size < REQUIRED_ENROLLMENT_SAMPLES) {
            AppLogger.w(TAG, "Cannot finalize enrollment with only ${tempEnrollmentSamples.size} samples.")
            return false
        }

        val master = FloatArray(VECTOR_DIM) { 0f }
        for (sample in tempEnrollmentSamples) {
            for (i in 0 until VECTOR_DIM) {
                master[i] += sample[i]
            }
        }
        val normalized = normalizeVector(master)

        val jsonArray = JSONArray()
        for (v in normalized) {
            jsonArray.put(v.toDouble())
        }

        prefs.edit()
            .putString(KEY_ENROLLED_EMBEDDING, jsonArray.toString())
            .putInt(KEY_ENROLLMENT_SAMPLE_COUNT, tempEnrollmentSamples.size)
            .putBoolean(KEY_VOICE_LOCK_ENABLED, true)
            .apply()

        tempEnrollmentSamples.clear()
        AppLogger.i(TAG, "Voice enrollment finalized successfully with $VECTOR_DIM-dim profile.")
        return true
    }

    fun getEnrolledEmbedding(): FloatArray? {
        val raw = prefs.getString(KEY_ENROLLED_EMBEDDING, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading stored voice embedding", e)
            null
        }
    }

    /**
     * Verifies a PCM audio sample against the stored biometric profile using cosine similarity.
     */
    fun verifySample(audioPcm: ByteArray, customThreshold: Float = DEFAULT_THRESHOLD): VerificationResult {
        val enrolled = getEnrolledEmbedding()
            ?: return VerificationResult(false, 0f, customThreshold, "No enrolled voice profile found.")

        val candidate = extractAcousticEmbedding(audioPcm)
            ?: return VerificationResult(false, 0f, customThreshold, "Audio sample contained insufficient speech energy.")

        val sim = computeCosineSimilarity(candidate, enrolled)
        val isMatch = sim >= customThreshold

        if (isMatch) {
            recordSuccessfulAuth()
            AppLogger.i(TAG, "Voice biometric verification PASSED (score: ${"%.3f".format(sim)} >= $customThreshold)")
        } else {
            AppLogger.w(TAG, "Voice biometric verification FAILED (score: ${"%.3f".format(sim)} < $customThreshold)")
        }

        return VerificationResult(
            isMatch = isMatch,
            similarity = sim,
            threshold = customThreshold,
            reason = if (isMatch) "Voice signature verified" else "Voice mismatch"
        )
    }

    fun isRecentAuthValid(windowMs: Long = 60_000L): Boolean {
        val lastAuth = prefs.getLong(KEY_LAST_AUTH_TIME, 0L)
        return (System.currentTimeMillis() - lastAuth) < windowMs
    }

    fun recordSuccessfulAuth() {
        prefs.edit().putLong(KEY_LAST_AUTH_TIME, System.currentTimeMillis()).apply()
    }

    /**
     * Records audio from microphone for [durationMs] and matches against stored voice embedding.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordAndVerify(
        durationMs: Long = 2500L,
        threshold: Float = DEFAULT_THRESHOLD
    ): VerificationResult = withContext(Dispatchers.IO) {
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
                return@withContext VerificationResult(false, 0f, threshold, "Microphone initialization failed.")
            }
        } catch (e: Exception) {
            return@withContext VerificationResult(false, 0f, threshold, "Microphone access error: ${e.message}")
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
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error recording audio for biometric verification: ${e.message}")
        } finally {
            try {
                recorder.stop()
                recorder.release()
            } catch (_: Exception) {}
        }

        if (totalRead < 3200) {
            return@withContext VerificationResult(false, 0f, threshold, "Utterance too short for analysis.")
        }

        verifySample(pcmAccumulator.copyOf(totalRead), threshold)
    }

    // ─── Acoustic Feature Extraction & Cosine Similarity ─────────────────────

    /**
     * Extracts a normalized 34-dimensional acoustic embedding vector:
     * - 30 Mel-spaced frequency sub-band log energies
     * - Spectral Centroid
     * - Spectral Spread
     * - Spectral Flux
     * - Zero-Crossing Rate
     */
    fun extractAcousticEmbedding(audioPcm: ByteArray): FloatArray? {
        if (audioPcm.size < 1024) return null

        // Convert 16-bit PCM bytes to Float samples in [-1.0, 1.0]
        val numSamples = audioPcm.size / 2
        val samples = FloatArray(numSamples)
        var totalAbsEnergy = 0.0

        for (i in 0 until numSamples) {
            val bLow = audioPcm[i * 2].toInt() and 0xFF
            val bHigh = audioPcm[i * 2 + 1].toInt()
            val sample = (bHigh shl 8) or bLow
            val normalized = sample / 32768.0f
            samples[i] = normalized
            totalAbsEnergy += abs(normalized)
        }

        // Voice Activity / Energy check: avoid silent/empty frames
        val avgEnergy = totalAbsEnergy / numSamples
        if (avgEnergy < 0.003) {
            Log.w(TAG, "Audio sample energy too low ($avgEnergy), rejecting feature extraction.")
            return null
        }

        val frameSize = 512
        val hopSize = 256
        val numFrames = (numSamples - frameSize) / hopSize
        if (numFrames <= 0) return null

        val accumulatedFeatures = FloatArray(VECTOR_DIM) { 0f }
        var validFrames = 0
        var prevSpectrum: FloatArray? = null

        for (f in 0 until numFrames) {
            val offset = f * hopSize
            val frame = FloatArray(frameSize)
            var frameEnergy = 0f
            var zeroCrossings = 0

            // Apply Hamming window
            for (i in 0 until frameSize) {
                val s = samples[offset + i]
                val window = 0.54f - 0.46f * cos(2.0 * Math.PI * i / (frameSize - 1)).toFloat()
                frame[i] = s * window
                frameEnergy += frame[i] * frame[i]
                if (i > 0 && ((samples[offset + i] >= 0 && samples[offset + i - 1] < 0) ||
                            (samples[offset + i] < 0 && samples[offset + i - 1] >= 0))) {
                    zeroCrossings++
                }
            }

            if (frameEnergy < 0.001f) continue // Skip silent frame

            // Compute discrete magnitude spectrum across 30 Mel-spaced bands
            val spectrum = computeMelFilterBankEnergies(frame, numBands = 30)

            // Spectral Centroid & Spread
            var centroidNum = 0.0
            var centroidDen = 0.0
            for (b in spectrum.indices) {
                centroidNum += b * spectrum[b]
                centroidDen += spectrum[b]
            }
            val centroid = if (centroidDen > 0.0) (centroidNum / centroidDen).toFloat() else 0f

            var spreadNum = 0.0
            for (b in spectrum.indices) {
                val diff = b - centroid
                spreadNum += diff * diff * spectrum[b]
            }
            val spread = if (centroidDen > 0.0) sqrt(spreadNum / centroidDen).toFloat() else 0f

            // Spectral Flux
            var flux = 0f
            if (prevSpectrum != null) {
                for (b in spectrum.indices) {
                    val diff = spectrum[b] - prevSpectrum[b]
                    if (diff > 0) flux += diff
                }
            }
            prevSpectrum = spectrum

            val zcr = zeroCrossings.toFloat() / frameSize

            // Accumulate into vector
            for (b in 0 until 30) {
                accumulatedFeatures[b] += spectrum[b]
            }
            accumulatedFeatures[30] += centroid
            accumulatedFeatures[31] += spread
            accumulatedFeatures[32] += flux
            accumulatedFeatures[33] += zcr
            validFrames++
        }

        if (validFrames == 0) return null

        for (i in 0 until VECTOR_DIM) {
            accumulatedFeatures[i] /= validFrames
        }

        return normalizeVector(accumulatedFeatures)
    }

    private fun computeMelFilterBankEnergies(frame: FloatArray, numBands: Int): FloatArray {
        val bands = FloatArray(numBands)
        val halfN = frame.size / 2
        val linearSpectrum = FloatArray(halfN)

        // Approximate DFT power spectrum
        for (k in 0 until halfN) {
            var real = 0.0
            var imag = 0.0
            val omega = 2.0 * Math.PI * k / frame.size
            for (n in frame.indices) {
                val angle = omega * n
                real += frame[n] * cos(angle)
                imag -= frame[n] * sin(angle)
            }
            linearSpectrum[k] = (real * real + imag * imag).toFloat()
        }

        // Map into non-linear Mel bands
        for (b in 0 until numBands) {
            val startBin = (b * b * halfN) / (numBands * numBands)
            val endBin = (((b + 1) * (b + 1) * halfN) / (numBands * numBands)).coerceAtMost(halfN)
            var bandSum = 0.0f
            val count = max(1, endBin - startBin)
            for (k in startBin until endBin) {
                bandSum += linearSpectrum[k]
            }
            bands[b] = ln(1.0f + (bandSum / count))
        }

        return bands
    }

    fun computeCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.size != vecB.size || vecA.isEmpty()) return 0f
        var dot = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in vecA.indices) {
            dot += vecA[i] * vecB[i]
            normA += vecA[i] * vecA[i]
            normB += vecB[i] * vecB[i]
        }

        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-6f) (dot / denom).coerceIn(-1.0f, 1.0f) else 0f
    }

    private fun normalizeVector(vec: FloatArray): FloatArray {
        var sumSq = 0.0f
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm < 1e-6f) return vec
        return FloatArray(vec.size) { i -> vec[i] / norm }
    }
}
