package com.soltini.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.memory.DiaryEntry
import com.soltini.app.memory.MyraUnifiedMemory
import com.soltini.app.telephony.TtsSpeaker
import com.soltini.app.ui.theme.AccentBlue
import com.soltini.app.ui.theme.AccentPurple
import com.soltini.app.ui.theme.DarkBackground
import com.soltini.app.ui.theme.DarkBorder
import com.soltini.app.ui.theme.DarkSurface
import com.soltini.app.ui.theme.DarkSurfaceVariant
import com.soltini.app.ui.theme.GreenLive
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DailyDiaryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unifiedMemory = remember { MyraUnifiedMemory.getInstance(context) }
    val ttsSpeaker = remember { TtsSpeaker(context) }

    var entries by remember { mutableStateOf<List<DiaryEntry>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf("ALL") }
    var showAddDialog by remember { mutableStateOf(false) }
    var entryToEdit by remember { mutableStateOf<DiaryEntry?>(null) }
    var entryToDelete by remember { mutableStateOf<DiaryEntry?>(null) }
    var isGeneratingRecap by remember { mutableStateOf(false) }

    fun refreshEntries() {
        scope.launch(Dispatchers.IO) {
            val list = if (searchQuery.isNotBlank()) {
                unifiedMemory.searchDiary(searchQuery)
            } else {
                unifiedMemory.getAllDiaryEntries()
            }
            withContext(Dispatchers.Main) {
                entries = list
            }
        }
    }

    LaunchedEffect(searchQuery) {
        refreshEntries()
    }

    val filteredEntries = remember(entries, selectedCategoryFilter) {
        if (selectedCategoryFilter == "ALL") {
            entries
        } else {
            entries.filter { it.category.equals(selectedCategoryFilter, ignoreCase = true) }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = DarkBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = AccentBlue,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Diary Note")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // ── Top Header ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Myra Memory Diary",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AccentPurple.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "${entries.size} Entries",
                                    color = AccentPurple,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            "Permanent daily reflections, notes & auto-recaps",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                // Auto-generate recap button
                IconButton(
                    onClick = {
                        isGeneratingRecap = true
                        scope.launch(Dispatchers.IO) {
                            val generated = unifiedMemory.generateDailyRecapSummary()
                            withContext(Dispatchers.Main) {
                                isGeneratingRecap = false
                                if (generated != null) {
                                    Toast.makeText(context, "Today's recap created! ✓", Toast.LENGTH_SHORT).show()
                                    refreshEntries()
                                } else {
                                    Toast.makeText(context, "No conversation turns yet today to recap.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = "Generate Daily Recap",
                        tint = if (isGeneratingRecap) GreenLive else AccentBlue
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Search & Filter ───────────────────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search diary, notes, or memories...", color = TextSecondary, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface
                ),
                singleLine = true
            )

            Spacer(Modifier.height(10.dp))

            // Category Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiaryFilterChip("All", selected = selectedCategoryFilter == "ALL") {
                    selectedCategoryFilter = "ALL"
                }
                DiaryFilterChip("🌅 Recaps", selected = selectedCategoryFilter == "DAILY_SUMMARY") {
                    selectedCategoryFilter = "DAILY_SUMMARY"
                }
                DiaryFilterChip("📝 Notes", selected = selectedCategoryFilter == "NOTE") {
                    selectedCategoryFilter = "NOTE"
                }
                DiaryFilterChip("💡 Ideas", selected = selectedCategoryFilter == "IDEA") {
                    selectedCategoryFilter = "IDEA"
                }
                DiaryFilterChip("💖 Personal", selected = selectedCategoryFilter == "PERSONAL") {
                    selectedCategoryFilter = "PERSONAL"
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Diary List ────────────────────────────────────────────────────
            if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = BorderStroke(1.dp, DarkBorder),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Book,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No Diary Entries Yet",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Voice se bol kar ya '+' button dabakar notes add karein!\nJaise: \"Myra, diary me likho ki aaj mera exam bahut achha gaya\"",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredEntries, key = { it.id }) { entry ->
                        DiaryEntryCard(
                            entry = entry,
                            onReadAloud = {
                                val textToSpeak = "${entry.title}. ${entry.content}"
                                ttsSpeaker.speak(textToSpeak)
                                Toast.makeText(context, "Speaking entry...", Toast.LENGTH_SHORT).show()
                            },
                            onEdit = { entryToEdit = entry },
                            onDelete = { entryToDelete = entry }
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }

    // ── Add / Edit Dialog ─────────────────────────────────────────────────────
    if (showAddDialog || entryToEdit != null) {
        val isEditing = entryToEdit != null
        var titleInput by remember { mutableStateOf(entryToEdit?.title ?: "") }
        var contentInput by remember { mutableStateOf(entryToEdit?.content ?: "") }
        var moodInput by remember { mutableStateOf(entryToEdit?.mood ?: "PRODUCTIVE") }
        var categoryInput by remember { mutableStateOf(entryToEdit?.category ?: "NOTE") }

        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                entryToEdit = null
            },
            containerColor = DarkSurface,
            title = {
                Text(
                    if (isEditing) "Edit Diary Entry" else "New Diary Note",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        placeholder = { Text("Title (e.g. Aaj ka din, Exam review)", color = TextSecondary, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = contentInput,
                        onValueChange = { contentInput = it },
                        placeholder = { Text("Apne vichaar, events ya notes likhein...", color = TextSecondary, fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    // Mood Selector
                    Text("Mood / Emotion:", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("PRODUCTIVE" to "🎯 Productive", "HAPPY" to "😊 Happy", "CALM" to "🧘 Calm", "TIRED" to "😴 Tired", "IDEA" to "💡 Inspired").forEach { (key, label) ->
                            MoodPill(
                                label = label,
                                selected = moodInput == key,
                                onClick = { moodInput = key }
                            )
                        }
                    }

                    // Category Selector
                    Text("Category:", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("NOTE" to "📝 Note", "PERSONAL" to "💖 Personal", "IDEA" to "💡 Idea", "DAILY_SUMMARY" to "🌅 Recap").forEach { (cat, label) ->
                            MoodPill(
                                label = label,
                                selected = categoryInput == cat,
                                onClick = { categoryInput = cat }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalTitle = titleInput.trim().ifBlank {
                            "Note - " + SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date())
                        }
                        val finalContent = contentInput.trim()
                        if (finalContent.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                if (isEditing && entryToEdit != null) {
                                    unifiedMemory.updateDiaryEntry(
                                        id = entryToEdit!!.id,
                                        newTitle = finalTitle,
                                        newContent = finalContent,
                                        newMood = moodInput,
                                        newCategory = categoryInput
                                    )
                                } else {
                                    unifiedMemory.saveDiaryEntry(
                                        title = finalTitle,
                                        content = finalContent,
                                        mood = moodInput,
                                        category = categoryInput,
                                        isAutoGenerated = false
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    showAddDialog = false
                                    entryToEdit = null
                                    refreshEntries()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (isEditing) "Save Changes" else "Add Entry", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    entryToEdit = null
                }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // ── Delete Confirmation Dialog ───────────────────────────────────────────
    if (entryToDelete != null) {
        val entry = entryToDelete!!
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            containerColor = DarkSurface,
            title = { Text("Delete Entry?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Kya aap '${entry.title}' ko delete karna chahte hain?", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            unifiedMemory.deleteDiaryEntry(entry.id)
                            withContext(Dispatchers.Main) {
                                entryToDelete = null
                                refreshEntries()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun DiaryEntryCard(
    entry: DiaryEntry,
    onReadAloud: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (entry.isAutoGenerated) AccentBlue.copy(0.35f) else DarkBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Category, Mood, Date, Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val moodEmoji = when (entry.mood.uppercase(Locale.ROOT)) {
                        "PRODUCTIVE" -> "🎯"
                        "HAPPY" -> "😊"
                        "CALM" -> "🧘"
                        "TIRED" -> "😴"
                        "IDEA" -> "💡"
                        else -> "✨"
                    }
                    Text(moodEmoji, fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (entry.isAutoGenerated) GreenLive.copy(0.15f) else AccentBlue.copy(0.15f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (entry.isAutoGenerated) "Daily Recap" else entry.category,
                            color = if (entry.isAutoGenerated) GreenLive else AccentBlue,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        entry.dateString,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onReadAloud, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = "Read Aloud",
                            tint = AccentBlue,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color(0xFFEF5350).copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                entry.title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(4.dp))

            Text(
                entry.content,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun DiaryFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) AccentBlue else DarkSurface,
        border = BorderStroke(1.dp, if (selected) AccentBlue else DarkBorder)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun MoodPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) AccentPurple.copy(0.25f) else DarkSurfaceVariant,
        border = BorderStroke(1.dp, if (selected) AccentPurple else Color.Transparent)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
