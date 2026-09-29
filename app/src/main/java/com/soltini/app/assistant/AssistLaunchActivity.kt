package com.soltini.app.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.soltini.app.overlay.OverlayService
import com.soltini.app.services.BackgroundVoiceService

/**
 * AssistLaunchActivity
 *
 * A transparent, no-animation Activity that acts as a trampoline when the
 * ROLE_ASSISTANT intent is fired by Android (e.g., holding the home button).
 *
 * It immediately starts the foreground services and finishes itself so
 * the user sees only the floating orb overlay — exactly like Gemini.
 */
class AssistLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the services
        val voiceIntent = Intent(this, BackgroundVoiceService::class.java)
        startForegroundService(voiceIntent)
        OverlayService.start(this)

        // No UI needed — finish immediately
        finish()
    }
}
