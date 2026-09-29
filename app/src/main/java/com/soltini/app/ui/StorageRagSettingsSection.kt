package com.soltini.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.rag.RagKnowledgeEngine
import com.soltini.app.rag.RagSearchResult
import com.soltini.app.settings.AppSettings
import com.soltini.app.storage.AuthorizedLocation
import com.soltini.app.storage.SafStorageManager
import com.soltini.app.storage.UndoBackupRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardBackground = Color(0xFF1E222B)
private val AccentBlue = Color(0xFF4C8DFF)
private val AccentGreen = Color(0xFF00C853)
private val DangerRed = Color(0xFFFF5252)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB0B7C3)

@Composable
fun StorageRagSettingsSection(
    context: Context,
    appSettings: AppSettings?
) {
    val coroutineScope = rememberCoroutineScope()
    val storageManager = remember { SafStorageManager.getInstance(context) }
    val ragEngine = remember {
        val settings = appSettings ?: AppSettings(context)
        RagKnowledgeEngine.getInstance(context, settings)
    }

    var authorizedFolders by remember { mutableStateOf(storageManager.getAuthorizedLocations()) }
    var backups by remember { mutableStateOf(storageManager.backupManager.getAllBackups()) }
    var isIndexing by remember { mutableStateOf(false) }
    var indexingStatus by remember { mutableStateOf("") }
    var indexedDocCount by remember { mutableStateOf(0) }
    var indexedChunkCount by remember { mutableStateOf(0) }

    // Test Search State
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<RagSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // Refresh counts
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val stats = ragEngine.db.getStats()
            indexedDocCount = stats.totalDocuments
            indexedChunkCount = stats.totalChunks
            backups = storageManager.backupManager.getAllBackups()
        }
    }

    // SAF Directory Picker Launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val success = storageManager.addAuthorizedTree(uri)
                if (success) {
                    authorizedFolders = storageManager.getAuthorizedLocations()
                    Toast.makeText(context, "Folder authorized successfully!", Toast.LENGTH_SHORT).show()

                    // Trigger indexing in background
                    coroutineScope.launch(Dispatchers.IO) {
                        isIndexing = true
                        indexingStatus = "Indexing authorized folder..."
                        ragEngine.indexAllAuthorizedFolders(storageManager) { current, total, name ->
                            indexingStatus = "Indexing ($current/$total): $name"
                        }
                        val stats = ragEngine.db.getStats()
                        indexedDocCount = stats.totalDocuments
                        indexedChunkCount = stats.totalChunks
                        isIndexing = false
                        indexingStatus = "Indexing complete!"
                    }
                } else {
                    Toast.makeText(context, "Failed to persist folder permissions.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error granting folder access: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // ── Security Guarantee Banner ────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = AccentGreen)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Zero Silent Storage Access",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "MYRA strictly accesses ONLY the folders you explicitly authorize below using Android's Storage Access Framework (SAF). Entire device storage is never silently accessed.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Authorized Locations ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Authorized Folders (${authorizedFolders.size})",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            Button(
                onClick = { folderPickerLauncher.launch(null) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Folder", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (authorizedFolders.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground.copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "No folders authorized yet",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tap 'Add Folder' above to let MYRA read, search, and manage files in your selected folders.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            authorizedFolders.forEach { folder ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = AccentBlue)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(folder.displayName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(folder.uriString.takeLast(40), color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                            }
                        }

                        IconButton(onClick = {
                            storageManager.removeAuthorizedTree(folder.uriString)
                            authorizedFolders = storageManager.getAuthorizedLocations()
                            Toast.makeText(context, "Folder removed", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove", tint = DangerRed)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── RAG Knowledge System Status ──────────────────────────────────────
        Text(
            text = "RAG Knowledge System (LangChain4j)",
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Indexed Documents", color = TextSecondary, fontSize = 12.sp)
                        Text("$indexedDocCount", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("Knowledge Chunks", color = TextSecondary, fontSize = 12.sp)
                        Text("$indexedChunkCount", color = AccentBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("Parsers Supported", color = TextSecondary, fontSize = 12.sp)
                        Text("PDF, TXT, MD, Code, OCR", color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (indexingStatus.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(indexingStatus, color = AccentBlue, fontSize = 12.sp)
                }

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            isIndexing = true
                            indexingStatus = "Re-indexing all folders..."
                            ragEngine.db.clearAll()
                            ragEngine.indexAllAuthorizedFolders(storageManager) { current, total, name ->
                                indexingStatus = "Processing ($current/$total): $name"
                            }
                            val stats = ragEngine.db.getStats()
                            indexedDocCount = stats.totalDocuments
                            indexedChunkCount = stats.totalChunks
                            isIndexing = false
                            indexingStatus = "Knowledge base up to date!"
                        }
                    },
                    enabled = !isIndexing && authorizedFolders.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isIndexing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Indexing Documents...", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Re-Index Authorized Knowledge", fontSize = 13.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Knowledge Search Playground ──────────────────────────────────────
        Text(
            text = "Test Knowledge Search",
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search your indexed documents...", color = TextSecondary, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Color(0xFF2C3240)
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (searchQuery.isNotBlank()) {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        isSearching = true
                                        searchResults = ragEngine.searchKnowledge(searchQuery, limit = 5)
                                        isSearching = false
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = AccentBlue)
                        }
                    }
                )

                if (searchResults.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Top Relevant Chunks:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))

                    for (hit in searchResults) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF131720))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(hit.chunk.documentName, color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("Score: ${String.format(Locale.US, "%.2f", hit.score)}", color = AccentGreen, fontSize = 11.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = hit.matchSnippet.ifBlank { hit.chunk.text.take(200) },
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Backup Snapshots (Undo Safety) ───────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pre-Modification Backups (${backups.size})",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            if (backups.isNotEmpty()) {
                TextButton(onClick = {
                    storageManager.backupManager.clearAllBackups()
                    backups = storageManager.backupManager.getAllBackups()
                }) {
                    Text("Clear All", color = DangerRed, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (backups.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground.copy(alpha = 0.6f))
            ) {
                Text(
                    text = "Automatic snapshots are created whenever MYRA edits or modifies a file, allowing instant undo.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
            backups.take(5).forEach { backup ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(backup.originalFileName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Saved: ${dateFormat.format(Date(backup.timestamp))}", color = TextSecondary, fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                val res = storageManager.backupManager.restoreBackup(backup.id)
                                Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                                backups = storageManager.backupManager.getAllBackups()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3240)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentBlue)
                            Spacer(Modifier.width(4.dp))
                            Text("Undo", color = TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
