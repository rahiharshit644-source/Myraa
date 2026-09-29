package com.soltini.app.assistant

import android.content.Intent
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * SoltiniVoiceInteractionService
 *
 * This is the entry point that tells Android that Soltini is a valid system assistant.
 * It must be declared in the manifest with BIND_VOICE_INTERACTION permission and
 * the android.service.voice.VoiceInteractionService intent-filter.
 *
 * When the user sets Soltini as the default assistant (ROLE_ASSISTANT), Android
 * keeps this service running persistently in the background — exactly like Google Gemini.
 * The service launches a VoiceInteractionSession when triggered (home button long-press,
 * power button double-tap, swipe from corner, etc.).
 */
class SoltiniVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "SoltiniVIS"
    }

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "VoiceInteractionService ready — Soltini is the active system assistant")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.i(TAG, "VoiceInteractionService shutdown")
    }
}
