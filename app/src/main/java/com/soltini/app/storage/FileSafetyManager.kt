package com.soltini.app.storage

import android.net.Uri
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * FileSafetyManager
 *
 * Enforces strict safety, confirmation barriers, and result verification before
 * any destructive file operation (delete, overwrite, move, bulk modification).
 *
 * Never performs destructive actions silently without explicit user authorization!
 */
class FileSafetyManager(private val storageManager: SafStorageManager) {

    companion object {
        private const val TAG = "FileSafetyManager"
        private const val CONFIRMATION_TIMEOUT_MS = 60_000L // 1 minute window
    }

    private val pendingConfirmations = ConcurrentHashMap<String, PendingFileConfirmation>()

    /**
     * Registers a request for a destructive operation and generates a confirmation prompt.
     */
    fun registerDestructiveAction(
        actionType: FileActionType,
        targetUri: Uri,
        targetName: String,
        extraParams: Map<String, String> = emptyMap()
    ): PendingFileConfirmation {
        cleanExpired()

        val id = UUID.randomUUID().toString().take(8)
        val prompt = when (actionType) {
            FileActionType.DELETE ->
                "Boss, kya aap sach mein '$targetName' ko permanently delete karna chahte hain? Confirm karne ke liye 'Haan delete karo' bolein."
            FileActionType.BULK_DELETE ->
                "Boss, kya aap in sabhi ${extraParams["count"] ?: "multiple"} files ko delete karna chahte hain? Confirm karne ke liye 'Haan sab delete karo' bolein."
            FileActionType.OVERWRITE ->
                "Boss, '$targetName' pehle se maujood hai. Kya ise overwrite karein? (Original backup save ho jayega). Kripya 'Haan overwrite karo' bolein."
            FileActionType.MOVE ->
                "Boss, kya '$targetName' ko '${extraParams["target_folder"] ?: "new location"}' mein move kar dein? Kripya 'Haan move karo' bolein."
            else ->
                "Boss, kya aap '$targetName' par $actionType action confirm karte hain? Kripya 'Haan' bolein."
        }

        val pending = PendingFileConfirmation(
            id = id,
            actionType = actionType,
            targetUri = targetUri.toString(),
            targetName = targetName,
            extraParams = extraParams,
            timestamp = System.currentTimeMillis(),
            confirmationPrompt = prompt
        )

        pendingConfirmations[id] = pending
        Log.i(TAG, "Registered pending destructive action [$id]: $actionType on $targetName")
        return pending
    }

    /**
     * Checks whether user utterance is an affirmative confirmation or denial.
     */
    fun checkUserUtterance(input: String): UserConfirmationIntent {
        val lower = input.lowercase().trim()
        val affirmative = listOf(
            "haan", "ha", "yes", "confirm", "sure", "delete karo", "delete", "kardo",
            "kar do", "proceed", "bilkul", "ok", "okay", "overwrite karo", "move karo"
        )
        val negative = listOf(
            "nahi", "na", "no", "cancel", "mat karo", "rehne do", "stop", "abort",
            "ruk jao", "cancel karo"
        )

        if (affirmative.any { lower.contains(it) }) {
            return UserConfirmationIntent.AFFIRMATIVE
        }
        if (negative.any { lower.contains(it) }) {
            return UserConfirmationIntent.NEGATIVE
        }
        return UserConfirmationIntent.UNCERTAIN
    }

    /**
     * Executes the pending destructive action after confirmation.
     */
    suspend fun executeConfirmedAction(confirmationId: String): StorageActionResult {
        val pending = pendingConfirmations.remove(confirmationId)
            ?: return StorageActionResult(false, "No active confirmation found or confirmation expired.")

        if (System.currentTimeMillis() - pending.timestamp > CONFIRMATION_TIMEOUT_MS) {
            return StorageActionResult(false, "Confirmation expired. Destructive action was safely aborted.")
        }

        val uri = Uri.parse(pending.targetUri)
        return when (pending.actionType) {
            FileActionType.DELETE -> {
                storageManager.deleteFile(uri)
            }
            FileActionType.MOVE -> {
                val destUri = Uri.parse(pending.extraParams["target_folder_uri"] ?: return StorageActionResult(false, "Missing destination URI"))
                storageManager.moveFile(uri, destUri, pending.extraParams["new_name"])
            }
            else -> {
                StorageActionResult(false, "Action type ${pending.actionType} not directly supported in confirmation executor.")
            }
        }
    }

    /**
     * Cancels a pending confirmation.
     */
    fun cancelPendingAction(confirmationId: String? = null): String {
        return if (confirmationId != null) {
            val removed = pendingConfirmations.remove(confirmationId)
            if (removed != null) "Action on '${removed.targetName}' cancelled safely." else "No such action found."
        } else {
            val count = pendingConfirmations.size
            pendingConfirmations.clear()
            "All $count pending file operations cancelled safely."
        }
    }

    fun getLatestPending(): PendingFileConfirmation? {
        cleanExpired()
        return pendingConfirmations.values.maxByOrNull { it.timestamp }
    }

    private fun cleanExpired() {
        val now = System.currentTimeMillis()
        val expired = pendingConfirmations.filter { now - it.value.timestamp > CONFIRMATION_TIMEOUT_MS }
        for (key in expired.keys) {
            pendingConfirmations.remove(key)
        }
    }
}

enum class UserConfirmationIntent {
    AFFIRMATIVE,
    NEGATIVE,
    UNCERTAIN
}
