package com.soltini.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject

/**
 * WhatsAppAutomator
 *
 * Automates WhatsApp using AccessibilityService UI automation.
 *
 * Tools:
 *  - whatsapp_send_message
 *  - whatsapp_call
 *  - whatsapp_video_call
 */
class WhatsAppAutomator(private val context: Context) {

    companion object {
        private const val TAG = "WhatsAppAutomator"
        private const val WHATSAPP_PKG = "com.whatsapp"
    }

    /**
     * Sends a message to a contact by automating the UI.
     */
    fun sendMessage(contactName: String, message: String): JSONObject {
        if (contactName.isBlank() || message.isBlank()) {
            return error("contact_name and message are required")
        }

        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        if (!launchWhatsApp()) return error("WhatsApp not installed")
        Thread.sleep(1500) // Wait for app to open

        // 1. Click Search icon
        if (!svc.clickByText("Search")) {
            return error("Could not find WhatsApp search button")
        }
        Thread.sleep(500)

        // 2. Type contact name
        if (!svc.typeText(contactName)) {
            return error("Could not type in search field")
        }
        Thread.sleep(1500) // Wait for search results

        // 3. Click contact (we try to click the exact name, or something close)
        if (!svc.clickByText(contactName)) {
            return error("Contact '$contactName' not found in search results")
        }
        Thread.sleep(1000) // Wait for chat to open

        // FIX: verify the chat screen actually opened before continuing. If the tap landed on
        // the contact's avatar/DP instead of their name/row, WhatsApp opens the contact-info
        // (profile) screen instead — which has no message box. Rather than blindly typing into
        // whatever is on screen, check for an editable field first; if it's missing, this is
        // almost certainly the profile screen, so back out and retry the contact tap once before
        // giving up with a clear error (instead of the confusing downstream "could not type").
        if (!svc.hasEditableField()) {
            Log.w(TAG, "sendMessage: no message box found after tapping '$contactName' — likely opened profile/info screen instead of chat. Backing out and retrying once.")
            svc.pressBack()
            Thread.sleep(500)
            if (!svc.clickByText(contactName)) {
                return error("Contact '$contactName' select nahi ho paaya")
            }
            Thread.sleep(1000)
            if (!svc.hasEditableField()) {
                return error("Chat screen open nahi hua — profile/info page khul gaya hai, message box nahi mila")
            }
        }

        // 4. Type message
        if (!svc.typeText(message)) {
            return error("Could not type message in chat")
        }
        Thread.sleep(500)

        // 5. Click Send
        if (!svc.clickByText("Send")) {
            // Sometimes it's just an icon without text, we might need a coordinate fallback,
            // but "Send" usually works as content description on the button.
            return error("Could not find Send button")
        }

        return result("status", "message_sent", "contact", contactName)
    }

    /**
     * Starts a voice or video call.
     */
    fun makeCall(contactName: String, isVideo: Boolean): JSONObject {
        if (contactName.isBlank()) return error("contact_name is required")

        com.soltini.app.telephony.CallNotificationManager.getInstance(context).markOutgoingCallInitiated(contactName)

        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        if (!launchWhatsApp()) return error("WhatsApp not installed")
        Thread.sleep(1500)

        if (!svc.clickByText("Search")) return error("Could not find WhatsApp search button")
        Thread.sleep(500)

        if (!svc.typeText(contactName)) return error("Could not type in search field")
        Thread.sleep(1500)

        if (!svc.clickByText(contactName)) return error("Contact '$contactName' not found")
        Thread.sleep(1000)

        // FIX: same chat-vs-profile verification as sendMessage — a call button only exists
        // inside the actual chat screen, not on some intermediate screen, so confirm we're
        // somewhere sensible before hunting for the call button. (Chat screens for calling
        // don't strictly need an editable field the way messaging does, so here we just retry
        // the tap once if the very next click for the call button fails — see below.)
        val callButton = if (isVideo) "Video call" else "Voice call"
        if (!svc.clickByText(callButton) && !svc.clickByText("Call")) {
            // Retry once: the first tap may have landed on the contact's avatar (opening their
            // profile/info screen) instead of the chat — back out and try the name/row again.
            svc.pressBack()
            Thread.sleep(500)
            if (!svc.clickByText(contactName)) return error("Contact '$contactName' select nahi ho paaya")
            Thread.sleep(1000)
            if (!svc.clickByText(callButton) && !svc.clickByText("Call")) {
                return error("Could not find $callButton button in chat")
            }
        }

        return result("status", "call_started", "contact", contactName, "video", isVideo.toString())
    }

    private fun launchWhatsApp(): Boolean {
        val pm: PackageManager = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(WHATSAPP_PKG) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    private fun result(vararg pairs: Any): JSONObject = JSONObject().apply {
        var i = 0
        while (i < pairs.size - 1) {
            put(pairs[i].toString(), pairs[i + 1])
            i += 2
        }
    }

    private fun error(msg: String): JSONObject = JSONObject().apply { put("error", msg) }
}
