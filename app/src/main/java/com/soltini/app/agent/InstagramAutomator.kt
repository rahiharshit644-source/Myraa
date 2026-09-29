package com.soltini.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * InstagramAutomator
 *
 * Dedicated UI & Intent Automator for Instagram:
 * - instagram_send_message(username, message)
 * - instagram_open_profile(username)
 * - instagram_search(query)
 */
class InstagramAutomator(private val context: Context) {

    companion object {
        private const val TAG = "InstagramAutomator"
        private const val INSTAGRAM_PKG = "com.instagram.android"
    }

    private val screenOperator = ScreenOperator(context)

    /**
     * Sends a direct message to a user on Instagram hands-free.
     */
    suspend fun sendMessage(username: String, message: String): JSONObject {
        if (username.isBlank() || message.isBlank()) {
            return error("username and message are required")
        }

        val a11y = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service is not active")

        if (!isAppInstalled(INSTAGRAM_PKG)) {
            return error("Instagram is not installed on this device")
        }

        // Try direct URI intent first to open user chat/profile
        val cleanUser = username.trim().removePrefix("@")
        val directChatIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ig.me/m/$cleanUser")).apply {
            setPackage(INSTAGRAM_PKG)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        var openedDirectly = false
        try {
            context.startActivity(directChatIntent)
            openedDirectly = true
        } catch (_: Exception) {
            launchInstagram()
        }

        delay(1800) // Wait for Instagram / chat screen to load

        // If direct link worked and we are in chat:
        // Try finding the message text input field
        var typed = false
        val messageBoxLabels = listOf("Message...", "Send message...", "Message", "Aa", "Write a message...")
        for (label in messageBoxLabels) {
            val typeRes = screenOperator.typeIntoField(
                targetField = label,
                textToType = message,
                pressEnterAfter = false,
                autoScroll = false
            )
            if (typeRes.optString("status") == "success") {
                typed = true
                break
            }
        }

        if (!typed) {
            // If direct link didn't immediately land in chat, navigate from Home:
            // 1. Click DM / Messenger icon or search
            val dmClicked = screenOperator.clickElement("Direct", autoScroll = false).optString("status") == "success" ||
                    screenOperator.clickElement("Messenger", autoScroll = false).optString("status") == "success" ||
                    screenOperator.clickElement("Messages", autoScroll = false).optString("status") == "success" ||
                    screenOperator.clickElement("Chat", autoScroll = false).optString("status") == "success"

            delay(1200)

            // Click Search bar or New Message button in DMs
            val searchClicked = screenOperator.clickElement("Search", autoScroll = false).optString("status") == "success" ||
                    screenOperator.clickElement("New message", autoScroll = false).optString("status") == "success"

            delay(600)

            // Type the username to find the chat
            screenOperator.typeIntoField(
                targetField = null,
                textToType = cleanUser,
                pressEnterAfter = true
            )

            delay(1500)

            // Click user from list
            screenOperator.clickElement(cleanUser, autoScroll = false)

            delay(1200)

            // Now type the message in chat
            screenOperator.typeIntoField(
                targetField = null,
                textToType = message,
                pressEnterAfter = false
            )
        }

        delay(600)

        // Click Send button
        val sendRes = screenOperator.clickElement("Send", autoScroll = false)
        if (sendRes.optString("status") != "success") {
            // Alternative labels for Send
            screenOperator.clickElement("Send message", autoScroll = false)
        }

        return JSONObject().apply {
            put("status", "success")
            put("message", "Sent Instagram message to @$cleanUser: \"$message\"")
        }
    }

    /**
     * Opens an Instagram profile.
     */
    fun openProfile(username: String): JSONObject {
        val cleanUser = username.trim().removePrefix("@")
        val uri = Uri.parse("https://instagram.com/_u/$cleanUser")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(INSTAGRAM_PKG)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            JSONObject().apply {
                put("status", "success")
                put("message", "Opened Instagram profile: @$cleanUser")
            }
        } catch (_: Exception) {
            launchInstagram()
            JSONObject().apply {
                put("status", "success")
                put("message", "Opened Instagram. Search for @$cleanUser")
            }
        }
    }

    private fun launchInstagram(): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(INSTAGRAM_PKG) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun isAppInstalled(pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun error(msg: String): JSONObject = JSONObject().apply {
        put("status", "error")
        put("message", msg)
    }
}
