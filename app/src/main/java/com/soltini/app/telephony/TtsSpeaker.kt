package com.soltini.app.telephony

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Lightweight TextToSpeech helper for Myra to speak instant audio announcements
 * when Gemini Live is not connected or in low-latency critical situations (such as incoming calls).
 */
class TtsSpeaker(private val context: Context) {

    companion object {
        private const val TAG = "TtsSpeaker"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeech: String? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isInitialized = true
                    configureVoice()
                    pendingSpeech?.let { text ->
                        speak(text)
                        pendingSpeech = null
                    }
                } else {
                    Log.w(TAG, "TextToSpeech init failed with status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing TextToSpeech: ${e.message}", e)
        }
    }

    private fun configureVoice() {
        val ttsInstance = tts ?: return
        try {
            // Prefer Hindi (India) for natural Hinglish pronunciation
            val hiLocale = Locale("hi", "IN")
            val checkHi = ttsInstance.isLanguageAvailable(hiLocale)
            if (checkHi >= TextToSpeech.LANG_AVAILABLE) {
                ttsInstance.language = hiLocale
            } else {
                val enInLocale = Locale("en", "IN")
                if (ttsInstance.isLanguageAvailable(enInLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    ttsInstance.language = enInLocale
                } else {
                    ttsInstance.language = Locale.getDefault()
                }
            }
            ttsInstance.setPitch(1.05f)
            ttsInstance.setSpeechRate(1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "Voice config error: ${e.message}")
        }
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isInitialized || tts == null) {
            pendingSpeech = text
            return
        }

        try {
            val utteranceId = "tts_${System.currentTimeMillis()}"
            if (onComplete != null) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(id: String?) {
                        if (id == utteranceId) {
                            onComplete()
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            Log.i(TAG, "Spoke announcement: $text")
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed: ${e.message}", e)
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (_: Exception) {}
    }
}
