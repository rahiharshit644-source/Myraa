package com.soltini.app.reach.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.reach.data.ReachHistoryStore
import com.soltini.app.reach.gateway.AgentReachGatewayImpl
import com.soltini.app.reach.model.*
import com.soltini.app.reach.planner.ResearchPlanner
import com.soltini.app.reach.security.ReachCredentialStore
import com.soltini.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternetResearchScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gateway = remember { AgentReachGatewayImpl.getInstance(context) }
    val credentialStore = remember { gateway.getCredentialStore() }
    val historyStore = remember { ReachHistoryStore.getInstance(context) }

    var capabilities by remember { mutableStateOf(gateway.getCapabilities()) }
    var doctorReport by remember { mutableStateOf<DiagnosticReport?>(null) }
    var showDoctorDialog by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf(ResearchMode.NORMAL) }
    var isResearching by remember { mutableStateOf(false) }
    var researchProgressText by remember { mutableStateOf("") }
    var researchResult by remember { mutableStateOf<ResearchResult?>(null) }
    var synthesizedAnswer by remember { mutableStateOf<String?>(null) }
    var expandedSourceId by remember { mutableStateOf<String?>(null) }

    var historyItems by remember { mutableStateOf(historyStore.getRecentHistory(15)) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    fun refreshHealth() {
        scope.launch {
            statusBanner = "Running Agent Reach Doctor..."
            val rep = gateway.getHealth(forceRefresh = true)
            doctorReport = rep
            capabilities = gateway.getCapabilities()
            statusBanner = "${rep.readyCapabilities}/${rep.totalCapabilities} capabilities ready"
        }
    }

    LaunchedEffect(Unit) {
        refreshHealth()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "MYRA INTERNET REACH",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            "Agent Reach Capability & Research Gateway",
                            fontSize = 11.sp,
                            color = AccentTeal
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshHealth() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentTeal)
                    }
                    IconButton(onClick = { showConfigDialog = true }) {
                        Icon(Icons.Default.Key, contentDescription = "Configure Credentials", tint = AccentBlue)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── ACTION BUTTONS BAR ──────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val rep = gateway.getHealth(forceRefresh = true)
                                doctorReport = rep
                                showDoctorDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Healing, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CHECK STATUS", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = { showConfigDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CONFIGURE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                statusBanner = "Testing search connection..."
                                try {
                                    val start = System.currentTimeMillis()
                                    val res = gateway.searchWeb("test connection", 2)
                                    val lat = System.currentTimeMillis() - start
                                    statusBanner = "Connection test OK: ${res.size} result(s) in ${lat}ms via ${gateway.getActiveBackend("web.search")}"
                                } catch (e: Exception) {
                                    statusBanner = "Test error: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentTeal)
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("TEST CONNECTION", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = { showClearConfirmDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RedError)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CLEAR KEYS", fontSize = 11.sp)
                    }
                }

                if (statusBanner != null) {
                    Text(
                        text = statusBanner!!,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // ── CAPABILITY STATUS DASHBOARD ─────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "INTERNET CAPABILITIES",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            val readyCount = capabilities.count { it.healthStatus == HealthStatus.READY }
                            Surface(
                                color = if (readyCount > 0) AccentTeal.copy(alpha = 0.2f) else RedError.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "$readyCount/${capabilities.size} READY",
                                    fontSize = 11.sp,
                                    color = if (readyCount > 0) AccentTeal else RedError,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Capability list
                        capabilities.forEach { cap ->
                            CapabilityRow(cap = cap)
                            HorizontalDivider(color = DarkBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        }
                    }
                }
            }

            // ── INTERACTIVE RESEARCH SANDBOX ────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(AccentTeal.copy(alpha = 0.4f)))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "LIVE RESEARCH SANDBOX",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentTeal
                        )
                        Text(
                            "Ask any question, share a URL, or request a deep dive",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search web, analyze URL, inspect GitHub repo, or summarize YouTube...", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = DarkBorder,
                                focusedContainerColor = DarkBackground,
                                unfocusedContainerColor = DarkBackground
                            ),
                            shape = RoundedCornerShape(12.dp),
                            maxLines = 3
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Mode Selector Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                ResearchMode.QUICK to "Quick (1-2 Sources)",
                                ResearchMode.NORMAL to "Normal (Multi-source)",
                                ResearchMode.DEEP to "Deep (Cross-platform)"
                            ).forEach { (mode, label) ->
                                val selected = selectedMode == mode
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedMode = mode },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentBlue.copy(alpha = 0.25f),
                                        selectedLabelColor = AccentBlue,
                                        containerColor = DarkBackground,
                                        labelColor = TextSecondary
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                if (searchQuery.isNotBlank() && !isResearching) {
                                    scope.launch {
                                        isResearching = true
                                        researchProgressText = "Planning research..."
                                        researchResult = null
                                        synthesizedAnswer = null

                                        try {
                                            val plan = ResearchPlanner.createPlan(searchQuery).copy(mode = selectedMode)
                                            val res = gateway.executeResearch(plan) { stage ->
                                                researchProgressText = stage
                                            }
                                            researchResult = res

                                            researchProgressText = "Synthesizing answer with Gemini..."
                                            val synth = com.soltini.app.reach.integration.MyraReachSynthesizer(
                                                context,
                                                com.soltini.app.settings.AppSettings(context)
                                            )
                                            val answer = synth.synthesizeResearch(searchQuery, res) { stage ->
                                                researchProgressText = stage
                                            }
                                            synthesizedAnswer = answer

                                            // Save to history
                                            historyStore.saveHistory(
                                                ResearchHistoryItem(
                                                    query = searchQuery,
                                                    mode = selectedMode,
                                                    summary = answer,
                                                    sourceCount = res.sources.size,
                                                    sourcesJson = org.json.JSONArray().apply {
                                                        res.sources.forEach { put(it.toJsonObject()) }
                                                    }.toString(),
                                                    durationMs = res.durationMs,
                                                    isSuccess = res.isSuccess
                                                )
                                            )
                                            historyItems = historyStore.getRecentHistory(15)
                                        } catch (e: Exception) {
                                            synthesizedAnswer = "Research failed: ${e.message}"
                                        } finally {
                                            isResearching = false
                                        }
                                    }
                                }
                            },
                            enabled = searchQuery.isNotBlank() && !isResearching,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isResearching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = DarkBackground,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(researchProgressText, fontSize = 12.sp, color = DarkBackground)
                            } else {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("EXECUTE RESEARCH", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Answer View
                        if (synthesizedAnswer != null) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = DarkBackground,
                                shape = RoundedCornerShape(12.dp),
                                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        "SYNTHESIZED ANSWER",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentTeal
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = synthesizedAnswer!!,
                                        fontSize = 13.sp,
                                        color = TextPrimary,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }

                        // Retrieved Sources List
                        if (researchResult != null && researchResult!!.sources.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Retrieved Sources (${researchResult!!.sources.size}) [Deduplicated: ${researchResult!!.deduplicatedCount}]:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            researchResult!!.sources.forEachIndexed { idx, src ->
                                val isExpanded = expandedSourceId == src.id
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { expandedSourceId = if (isExpanded) null else src.id },
                                    colors = CardDefaults.cardColors(containerColor = DarkBackground),
                                    shape = RoundedCornerShape(8.dp),
                                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                "[${idx + 1}] ${src.title}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = AccentBlue,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Surface(
                                                color = AccentPurple.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    src.platform.displayName,
                                                    fontSize = 9.sp,
                                                    color = AccentPurple,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            src.url,
                                            fontSize = 10.sp,
                                            color = TextSecondary,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                        if (isExpanded) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                src.fullContent ?: src.snippet,
                                                fontSize = 11.sp,
                                                color = TextPrimary,
                                                lineHeight = 15.sp
                                            )
                                            Text(
                                                "Backend: ${src.backendUsed} | Reliability: ${src.reliability.name}",
                                                fontSize = 9.sp,
                                                color = TextSecondary,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── RESEARCH HISTORY ────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "RESEARCH HISTORY",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            if (historyItems.isNotEmpty()) {
                                TextButton(onClick = {
                                    historyStore.clearHistory()
                                    historyItems = emptyList()
                                }) {
                                    Text("Clear All", fontSize = 11.sp, color = RedError)
                                }
                            }
                        }

                        if (historyItems.isEmpty()) {
                            Text(
                                "No previous research sessions recorded yet.",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            val sdf = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }
                            historyItems.forEach { item ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .clickable {
                                            searchQuery = item.query
                                            synthesizedAnswer = item.summary
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            item.query,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            sdf.format(Date(item.timestamp)),
                                            fontSize = 10.sp,
                                            color = TextSecondary
                                        )
                                    }
                                    Text(
                                        "${item.mode.name} • ${item.sourceCount} sources • ${item.durationMs}ms",
                                        fontSize = 10.sp,
                                        color = AccentTeal
                                    )
                                    Text(
                                        item.summary.take(100) + "...",
                                        fontSize = 11.sp,
                                        color = TextSecondary,
                                        maxLines = 2
                                    )
                                }
                                HorizontalDivider(color = DarkBorder.copy(alpha = 0.3f), thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }

    // ── DOCTOR DIAGNOSTIC REPORT DIALOG ────────────────────────────────
    if (showDoctorDialog && doctorReport != null) {
        AlertDialog(
            onDismissRequest = { showDoctorDialog = false },
            title = {
                Text(
                    "AGENT REACH DOCTOR",
                    fontWeight = FontWeight.Bold,
                    color = AccentPurple
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .background(DarkBackground, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    item {
                        Text(
                            text = doctorReport!!.toFormattedReport(),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            lineHeight = 16.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDoctorDialog = false }) {
                    Text("CLOSE", color = AccentBlue)
                }
            },
            containerColor = DarkSurface
        )
    }

    // ── CONFIGURE CREDENTIALS DIALOG ───────────────────────────────────
    if (showConfigDialog) {
        var exaKey by remember { mutableStateOf(credentialStore.getSecret(ReachCredentialStore.KEY_EXA_API_KEY).orEmpty()) }
        var ghToken by remember { mutableStateOf(credentialStore.getSecret(ReachCredentialStore.KEY_GITHUB_TOKEN).orEmpty()) }
        var twAuthToken by remember { mutableStateOf(credentialStore.getSecret(ReachCredentialStore.KEY_TWITTER_AUTH_TOKEN).orEmpty()) }
        var twCt0 by remember { mutableStateOf(credentialStore.getSecret(ReachCredentialStore.KEY_TWITTER_CT0).orEmpty()) }
        var bridgeUrl by remember { mutableStateOf(credentialStore.getCompanionBridgeUrl()) }

        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = { Text("Configure Reach Credentials", fontWeight = FontWeight.Bold, color = AccentBlue) },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text("Exa AI Search API Key (optional):", fontSize = 11.sp, color = TextSecondary)
                        OutlinedTextField(
                            value = exaKey,
                            onValueChange = { exaKey = it },
                            placeholder = { Text("exa_...", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Text("GitHub Personal Access Token (optional):", fontSize = 11.sp, color = TextSecondary)
                        OutlinedTextField(
                            value = ghToken,
                            onValueChange = { ghToken = it },
                            placeholder = { Text("ghp_...", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Text("Twitter/X auth_token cookie (optional):", fontSize = 11.sp, color = TextSecondary)
                        OutlinedTextField(
                            value = twAuthToken,
                            onValueChange = { twAuthToken = it },
                            placeholder = { Text("auth_token", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Text("Twitter/X ct0 cookie (optional):", fontSize = 11.sp, color = TextSecondary)
                        OutlinedTextField(
                            value = twCt0,
                            onValueChange = { twCt0 = it },
                            placeholder = { Text("ct0", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Text("Termux / Companion Bridge URL:", fontSize = 11.sp, color = TextSecondary)
                        OutlinedTextField(
                            value = bridgeUrl,
                            onValueChange = { bridgeUrl = it },
                            placeholder = { Text("http://127.0.0.1:8765", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (exaKey.isNotBlank()) credentialStore.saveSecret(ReachCredentialStore.KEY_EXA_API_KEY, exaKey)
                    else credentialStore.removeSecret(ReachCredentialStore.KEY_EXA_API_KEY)

                    if (ghToken.isNotBlank()) credentialStore.saveSecret(ReachCredentialStore.KEY_GITHUB_TOKEN, ghToken)
                    else credentialStore.removeSecret(ReachCredentialStore.KEY_GITHUB_TOKEN)

                    if (twAuthToken.isNotBlank()) credentialStore.saveSecret(ReachCredentialStore.KEY_TWITTER_AUTH_TOKEN, twAuthToken)
                    else credentialStore.removeSecret(ReachCredentialStore.KEY_TWITTER_AUTH_TOKEN)

                    if (twCt0.isNotBlank()) credentialStore.saveSecret(ReachCredentialStore.KEY_TWITTER_CT0, twCt0)
                    else credentialStore.removeSecret(ReachCredentialStore.KEY_TWITTER_CT0)

                    if (bridgeUrl.isNotBlank()) credentialStore.setCompanionBridgeUrl(bridgeUrl)

                    showConfigDialog = false
                    refreshHealth()
                }) {
                    Text("SAVE", color = AccentTeal)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) {
                    Text("CANCEL", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // ── CLEAR CREDENTIALS CONFIRMATION ────────────────────────────────
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear All Stored Credentials?", fontWeight = FontWeight.Bold, color = RedError) },
            text = { Text("This will remove all saved API keys and tokens from Android secure storage.", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    credentialStore.clearAllSecrets()
                    showClearConfirmDialog = false
                    refreshHealth()
                }) {
                    Text("CLEAR ALL", color = RedError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("CANCEL", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
private fun CapabilityRow(cap: ReachCapability) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cap.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                if (cap.tier == 0) {
                    Surface(
                        color = AccentTeal.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(start = 6.dp)
                    ) {
                        Text(
                            "Zero-config",
                            fontSize = 9.sp,
                            color = AccentTeal,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                cap.description,
                fontSize = 11.sp,
                color = TextSecondary,
                maxLines = 1
            )
            if (cap.activeBackend != null) {
                Text(
                    "Backend: ${cap.activeBackend}",
                    fontSize = 10.sp,
                    color = AccentBlue
                )
            }
        }

        // Status badge
        val badgeColor: Color
        val badgeText: String
        when (cap.healthStatus) {
            HealthStatus.READY -> {
                badgeColor = AccentTeal
                badgeText = "Ready"
            }
            HealthStatus.SETUP_REQUIRED -> {
                badgeColor = Color(0xFFFFB300)
                badgeText = "Setup"
            }
            HealthStatus.RATE_LIMITED -> {
                badgeColor = Color(0xFFFF9800)
                badgeText = "Limited"
            }
            HealthStatus.NETWORK_UNAVAILABLE -> {
                badgeColor = RedError
                badgeText = "Offline"
            }
            else -> {
                badgeColor = RedError
                badgeText = "Unavailable"
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                badgeText,
                fontSize = 11.sp,
                color = badgeColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
