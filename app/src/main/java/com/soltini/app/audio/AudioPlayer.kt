package com.soltini.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.soltini.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * Handles playing raw 24kHz 16-bit mono PCM audio chunks received from Gemini Live API.
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 24000
    }

    private var audioTrack: AudioTrack? = null
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var playbackJob: Job? = null
    private var isPlaying = false

    private val _speakerAmplitude = MutableStateFlow(0f)
    val speakerAmplitude: StateFlow<Float> = _speakerAmplitude.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    fun start() {
        if (isPlaying) return

        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val bufferSize = maxOf(minBufferSize, SAMPLE_RATE * 2) // 1 sec buffer

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying = true
            AppLogger.i(TAG, "AudioTrack initialized and playing at 24000Hz")

            startPlaybackLoop()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error initializing AudioTrack: ${e.message}", e)
        }
    }

    private fun startPlaybackLoop() {
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            var playChunkCount = 0
            var totalFramesWritten = 0L
            var queueEmptySince = 0L

            while (isPlaying) {
                val chunk = audioQueue.poll()
                if (chunk != null && chunk.isNotEmpty()) {
                    _isSpeaking.value = true
                    queueEmptySince = 0L
                    calculateAmplitude(chunk)

                    val written = audioTrack?.write(chunk, 0, chunk.size) ?: 0
                    if (written < 0) {
                        AppLogger.e(TAG, "AudioTrack write error code: $written")
                    } else {
                        // Track total PCM frames written (2 bytes per frame for 16-bit mono)
                        totalFramesWritten += written / 2
                        playChunkCount++
                        if (playChunkCount % 50 == 1) {
                            AppLogger.d(TAG, "Writing bytes to AudioTrack: chunk #$playChunkCount (${chunk.size} bytes)")
                        }
                    }
                } else {
                    // Queue is empty — but audio may still be playing in hardware buffer.
                    // Calculate how many frames the hardware has actually played using playbackHeadPosition.
                    if (_isSpeaking.value) {
                        if (queueEmptySince == 0L) {
                            queueEmptySince = System.currentTimeMillis()
                        }
                        val track = audioTrack
                        val hardwareDone = if (track != null) {
                            val headPosition = track.playbackHeadPosition.toLong()
                            // playbackHeadPosition wraps at Int.MAX_VALUE — normalise it
                            val normHead = headPosition and 0xFFFFFFFFL
                            normHead >= (totalFramesWritten and 0xFFFFFFFFL)
                        } else {
                            true
                        }
                        if (hardwareDone) {
                            // Audio has fully drained from the hardware DAC
                            _isSpeaking.value = false
                            _speakerAmplitude.value = 0f
                            totalFramesWritten = 0L
                            AppLogger.d(TAG, "AudioTrack hardware drain complete — mic unmuting")
                        }
                    }
                    Thread.sleep(10)
                }
            }
        }
    }

    fun playChunk(chunk: ByteArray) {
        if (!isPlaying) start()
        audioQueue.add(chunk)
    }

    /**
     * Called when model response is interrupted by user barge-in.
     * Immediately halts playback and clears pending audio buffer.
     */
    fun interrupt() {
        AppLogger.i(TAG, "Interrupted! Clearing audio queue and flushing track")
        audioQueue.clear()
        try {
            audioTrack?.let { track ->
                track.pause()
                track.flush()
                track.play()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error flushing AudioTrack on interrupt: ${e.message}", e)
        }
        _isSpeaking.value = false
        _speakerAmplitude.value = 0f
    }

    private fun calculateAmplitude(pcmData: ByteArray) {
        var maxSample = 0
        var i = 0
        while (i < pcmData.size - 1) {
            val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            val absSample = abs(shortSample.toInt())
            if (absSample > maxSample) {
                maxSample = absSample
            }
            i += 2
        }
        val norm = (maxSample / 32768f).coerceIn(0f, 1f)
        _speakerAmplitude.value = norm
    }

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        audioQueue.clear()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing AudioTrack: ${e.message}", e)
        }
        audioTrack = null
        _isSpeaking.value = false
        _speakerAmplitude.value = 0f
        AppLogger.i(TAG, "AudioTrack stopped")
    }
}
