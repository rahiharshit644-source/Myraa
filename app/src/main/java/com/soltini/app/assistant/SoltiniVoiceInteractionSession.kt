package com.soltini.app.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.soltini.app.overlay.OverlayService
import com.soltini.app.services.BackgroundVoiceService

/**
 * SoltiniVoiceInteractionSession
 *
 * Created each time the user triggers the assistant gesture (long-press home,
 * corner swipe, etc.). This is where we hook into the OS microphone pipeline and
 * launch the Gemini Live connection.
 */
class SoltiniVoiceInteractionSession(
    private val context: Context
) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "SoltiniSession"
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "Assistant triggered — waking Soltini")

        // Wake the screen and start the voice service + overlay
        val voiceIntent = Intent(context, BackgroundVoiceService::class.java)
        context.startForegroundService(voiceIntent)

        OverlayService.start(context)

        // Immediately hide the session UI — Soltini's floating orb IS the UI
        hide()
    }

    override fun onHide() {
        super.onHide()
        Log.i(TAG, "Session hidden")
    }
}
