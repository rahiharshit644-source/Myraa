package com.soltini.app.homeautomation.generator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * FirmwareFileUtils
 *
 * Utility functions for exporting generated ESP32 Arduino .ino sketches:
 * 1. Copy sketch directly to system clipboard.
 * 2. Share sketch via Android system share sheet using FileProvider.
 * 3. Save sketch directly to device Downloads folder via MediaStore API.
 *
 * Connections:
 * - Invoked by AddDeviceScreen and CodePreviewDialog in the Device Wizard.
 * - Uses FileProvider configured in AndroidManifest.xml and res/xml/file_paths.xml.
 */
object FirmwareFileUtils {

    private const val TAG = "FirmwareFileUtils"

    /**
     * Copies code text to Android system clipboard.
     */
    fun copyToClipboard(context: Context, text: String, label: String = "ESP32 Firmware"): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard: ${e.message}")
            false
        }
    }

    /**
     * Shares .ino code as an attachment or plain text intent via FileProvider.
     */
    fun shareSketch(context: Context, code: String, fileName: String): Boolean {
        return try {
            val safeName = if (fileName.endsWith(".ino")) fileName else "$fileName.ino"
            val cacheDir = File(context.cacheDir, "sketches")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val file = File(cacheDir, safeName)
            FileOutputStream(file).use { out ->
                out.write(code.toByteArray(Charsets.UTF_8))
            }

            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ESP32 Firmware: $safeName")
                putExtra(Intent.EXTRA_TEXT, code)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(shareIntent, "Share ESP32 Firmware ($safeName)"))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share sketch: ${e.message}", e)
            false
        }
    }

    /**
     * Saves .ino file directly to public Downloads folder using MediaStore (Android 10+)
     * or standard external storage.
     */
    fun saveToDownloads(context: Context, code: String, fileName: String): Result<String> {
        return try {
            val safeName = if (fileName.endsWith(".ino")) fileName else "$fileName.ino"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Soltini")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, contentValues)
                    ?: return Result.failure(Exception("Failed to allocate MediaStore entry"))

                resolver.openOutputStream(itemUri)?.use { out ->
                    out.write(code.toByteArray(Charsets.UTF_8))
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)

                Result.success("Saved to Downloads/Soltini/$safeName")
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadDir, "Soltini")
                if (!targetDir.exists()) targetDir.mkdirs()
                val file = File(targetDir, safeName)
                FileOutputStream(file).use { out ->
                    out.write(code.toByteArray(Charsets.UTF_8))
                }
                Result.success("Saved to ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file to Downloads: ${e.message}", e)
            Result.failure(e)
        }
    }
}
