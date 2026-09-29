package com.soltini.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.soltini.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Captures microphone audio at 16kHz 16-bit mono PCM for Gemini Live API streaming.
 */
class AudioRecorder(
    private val onAudioChunkCaptured: (ByteArray) -> Unit,
    var onSpeechStateChanged: ((Boolean) -> Unit)? = null
) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    var isMuted = false
    var isDataSaverEnabled: Boolean = true
    var vadThreshold: Float = 0.010f
    var micGain: Float = 1.5f
    var isModelSpeaking: Boolean = false
    var isScreenRecordingModeEnabled: Boolean = false

    // Auto-calibrating ambient noise floor
    private var ambientNoiseFloor: Float = 0.005f

    private val _micAmplitude = MutableStateFlow(0f)
    val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            AppLogger.e(TAG, "Invalid buffer size for AudioRecord: $minBufferSize")
            return
        }

        val bufferSize = maxOf(minBufferSize, SAMPLE_RATE * 2 / 10) // ~200ms chunk buffer

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.w(TAG, "VOICE_COMMUNICATION failed, falling back to MIC source")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.e(TAG, "AudioRecord failed to initialize state")
                audioRecord?.release()
                audioRecord = null
                return
            }

            // Enable hardware echo cancellation and noise suppression if available
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                    aec?.enabled = true
                    AppLogger.i(TAG, "AcousticEchoCanceler enabled")
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(audioRecord!!.audioSessionId)
                    ns?.enabled = true
                    AppLogger.i(TAG, "NoiseSuppressor enabled")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not apply audio FX: ${e.message}")
            }

            audioRecord?.startRecording()
            isRecording = true
            AppLogger.i(TAG, "AudioRecord started recording at 16000Hz PCM 16-bit")

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(2048)
                var chunkCount = 0
                var errorCount = 0
                while (isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        errorCount = 0
                        val rawChunk = buffer.copyOf(bytesRead)
                        val chunk = if (micGain != 1.0f) applySoftGain(rawChunk, micGain) else rawChunk
                        val amp = calculateAmplitude(chunk)

                        if (!isMuted) {
                            var outputChunk = chunk
                            
                            if (isScreenRecordingModeEnabled && isModelSpeaking) {
                                // HARD MUTE: When Screen Recording Mode is ON, the mic is completely muted
                                // while the AI is speaking. isModelSpeaking is now set to false only after
                                // the AudioTrack hardware buffer fully drains, so this is precise and reliable.
                                outputChunk = ByteArray(chunk.size)
                            }
                            
                            onAudioChunkCaptured(outputChunk)
                            onSpeechStateChanged?.invoke(amp > 0.005f)
                            chunkCount++
                            if (chunkCount % 100 == 1) {
                                AppLogger.d(TAG, "AudioRecord captured chunk #$chunkCount ($bytesRead bytes)")
                            }
                        }
                    } else if (bytesRead < 0) {
                        AppLogger.e(TAG, "AudioRecord read error code: $bytesRead")
                        errorCount++
                        Thread.sleep(50)
                        
                        // If we get consecutive errors (e.g., another app took the mic), try to restart
                        if (errorCount > 5) {
                            AppLogger.w(TAG, "Consecutive audio errors. Attempting to restart microphone...")
                            try {
                                audioRecord?.stop()
                                audioRecord?.release()
                            } catch (e: Exception) {}
                            
                            // Try to re-init
                            try {
                                audioRecord = AudioRecord(
                                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                                    SAMPLE_RATE,
                                    CHANNEL_CONFIG,
                                    AUDIO_FORMAT,
                                    bufferSize
                                )
                                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                                    audioRecord = AudioRecord(
                                        MediaRecorder.AudioSource.MIC,
                                        SAMPLE_RATE,
                                        CHANNEL_CONFIG,
                                        AUDIO_FORMAT,
                                        bufferSize
                                    )
                                }
                                audioRecord?.startRecording()
                                errorCount = 0
                                AppLogger.i(TAG, "Microphone restarted successfully")
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Failed to restart microphone: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "RECORD_AUDIO permission missing: ${e.message}", e)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error starting AudioRecord: ${e.message}", e)
        }
    }

    private fun applySoftGain(pcmData: ByteArray, multiplier: Float): ByteArray {
        if (multiplier == 1.0f) return pcmData
        val boosted = ByteArray(pcmData.size)
        var i = 0
        while (i < pcmData.size - 1) {
            val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
            val shortSample = sample.toShort().toInt()
            val scaled = (shortSample * multiplier).toInt().coerceIn(-32768, 32767)
            boosted[i] = scaled.toByte()
            boosted[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
        return boosted
    }

    private fun calculateAmplitude(pcmData: ByteArray): Float {
        var maxSample = 0
        var i = 0
        while (i < pcmData.size - 1) {
            val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
            val absSample = abs(sample.toShort().toInt())
            if (absSample > maxSample) {
                maxSample = absSample
            }
            i += 2
        }
        val norm = if (isMuted) 0f else (maxSample / 32768f).coerceIn(0f, 1f)
        _micAmplitude.value = norm
        return norm
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
            aec?.release()
            ns?.release()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping AudioRecord: ${e.message}", e)
        }
        audioRecord = null
        aec = null
        ns = null
        _micAmplitude.value = 0f
        AppLogger.i(TAG, "AudioRecord stopped")
    }
}

