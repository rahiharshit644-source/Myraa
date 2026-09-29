package com.soltini.app.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.memory2.FactAction
import com.soltini.app.memory2.FactResolution
import com.soltini.app.memory2.FactResolver
import com.soltini.app.memory2.MemoryCategory
import com.soltini.app.memory2.MemoryItem
import com.soltini.app.memory2.RetrievedMemoryContext
import com.soltini.app.settings.AppSettings
import com.soltini.app.ui.theme.*
import kotlinx.coroutines.launch

private val CardBg = Color(0xFF161928)
private val AmberWarn = Color(0xFFFFA726)

@Composable
fun ModularArchitectureScreen(
    viewModel: MainViewModel?,
    context: Context,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val memory2 = viewModel?.getMemory2Engine()
    val appSettings = viewModel?.getAppSettings()
    val pluginRegistry = viewModel?.getPluginRegistry()
    val orchestrator = viewModel?.getMyraOrchestrator()

    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Orchestrator", "Memory 2.0", "Plugin Registry")

    var longTermMemories by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    var sessionLogEntries by remember { mutableStateOf<List<com.soltini.app.memory2.Memory2Database.SessionLogEntry>>(emptyList()) }
    var experienceRecords by remember { mutableStateOf<List<com.soltini.app.memory2.Memory2Database.ExperienceRecord>>(emptyList()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshMemories() {
        if (memory2 != null) {
            longTermMemories = memory2.db.getAllLongTermMemories()
            experienceRecords = memory2.db.getAllExperiences()
            sessionLogEntries = memory2.db.getRecentSessionLogEntries(50)
        }
    }

    LaunchedEffect(selectedTab) {
        refreshMemories()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Hero Header
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(AccentBlue.copy(0.35f), AccentBlue.copy(0.05f))))
                .border(1.dp, AccentBlue.copy(0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Memory, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(36.dp))
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "MYRA Core Architecture",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Unified Orchestrator • Memory 2.0 • Plugin Registry",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
        )

        // Tab Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurfaceVariant)
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            tabTitles.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AccentBlue else Color.Transparent)
                        .clickable { selectedTab = index }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title,
                        color = if (isSelected) Color.White else TextSecondary,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        when (selectedTab) {
            0 -> OrchestratorTab(orchestrator, memory2, pluginRegistry)
            1 -> Memory2Tab(
                memory2 = memory2,
                appSettings = appSettings,
                longTermMemories = longTermMemories,
                sessionLogEntries = sessionLogEntries,
                experienceRecords = experienceRecords,
                onMemoryChanged = { refreshMemories() },
                onClearAll = {
                    memory2?.clearAllMemories()
                    refreshMemories()
                    statusMessage = "All memories cleared."
                }
            )
            2 -> PluginRegistryTab(pluginRegistry)
        }

        statusMessage?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(msg, color = GreenLive, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun OrchestratorTab(
    orchestrator: com.soltini.app.orchestrator.MyraOrchestrator?,
    memory2: com.soltini.app.memory2.Memory2Engine?,
    pluginRegistry: com.soltini.app.plugins.PluginRegistry?
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AccentBlue.copy(0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(GreenLive)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Master Decision Engine Active", color = GreenLive, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "MYRA Orchestrator acts as the central brain. It classifies user intent, queries Memory 2.0 for context, discovers capabilities from the Plugin Registry, executes steps, validates outputs, triggers fallbacks when needed, and updates persistent learning.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        // Execution Loop Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF2A2F4A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("8-Stage Intelligence Loop", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))

                val loopStages = listOf(
                    "1. UNDERSTAND" to "Classify conversational vs actionable vs multi-step plan",
                    "2. REMEMBER" to "Selectively retrieve relevant Working, Session & Long-term context",
                    "3. PLAN" to "Decompose goal into sequential and conditional task steps",
                    "4. DISCOVER" to "Resolve capable plugins & tools from dynamic Registry",
                    "5. EXECUTE" to "Run plugin actions safely with structured parameters",
                    "6. VERIFY & FALLBACK" to "Inspect output; trigger alternative plugin if failed",
                    "7. LEARN & EVALUATE" to "Save durable preferences & learned tool sequences",
                    "8. RESPOND" to "Synthesize warm, concise Hinglish voice/text response"
                )

                loopStages.forEach { (stage, desc) ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("•", color = AccentBlue, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                        Column {
                            Text(stage, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(desc, color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Active State Summary
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF2A2F4A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Current Working State", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))

                val activeGoal = memory2?.working?.activeGoal ?: "Idle (Listening for user prompt)"
                val currentStep = (memory2?.working?.activeStepIndex ?: 0) + 1
                val workingItems = memory2?.working?.getActiveWorkingContext() ?: emptyList()

                Text("Active Task: $activeGoal", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (memory2?.working?.activeGoal != null) {
                    Text("Current Step: #$currentStep", color = TextSecondary, fontSize = 12.sp)
                }
                if (workingItems.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Scratchpad Variables:", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    workingItems.forEach { item ->
                        Text("  ${item.key}: ${item.content}", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Memory2Tab(
    memory2: com.soltini.app.memory2.Memory2Engine?,
    appSettings: AppSettings?,
    longTermMemories: List<MemoryItem>,
    sessionLogEntries: List<com.soltini.app.memory2.Memory2Database.SessionLogEntry>,
    experienceRecords: List<com.soltini.app.memory2.Memory2Database.ExperienceRecord>,
    onMemoryChanged: () -> Unit,
    onClearAll: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var expandedFactsList by remember { mutableStateOf(true) }
    var expandedTiers by remember { mutableStateOf(false) }
    var expandedExperience by remember { mutableStateOf(false) }

    // Conflict Resolution Lab state
    var labStatementInput by remember { mutableStateOf("") }
    var labResolutionResult by remember { mutableStateOf<FactResolution?>(null) }
    var isLabResolving by remember { mutableStateOf(false) }

    // Selective Retrieval Tester state
    var searchTestQuery by remember { mutableStateOf("") }
    var searchTestResults by remember { mutableStateOf<RetrievedMemoryContext?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // ── 1. Memory Engine Metrics Card ───────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AccentBlue.copy(0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(AccentBlue.copy(0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Unified Memory Engine", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Local SQLite • Offline dedup • No parallel stores", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricBox("Active Facts", "${longTermMemories.size}", AccentBlue, Modifier.weight(1f))
                    MetricBox("Past Conversations", "${sessionLogEntries.size}", GreenLive, Modifier.weight(1f))
                    MetricBox("Experience Patterns", "${experienceRecords.size}", Color(0xFF00BCD4), Modifier.weight(1f))
                }
            }
        }

        // ── 2. Conflict Resolution & Deduplication Lab ───────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF2A2F4A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Conflict & Deduplication Lab", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Test the decision engine (ADD, UPDATE outdated memories, DELETE contradictions, NOOP duplicates) — runs offline, no network call:",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = labStatementInput,
                    onValueChange = { labStatementInput = it },
                    placeholder = { Text("e.g. \"I moved to Bangalore\" or \"I like black coffee without sugar\"", color = TextSecondary, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Color(0xFF2A2F4A),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (labStatementInput.isNotBlank() && memory2 != null) {
                                isLabResolving = true
                                coroutineScope.launch {
                                    val existing = memory2.db.getAllLongTermMemories()
                                    val res = FactResolver.resolve(labStatementInput.trim(), existing)
                                    labResolutionResult = res
                                    memory2.saveLongTerm(key = "lab_test", fact = labStatementInput.trim())
                                    isLabResolving = false
                                    onMemoryChanged()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isLabResolving && labStatementInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isLabResolving) {
                            Text("Resolving...", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Resolve", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            labStatementInput = "I like black coffee without sugar"
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = BorderStroke(1.dp, Color(0xFF2A2F4A)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Sample", fontSize = 11.sp)
                    }
                }

                labResolutionResult?.let { res ->
                    Spacer(Modifier.height(12.dp))
                    ResolutionCard(res)
                }
            }
        }

        // ── 3. Selective Context Retrieval Simulator ─────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF2A2F4A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Selective Query Retrieval", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Simulate how MYRA filters context. Only relevant memories are returned; irrelevant ones are omitted to avoid context pollution.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = searchTestQuery,
                    onValueChange = { searchTestQuery = it },
                    placeholder = { Text("e.g. \"Where does Boss live?\" or \"What does Boss drink?\"", color = TextSecondary, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPurple,
                        unfocusedBorderColor = Color(0xFF2A2F4A),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        if (searchTestQuery.isNotBlank() && memory2 != null) {
                            isSearching = true
                            coroutineScope.launch {
                                searchTestResults = memory2.retrieveContext(searchTestQuery.trim(), maxItems = 5)
                                isSearching = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSearching && searchTestQuery.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isSearching) "Searching..." else "Test Selective Retrieval", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                searchTestResults?.let { ctx ->
                    if (ctx.relevantItems.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Injected Context Block (${ctx.relevantItems.size} matches):", color = GreenLive, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        ctx.relevantItems.forEach { mem ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DarkSurfaceVariant)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(AccentPurple.copy(0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        mem.type.name,
                                        color = AccentPurple,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(mem.content, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    } else {
                        Spacer(Modifier.height(10.dp))
                        Text("No relevant matches found for this query.", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }

        // ── 4. Active Memories List ─────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF2A2F4A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Long-Term Memory (${longTermMemories.size})", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    IconButton(onClick = { expandedFactsList = !expandedFactsList }) {
                        Icon(
                            if (expandedFactsList) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = TextSecondary
                        )
                    }
                }

                if (longTermMemories.isEmpty()) {
                    Text("No memories saved yet. Teach MYRA above or talk naturally.", color = TextSecondary, fontSize = 12.sp)
                } else {
                    AnimatedVisibility(visible = expandedFactsList) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            longTermMemories.forEach { mem ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkSurfaceVariant)
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(AccentBlue.copy(0.18f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    mem.type.name,
                                                    color = AccentBlue,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "Importance: ${(mem.importance * 100).toInt()}% • Accessed: ${mem.accessCount}x",
                                                color = TextSecondary,
                                                fontSize = 10.sp
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(mem.content, color = TextPrimary, fontSize = 12.sp)
                                    }

                                    IconButton(
                                        onClick = {
                                            memory2?.deleteMemory(mem.id)
                                            onMemoryChanged()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Delete", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                if (longTermMemories.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onClearAll,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RedError),
                        border = BorderStroke(1.dp, RedError.copy(0.4f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear All Memories", fontSize = 12.sp)
                    }
                }
            }
        }

        // ── 5. 5 Memory Tiers Overview Card ──────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF2A2F4A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("5 Structured Memory Tiers", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    IconButton(onClick = { expandedTiers = !expandedTiers }) {
                        Icon(if (expandedTiers) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = TextSecondary)
                    }
                }

                AnimatedVisibility(visible = expandedTiers) {
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        TierRow("1. Working Memory", "Transient scratchpad variables & active task steps", GreenLive)
                        TierRow("2. Session Log", "${sessionLogEntries.size} persisted, timestamped conversation topics — survives restarts", AccentBlue)
                        TierRow("3. Long-Term Memory", "${longTermMemories.size} durable user facts with offline duplicate prevention", AccentPurple)
                        TierRow("4. Knowledge Memory", "Document facts, user notes, persistent references", Color(0xFFFFA726))
                        TierRow("5. Skill / Experience", "${experienceRecords.size} learned tool execution patterns & success rates", Color(0xFF00BCD4))
                    }
                }
            }
        }

        // ── 6. Learned Experiences Card ──────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF2A2F4A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Learned Skill & Experience Patterns (${experienceRecords.size})", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    IconButton(onClick = { expandedExperience = !expandedExperience }) {
                        Icon(
                            if (expandedExperience) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = TextSecondary
                        )
                    }
                }

                if (experienceRecords.isEmpty()) {
                    Text("MYRA learns execution patterns automatically as you perform voice commands and phone tasks.", color = TextSecondary, fontSize = 12.sp)
                } else {
                    AnimatedVisibility(visible = expandedExperience) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            experienceRecords.forEach { rec ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkSurfaceVariant)
                                        .padding(10.dp)
                                ) {
                                    Text("Task: ${rec.taskType}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text("Proven Tool Flow: ${rec.toolSequence.joinToString(" ➔ ")}", color = AccentBlue, fontSize = 11.sp)
                                    Text("Success Rate: ${(rec.successRate * 100).toInt()}% (${rec.successCount}/${rec.totalCount})", color = GreenLive, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricBox(label: String, value: String, accentColor: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceVariant)
            .border(1.dp, accentColor.copy(0.3f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = accentColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            Text(label, color = TextSecondary, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ResolutionCard(res: FactResolution) {
    val (color, badge) = when (res.action) {
        FactAction.ADD -> Pair(GreenLive, "ADD: NEW FACT STORED")
        FactAction.UPDATE -> Pair(AccentBlue, "UPDATE: OUTDATED FACT REPLACED")
        FactAction.DELETE -> Pair(RedError, "DELETE: FACT CONTRADICTED")
        FactAction.NOOP -> Pair(AmberWarn, "NOOP: DUPLICATE PREVENTED")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(0.12f))
            .border(1.dp, color.copy(0.4f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(badge, color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(res.fact, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(res.reason, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun PluginRegistryTab(registry: com.soltini.app.plugins.PluginRegistry?) {
    val plugins = registry?.getAllPlugins() ?: emptyList()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Summary Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AccentBlue.copy(0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Dynamic Capability System", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "The Plugin Registry decouples capabilities from the brain. MYRA queries this registry dynamically without hardcoded switches. Includes ${plugins.size} modular tools & agents + 1,756+ public APIs across 51 categories.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        // Plugins List
        Text("Active Registered Capabilities (${plugins.size})", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)

        plugins.forEach { plugin ->
            val meta = plugin.metadata
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF2A2F4A))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(meta.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentBlue.copy(0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(meta.category.name, color = AccentBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(meta.description, color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ID: ${meta.id}", color = Color(0xFF8E95AF), fontSize = 10.sp)
                        if (meta.fallbackPluginId != null) {
                            Text("Fallback: ${meta.fallbackPluginId}", color = Color(0xFFFFA726), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TierRow(title: String, desc: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(desc, color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
