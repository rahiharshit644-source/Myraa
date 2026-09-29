package com.soltini.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val tag: String,
    val level: String,
    val message: String
)

object AppLogger {
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logIdCounter = 0L

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    @Synchronized
    fun log(tag: String, level: String, message: String) {
        val time = dateFormat.format(Date())
        when (level.uppercase()) {
            "ERROR" -> Log.e(tag, message)
            "WARN" -> Log.w(tag, message)
            "INFO" -> Log.i(tag, message)
            else -> Log.d(tag, message)
        }

        val entry = LogEntry(
            id = ++logIdCounter,
            timestamp = time,
            tag = tag,
            level = level.uppercase(),
            message = message
        )

        val currentList = _logs.value.toMutableList()
        if (currentList.size > 500) {
            currentList.removeAt(0)
        }
        currentList.add(entry)
        _logs.value = currentList
    }

    fun d(tag: String, message: String) = log(tag, "DEBUG", message)
    fun i(tag: String, message: String) = log(tag, "INFO", message)
    fun w(tag: String, message: String) = log(tag, "WARN", message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
        log(tag, "ERROR", msg)
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun getAllLogsText(): String {
        return _logs.value.joinToString(separator = "\n") { entry ->
            "[${entry.timestamp}] [${entry.level}] [${entry.tag}]: ${entry.message}"
        }
    }

    fun copyToClipboard(context: Context): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Gemini Live Logs", getAllLogsText())
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            false
        }
    }
}
