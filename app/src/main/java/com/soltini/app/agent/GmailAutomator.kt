package com.soltini.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * GmailAutomator
 *
 * Composes email via Android's standard ACTION_SENDTO intent, which is reliable and
 * doesn't depend on Gmail's UI layout (unlike accessibility-click automation).
 *
 * SAFETY DESIGN NOTE: sending an email is an irreversible, externally-visible action —
 * same category as the file-delete flow elsewhere in this app, which requires explicit
 * "Haan delete karo" confirmation before proceeding. Following that same convention,
 * composeEmail() defaults to autoSend = false: it opens Gmail with To/Subject/Body
 * pre-filled and lets Boss review and tap Send himself. Only pass autoSend = true if
 * Boss has explicitly confirmed the email content and asked you to send it directly —
 * auto-send uses accessibility clickByText("Send") and will fire whatever is in the
 * compose screen without a second review step.
 *
 * Tools:
 *  - send_email(to, subject, body, autoSend)
 */
class GmailAutomator(private val context: Context) {

    companion object {
        private const val TAG = "GmailAutomator"
        private const val GMAIL_PKG = "com.google.android.gm"
    }

    fun composeEmail(to: String, subject: String, body: String, autoSend: Boolean = false): JSONObject {
        if (to.isBlank()) return error("recipient email address is required")

        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Prefer targeting Gmail directly if installed, so we know which app opens
            // (and therefore whether clickByText("Send") below will find the right button).
            val gmailIntent = Intent(intent).setPackage(GMAIL_PKG)
            val usedGmailDirectly = gmailIntent.resolveActivity(context.packageManager) != null

            if (usedGmailDirectly) {
                context.startActivity(gmailIntent)
            } else if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent) // generic email app chooser
            } else {
                return error("No email app found on this device")
            }

            Log.i(TAG, "composeEmail(to=$to) -> compose screen opened (autoSend=$autoSend)")

            if (!autoSend) {
                return result(
                    "status", "draft_ready",
                    "to", to,
                    "subject", subject,
                    "note", "Email draft opened for Boss to review and send manually."
                )
            }

            // autoSend path: only reached if the caller explicitly confirmed intent to send.
            if (!usedGmailDirectly) {
                return result(
                    "status", "draft_ready",
                    "to", to,
                    "note", "Gmail app not detected; opened a different email app — please send manually."
                )
            }

            val svc = SoltiniAccessibilityService.getInstance()
                ?: return result(
                    "status", "draft_ready",
                    "to", to,
                    "note", "Accessibility Service not active, could not auto-send. Draft is open — please tap Send."
                )

            Thread.sleep(1200) // let Gmail's compose screen finish rendering
            val sent = svc.clickByText("Send")
            return if (sent) {
                result("status", "sent", "to", to, "subject", subject)
            } else {
                result(
                    "status", "draft_ready",
                    "to", to,
                    "note", "Could not find the Send button automatically — draft is open, please tap Send."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "composeEmail failed: ${e.message}", e)
            error("Could not compose email: ${e.message}")
        }
    }

    private fun result(vararg pairs: Any): JSONObject = JSONObject().apply {
        var i = 0
        while (i < pairs.size - 1) {
            put(pairs[i].toString(), pairs[i + 1])
            i += 2
        }
    }

    private fun error(msg: String): JSONObject = JSONObject().apply { put("status", "error"); put("error", msg) }
}
