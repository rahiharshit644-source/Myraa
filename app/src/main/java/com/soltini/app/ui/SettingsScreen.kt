package com.soltini.app.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.soltini.app.security.MemoryBackupManager
import com.soltini.app.security.VoiceBiometricsManager
import com.soltini.app.security.VoiceUnlockActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import com.soltini.app.memory2.MemoryItem
import com.soltini.app.memory2.MemoryType
import com.soltini.app.notifications.NotificationRepository
import com.soltini.app.notifications.SoltiniNotificationListener
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.soltini.app.agent.SoltiniAccessibilityService
import com.soltini.app.settings.AppSettings
import com.soltini.app.ui.theme.AccentBlue
import com.soltini.app.ui.theme.DarkBackground
import com.soltini.app.ui.theme.DarkBorder
import com.soltini.app.ui.theme.DarkSurface
import com.soltini.app.ui.theme.DarkSurfaceVariant
import com.soltini.app.ui.theme.GreenLive
import com.soltini.app.ui.theme.RedError
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary

private val AmberWarning = Color(0xFFFFA726)
private val Purple = Color(0xFF9C27B0)
private val MiuiOrange = Color(0xFFFF6D00)

enum class SettingsRoute { MAIN, PERMISSIONS, SCREEN_COMPANION, API, MEMORY, NOTIFICATIONS, ARCHITECTURE, STORAGE_RAG, SCHEDULED_TASKS }

@Composable
fun SettingsMenuCard(title: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = TextSecondary)
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel? = null,
    onOpenHomeAutomation: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    var currentRoute by remember { mutableStateOf(SettingsRoute.MAIN) }

    BackHandler(enabled = currentRoute != SettingsRoute.MAIN) {
        currentRoute = SettingsRoute.MAIN
    }

    // --- Permission states ---
    var hasMic by remember { mutableStateOf(SettingsPermChecks.checkMic(context)) }
    var hasOverlay by remember { mutableStateOf(SettingsPermChecks.checkOverlay(context)) }
    var hasBattery by remember { mutableStateOf(SettingsPermChecks.checkBattery(context)) }
    var hasAccessibility by remember { mutableStateOf(SettingsPermChecks.checkAccessibility(context)) }
    var hasAssistantRole by remember { mutableStateOf(SettingsPermChecks.checkAssistantRole(context)) }
    var hasNotificationAccess by remember { mutableStateOf(SettingsPermChecks.checkNotificationAccess(context)) }
    var hasPhoneControl by remember { mutableStateOf(SettingsPermChecks.checkPhoneCallControl(context)) }
    var hasRestrictedSettings by remember { mutableStateOf(SettingsPermChecks.checkRestrictedSettings(context)) }
    val requiresAutoStart by remember { mutableStateOf(SettingsPermChecks.checkRequiresAutoStart(context)) }
    var autoStartRequested by remember {
        mutableStateOf(
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getBoolean("autostart_requested", false)
        )
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }

    val phonePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPhoneControl = SettingsPermChecks.checkPhoneCallControl(context)
        if (hasPhoneControl) {
            com.soltini.app.telephony.CallNotificationManager.getInstance(context).startListening()
        }
    }

    val roleLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            hasAssistantRole = SettingsPermChecks.checkAssistantRole(context)
        }
    } else null

    // Refresh all states when user comes back from system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMic = SettingsPermChecks.checkMic(context)
                hasOverlay = SettingsPermChecks.checkOverlay(context)
                hasBattery = SettingsPermChecks.checkBattery(context)
                hasAccessibility = SettingsPermChecks.checkAccessibility(context)
                hasAssistantRole = SettingsPermChecks.checkAssistantRole(context)
                hasNotificationAccess = SettingsPermChecks.checkNotificationAccess(context)
                hasPhoneControl = SettingsPermChecks.checkPhoneCallControl(context)
                if (hasNotificationAccess) {
                    SoltiniNotificationListener.rebindIfNeeded(context)
                }
                hasRestrictedSettings = SettingsPermChecks.checkRestrictedSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = DarkBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top Bar (Back / Done) ──────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.clickable { 
                    if (currentRoute == SettingsRoute.MAIN) onBack() else currentRoute = SettingsRoute.MAIN
                }.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = null, tint = AccentBlue)
                        Spacer(Modifier.width(4.dp))
                        Text(if (currentRoute == SettingsRoute.MAIN) "Done" else "Back", color = AccentBlue, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (currentRoute == SettingsRoute.MAIN) {
                SettingsHeader()
                Spacer(modifier = Modifier.height(24.dp))
            }

            when (currentRoute) {
                SettingsRoute.MAIN -> {
                    SettingsMenuCard("Live Screen Companion", Icons.Default.Visibility) { currentRoute = SettingsRoute.SCREEN_COMPANION }
                    Spacer(Modifier.height(16.dp))
                    SettingsMenuCard("Permissions & Agent", Icons.Default.Security) { currentRoute = SettingsRoute.PERMISSIONS }
                    Spacer(Modifier.height(16.dp))
                    SettingsMenuCard("Notifications & Direct Reply", Icons.Default.Notifications) { currentRoute = SettingsRoute.NOTIFICATIONS }
                    Spacer(Modifier.height(16.dp))
                    SettingsMenuCard("API & Persona", Icons.Default.Person) { currentRoute = SettingsRoute.API }
                    Spacer(Modifier.height(16.dp))
                    SettingsMenuCard("Memory System", Icons.Default.Storage) { currentRoute = SettingsRoute.MEMORY }
                    Spacer(Modifier.height(16.dp))
                    SettingsMenuCard("Storage & RAG Knowledge", Icons.Default.FolderOpen) { currentRoute = SettingsRoute.STORAGE_RAG }
                    Spacer(Modifier.height(16.dp))
                    SettingsMenuCard("Modular Architecture & Registry", Icons.Default.Memory) { currentRoute = SettingsRoute.ARCHITECTURE }
                    Spacer(Modifier.height(16.dp))
                    SettingsMenuCard("Scheduled Tasks & Experience", Icons.Default.Schedule) { currentRoute = SettingsRoute.SCHEDULED_TASKS }
                    Spacer(Modifier.height(16.dp))
                    SettingsMenuCard("Home Automation (ESP32)", Icons.Default.ElectricBolt) { onOpenHomeAutomation() }
                }
                SettingsRoute.SCHEDULED_TASKS -> {
                    ScheduledTasksScreen(
                        onBack = { currentRoute = SettingsRoute.MAIN }
                    )
                }
                SettingsRoute.STORAGE_RAG -> {
                    StorageRagSettingsSection(
                        context = context,
                        appSettings = viewModel?.getAppSettings()
                    )
                }
                SettingsRoute.ARCHITECTURE -> {
                    ModularArchitectureScreen(
                        viewModel = viewModel,
                        context = context
                    )
                }
                SettingsRoute.SCREEN_COMPANION -> {
                    ScreenCompanionSection(context = context, appSettings = viewModel?.getAppSettings())
                }
                SettingsRoute.NOTIFICATIONS -> {
                    NotificationsSettingsSection(context = context)
                }
                SettingsRoute.API -> {
                    AiConfigSection(
                        appSettings = viewModel?.getAppSettings(),
                        onSaveAndReconnect = { viewModel?.reconnect() }
                    )
                }
                SettingsRoute.MEMORY -> {
                    MemorySection(
                        memory2Engine = viewModel?.getMemory2Engine(),
                        appSettings = viewModel?.getAppSettings()
                    )
                }
                SettingsRoute.PERMISSIONS -> {

            // ── Section: Required for Basic Function ────────────────────────
            SectionLabel("Required Permissions")
            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                icon = Icons.Default.Mic,
                title = "Microphone",
                description = "Capture your voice for real-time AI conversation.",
                isGranted = hasMic,
                tier = PermTier.REQUIRED,
                onGrant = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            )
            Spacer(modifier = Modifier.height(10.dp))
            PermissionCard(
                icon = Icons.Default.Layers,
                title = "Display Over Other Apps",
                description = "Show the Myra floating orb on top of any screen.",
                isGranted = hasOverlay,
                tier = PermTier.REQUIRED,
                onGrant = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: Agentic (AI Agent Capabilities) ─────────────────────
            SectionLabel("AI Agent Capabilities")
            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                icon = Icons.Default.Assistant,
                title = "Default Device Assistant",
                description = "Replace Google Assistant / Gemini. Long-press home to trigger Myra — exactly like Gemini.",
                isGranted = hasAssistantRole,
                tier = PermTier.AGENT,
                onGrant = {
                    // Layered fallback to open the correct assistant settings page.
                    // isRoleAvailable() returns false for sideloaded APKs on many devices,
                    // so we try every known method in order of specificity.
                    var launched = false

                    // Tier 1: RoleManager intent (Android 10+, works when role is available)
                    if (!launched && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
                            val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                            roleLauncher?.launch(roleIntent)
                            launched = true
                        } catch (_: Exception) {}
                    }

                    // Tier 2: Deep-link to the Voice Input / Assistant sub-page
                    if (!launched) {
                        try {
                            context.startActivity(
                                Intent("com.android.settings.action.MANAGE_VOICE_INPUT_SETTINGS")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            launched = true
                        } catch (_: Exception) {}
                    }

                    // Tier 3: ACTION_VOICE_INPUT_SETTINGS (works on AOSP)
                    if (!launched) {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            launched = true
                        } catch (_: Exception) {}
                    }

                    // Tier 4: Last resort — generic default apps page
                    if (!launched) {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {}
                    }
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
            PermissionCard(
                icon = Icons.Default.Accessibility,
                title = "Accessibility Service",
                description = "Enables locking the device screen by voice. Myra only uses this to perform lock/unlock actions.",
                isGranted = hasAccessibility,
                tier = PermTier.AGENT,
                onGrant = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )

            Spacer(modifier = Modifier.height(10.dp))
            PermissionCard(
                icon = Icons.Default.Notifications,
                title = "Notification Access",
                description = "Enables Myra to read incoming messages and notifications on-demand and reply directly.",
                isGranted = hasNotificationAccess,
                tier = PermTier.AGENT,
                onGrant = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (_: Exception) {}
                }
            )

            Spacer(modifier = Modifier.height(10.dp))
            PermissionCard(
                icon = Icons.Default.Call,
                title = "Phone & Call Control",
                description = "Enables Myra to announce incoming callers aloud ('Boss, Rahul ka call aa raha hai') and answer or reject calls hands-free by voice.",
                isGranted = hasPhoneControl,
                tier = PermTier.AGENT,
                onGrant = {
                    phonePermLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.READ_CALL_LOG,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.ANSWER_PHONE_CALLS,
                            Manifest.permission.CALL_PHONE
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))
            VoiceProfilesSection(context = context)

            Spacer(modifier = Modifier.height(12.dp))
            BankingProtectionCard(context = context)

            Spacer(modifier = Modifier.height(12.dp))
            BusyModeCard(context = context)

            // Restricted settings warning for Android 13+ sideloaded APKs
            if (!hasRestrictedSettings && !hasAccessibility) {
                Spacer(modifier = Modifier.height(10.dp))
                RestrictedSettingsCard()
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: Background & System ────────────────────────────────
            SectionLabel("Background & System")
            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                icon = Icons.Default.BatteryAlert,
                title = "Ignore Battery Optimization",
                description = "Prevents Android from killing the assistant in the background.",
                isGranted = hasBattery,
                tier = PermTier.RECOMMENDED,
                onGrant = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )

            if (requiresAutoStart) {
                Spacer(modifier = Modifier.height(10.dp))
                PermissionCard(
                    icon = Icons.Default.PowerSettingsNew,
                    title = "Auto-Start (${Build.MANUFACTURER})",
                    description = "Allow Myra to automatically start in the background on your device.",
                    isGranted = autoStartRequested,
                    tier = PermTier.RECOMMENDED,
                    onGrant = {
                        SettingsPermChecks.launchAutoStart(context)
                        autoStartRequested = true
                        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("autostart_requested", true).apply()
                    }
                )
            }

            val isMiuiDevice = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                Build.MANUFACTURER.equals("POCO", ignoreCase = true) ||
                Build.MANUFACTURER.equals("Redmi", ignoreCase = true)

            if (isMiuiDevice) {
                Spacer(modifier = Modifier.height(10.dp))
                // MIUI-specific battery "No restrictions" deeplink card
                PermissionCard(
                    icon = Icons.Default.BatteryAlert,
                    title = "MIUI Battery — No Restrictions",
                    description = "Android battery optimization often isn't enough on MIUI. Set Battery saver to \"No restrictions\" in your app's settings.",
                    isGranted = false, // cannot be programmatically checked; always shows action
                    tier = PermTier.RECOMMENDED,
                    onGrant = {
                        // Try MIUI PowerKeeper first, fall back to App Details
                        val miuiIntent = android.content.Intent().apply {
                            component = android.content.ComponentName(
                                "com.miui.powerkeeper",
                                "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity"
                            )
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val resolved = context.packageManager.resolveActivity(miuiIntent, 0)
                        if (resolved != null) {
                            try { context.startActivity(miuiIntent) } catch (_: Exception) {}
                        } else {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                XiaomiActionsCard(context = context)
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Summary bar ─────────────────────────────────────────────────
            val totalRequired = 2
            val grantedRequired = listOf(hasMic, hasOverlay).count { it }
            val totalAgent = 3
            val grantedAgent = listOf(hasAssistantRole, hasAccessibility, hasNotificationAccess).count { it }

            PermissionSummaryBar(
                grantedRequired = grantedRequired,
                totalRequired = totalRequired,
                grantedAgent = grantedAgent,
                totalAgent = totalAgent
            )

            Spacer(modifier = Modifier.height(24.dp))
                } // End of PERMISSIONS route
            } // End of when(currentRoute)
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────
@Composable
private fun SettingsHeader() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "scale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(listOf(AccentBlue.copy(0.4f), AccentBlue.copy(0.05f)))
                )
                .border(1.dp, AccentBlue.copy(0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Settings, null, tint = AccentBlue, modifier = Modifier.size(44.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Permissions & Capabilities",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Only Microphone is required to start. Grant Agent capabilities\nto unlock voice-controlled device actions.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

// ── Section Label ─────────────────────────────────────────────────────────────
@Composable
private fun SectionLabel(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(3.dp).height(18.dp).background(AccentBlue, RoundedCornerShape(2.dp)))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

// ── Permission Tiers ─────────────────────────────────────────────────────────
enum class PermTier { REQUIRED, AGENT, RECOMMENDED }

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    tier: PermTier,
    onGrant: () -> Unit
) {
    val tierColor = when (tier) {
        PermTier.REQUIRED -> RedError
        PermTier.AGENT -> Purple
        PermTier.RECOMMENDED -> AmberWarning
    }
    val tierLabel = when (tier) {
        PermTier.REQUIRED -> "Required"
        PermTier.AGENT -> "Agent"
        PermTier.RECOMMENDED -> "Recommended"
    }

    val borderColor by animateColorAsState(
        if (isGranted) GreenLive.copy(alpha = 0.5f) else DarkBorder,
        label = "border"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isGranted) GreenLive.copy(alpha = 0.12f)
                        else tierColor.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else icon,
                    contentDescription = null,
                    tint = if (isGranted) GreenLive else tierColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (!isGranted) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(tierColor.copy(alpha = 0.18f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                tierLabel,
                                color = tierColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    description, color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp
                )
            }

            if (!isGranted) {
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tierColor.copy(alpha = 0.15f),
                        contentColor = tierColor
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Grant", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Restricted Settings Warning ───────────────────────────────────────────────
@Composable
private fun RestrictedSettingsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AmberWarning.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarning.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, tint = AmberWarning, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    "Enable Restricted Settings",
                    color = AmberWarning,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "On Android 13+, sideloaded apps need extra permission to access Accessibility.\n\n" +
                            "1. Go to Settings → Apps → Soltini\n" +
                            "2. Tap the three-dot menu (⋮) at the top right\n" +
                            "3. Tap \"Allow restricted settings\"\n" +
                            "4. Come back and tap Grant on Accessibility Service above.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ── Xiaomi Actions Card ───────────────────────────────────────────────────────
@Composable
private fun XiaomiActionsCard(context: android.content.Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MiuiOrange.copy(alpha = 0.07f)),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MiuiOrange.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = MiuiOrange, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "MIUI Background Kill Protection",
                    color = MiuiOrange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Your phone aggressively kills background apps. Use the buttons below to open each setting:",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Autostart button
            MiuiActionRow(
                label = "1. Open Autostart Settings",
                hint = "Security → Permissions → Autostart → toggle ON",
                buttonLabel = "Open",
                color = MiuiOrange
            ) {
                val intent = android.content.Intent().apply {
                    component = android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val resolved = context.packageManager.resolveActivity(intent, 0)
                if (resolved != null) {
                    try { context.startActivity(intent) } catch (_: Exception) {} 
                } else {
                    try {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}")
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {}
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Lock in recents — no deeplink, just instructions
            MiuiActionRow(
                label = "2. Lock in Recent Apps",
                hint = "Recents → Long-press Myra card → tap 🔒 lock icon",
                buttonLabel = null,
                color = AccentBlue
            ) {}

            Spacer(modifier = Modifier.height(10.dp))

            // Re-trigger full onboarding guide
            OutlinedButton(
                onClick = {
                    context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("miui_onboarding_complete", false).apply()
                    // Prompt user to relaunch
                    android.widget.Toast.makeText(
                        context,
                        "Relaunch Myra to see the full setup guide",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MiuiOrange
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MiuiOrange.copy(0.5f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Re-open Xiaomi Setup Guide", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MiuiActionRow(
    label: String,
    hint: String,
    buttonLabel: String?,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
        if (buttonLabel != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = color.copy(alpha = 0.18f),
                    contentColor = color
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(34.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
            ) {
                Text(buttonLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Summary Bar ───────────────────────────────────────────────────────────────
@Composable
private fun PermissionSummaryBar(
    grantedRequired: Int,
    totalRequired: Int,
    grantedAgent: Int,
    totalAgent: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryItem(
                label = "Core",
                granted = grantedRequired,
                total = totalRequired,
                color = if (grantedRequired == totalRequired) GreenLive else RedError
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(40.dp)
                    .background(DarkBorder)
            )
            SummaryItem(
                label = "Agent",
                granted = grantedAgent,
                total = totalAgent,
                color = if (grantedAgent == totalAgent) GreenLive else Purple
            )
        }
    }
}

@Composable
private fun SummaryItem(label: String, granted: Int, total: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$granted / $total",
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        )
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

// ── Permission Check Helpers ──────────────────────────────────────────────────
object SettingsPermChecks {

    fun checkPhoneCallControl(context: Context): Boolean {
        val phoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val answerCalls = ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        return phoneState && contacts && answerCalls
    }

    fun checkNotificationAccess(context: Context): Boolean {
        return androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    fun checkMic(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    fun checkOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun checkBattery(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true
    }

    fun checkAccessibility(context: Context): Boolean {
        if (SoltiniAccessibilityService.isActive()) return true
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(context.packageName, ignoreCase = true)
    }

    fun checkAssistantRole(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
            roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else {
            // Fallback: check the secure setting
            val setting = Settings.Secure.getString(
                context.contentResolver,
                "assistant"
            )
            setting?.contains(context.packageName, ignoreCase = true) == true
        }
    }

    /**
     * Heuristic check for Android 13+ Restricted Settings.
     * If Accessibility is not active AND we're on API 33+, it's likely blocked.
     */
    fun checkRestrictedSettings(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // If accessibility service IS active, restricted settings must already be allowed
            checkAccessibility(context)
        } else {
            true // Not relevant below Android 13
        }
    }

    fun checkRequiresAutoStart(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = Intent()
        when (manufacturer) {
            "xiaomi" -> intent.component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            "oppo" -> intent.component = ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
            "vivo" -> intent.component = ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            "huawei", "honor" -> intent.component = ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
            else -> return false
        }
        return context.packageManager.resolveActivity(intent, 0) != null
    }

    fun launchAutoStart(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = Intent()
        when (manufacturer) {
            "xiaomi" -> intent.component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            "oppo" -> intent.component = ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
            "vivo" -> intent.component = ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            "huawei", "honor" -> intent.component = ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AI Configuration Section
// ─────────────────────────────────────────────────────────────────────────────

private val CardBg      = Color(0xFF141828)
private val AccentPurple = Color(0xFF7C5CBF)
private val ChipSelected = Color(0xFF7C5CBF)
private val ChipUnselected = Color(0xFF232740)

@Composable
fun AiConfigSection(
    appSettings: AppSettings?,
    onSaveAndReconnect: () -> Unit
) {
    // Local draft state — only written to AppSettings on Save
    val devName     = appSettings?.developerName ?: AppSettings.DEFAULT_DEVELOPER_NAME // permanent, not editable
    var username    by remember { mutableStateOf(appSettings?.ownerUsername ?: "") }
    var appName     by remember { mutableStateOf(appSettings?.appDisplayName ?: AppSettings.DEFAULT_APP_NAME) }
    var apiKey      by remember { mutableStateOf(appSettings?.geminiApiKey ?: "") }
    var browserUseServerUrl by remember { mutableStateOf(appSettings?.browserUseServerUrl ?: "") }
    var persona     by remember { mutableStateOf(appSettings?.aiPersona ?: AppSettings.DEFAULT_PERSONA) }
    var voice       by remember { mutableStateOf(appSettings?.voiceName ?: AppSettings.DEFAULT_VOICE) }
    var isDataSaver by remember { mutableStateOf(appSettings?.isDataSaverEnabled ?: true) }
    var vadSens     by remember { mutableStateOf(appSettings?.vadSensitivity ?: AppSettings.SENSITIVITY_BALANCED) }
    var micGain     by remember { mutableStateOf(appSettings?.micGain ?: 1.5f) }
    var autoSleep   by remember { mutableStateOf(appSettings?.autoSleepMinutes ?: 3) }
    var isScreenRecMode by remember { mutableStateOf(appSettings?.isScreenRecordingModeEnabled ?: false) }
    var showKey     by remember { mutableStateOf(false) }
    var saved       by remember { mutableStateOf(false) }

    SectionLabel("⚙️  AI Configuration")
    Spacer(Modifier.height(12.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2F4A))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {

            // ── Developer & Creator Identity (permanent — not editable) ──────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null,
                    tint = AccentPurple, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Developer / Creator Name", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Kept strictly confidential. When asked \"Kisne banaya?\", \"Developer kaun hai?\" or \"Who made this app?\", the AI will reveal this name. Permanent — cannot be changed from this screen.",
                color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF1E2132),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2F4A))
            ) {
                Text(
                    devName,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Username ──────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null,
                    tint = AccentPurple, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Username", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "What the AI calls you during conversation.",
                color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    saved = false
                    persona = AppSettings.generatePersona(devName, appName, it.ifBlank { "Boss" })
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Your name", color = TextSecondary, fontSize = 13.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPurple,
                    unfocusedBorderColor = Color(0xFF2A2F4A),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentPurple
                ),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(Modifier.height(16.dp))

            // ── Assistant / App Name ─────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Assistant, contentDescription = null,
                    tint = AccentPurple, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Assistant / App Name", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "The name of your personal AI companion.",
                color = TextSecondary, fontSize = 11.sp
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = appName,
                onValueChange = {
                    appName = it
                    saved = false
                    persona = AppSettings.generatePersona(devName, it, username.ifBlank { "Boss" })
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. Myra", color = TextSecondary, fontSize = 13.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPurple,
                    unfocusedBorderColor = Color(0xFF2A2F4A),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentPurple
                ),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(Modifier.height(18.dp))

            // ── Browser-Use Bridge Server (real desktop browser control) ─────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Language, contentDescription = null,
                    tint = AccentPurple, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Browser-Use Server URL", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Optional. Address of your soltini_bridge_server.py running on a PC/VPS with Browser-Use + Playwright installed (e.g. http://192.168.1.23:8787). Lets the AI control a REAL desktop browser, not the phone. Leave blank to disable.",
                color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = browserUseServerUrl,
                onValueChange = {
                    browserUseServerUrl = it
                    saved = false
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://192.168.1.23:8787", color = TextSecondary, fontSize = 13.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPurple,
                    unfocusedBorderColor = Color(0xFF2A2F4A),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentPurple
                ),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(Modifier.height(18.dp))

            // ── API Key ─────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, contentDescription = null,
                    tint = AccentPurple, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Gemini API Key", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste your API key here…", color = TextSecondary, fontSize = 13.sp) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Box(
                        modifier = Modifier.clickable { showKey = !showKey }.padding(8.dp)
                    ) {
                        Icon(
                            imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle key visibility",
                            tint = TextSecondary, modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPurple,
                    unfocusedBorderColor = Color(0xFF2A2F4A),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentPurple
                ),
                shape = RoundedCornerShape(10.dp)
            )
            Text(
                if (apiKey.isBlank()) "Using built-in key" else "Your key will be used",
                color = if (apiKey.isBlank()) TextSecondary else GreenLive,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp, start = 2.dp)
            )

            Spacer(Modifier.height(18.dp))

            // ── AI Persona ──────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null,
                    tint = AccentPurple, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("AI Persona", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = persona,
                onValueChange = { persona = it; saved = false },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("Describe Myra's personality…", color = TextSecondary, fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPurple,
                    unfocusedBorderColor = Color(0xFF2A2F4A),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentPurple
                ),
                shape = RoundedCornerShape(10.dp)
            )
            Text(
                "${persona.length} chars",
                color = TextSecondary, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 3.dp)
            )

            Spacer(Modifier.height(18.dp))

            // ── Voice Model ─────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null,
                    tint = AccentPurple, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Voice", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppSettings.AVAILABLE_VOICES.forEach { v ->
                    val selected = (v == voice)
                    FilterChip(
                        selected = selected,
                        onClick = { voice = v; saved = false },
                        label = { Text(v, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ChipSelected,
                            selectedLabelColor = Color.White,
                            containerColor = ChipUnselected,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            selectedBorderColor = ChipSelected,
                            borderColor = Color(0xFF2A2F4A)
                        )
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── Mic Volume Soft Boost (Fixes Gemini ignoring voice) ──────────
            Column {
                Text("Microphone Volume Boost", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Amplifies soft speech so Gemini hears you loud and clear every time.", color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        1.0f to "1.0x (Normal)",
                        1.5f to "1.5x (Boosted)",
                        2.0f to "2.0x (Loud)"
                    ).forEach { (g, label) ->
                        val selected = (micGain == g)
                        FilterChip(
                            selected = selected,
                            onClick = { micGain = g; saved = false },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ChipSelected,
                                selectedLabelColor = Color.White,
                                containerColor = ChipUnselected,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                selectedBorderColor = ChipSelected,
                                borderColor = Color(0xFF2A2F4A)
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── Auto-Sleep Idle Timeout (Eliminates daily background data burn) ─
            Column {
                Text("Auto-Sleep Idle Timeout", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Disconnects Gemini & stops mic after inactivity. Saves 100% data while idle.", color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        1 to "1 Min",
                        3 to "3 Mins",
                        5 to "5 Mins",
                        0 to "Disabled"
                    ).forEach { (mins, label) ->
                        val selected = (autoSleep == mins)
                        FilterChip(
                            selected = selected,
                            onClick = { autoSleep = mins; saved = false },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ChipSelected,
                                selectedLabelColor = Color.White,
                                containerColor = ChipUnselected,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                selectedBorderColor = ChipSelected,
                                borderColor = Color(0xFF2A2F4A)
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── Data Saver & Voice Activity Detection (VAD) ─────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Data Saver (Silence Suppression)", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Stops streaming mic data during silence.", color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Switch(
                    checked = isDataSaver,
                    onCheckedChange = { isDataSaver = it; saved = false },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = GreenLive,
                        checkedTrackColor = GreenLive.copy(alpha = 0.3f)
                    )
                )
            }

            if (isDataSaver) {
                Spacer(Modifier.height(10.dp))
                Text("VAD Mic Sensitivity", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppSettings.AVAILABLE_SENSITIVITIES.forEach { s ->
                        val selected = (s == vadSens)
                        FilterChip(
                            selected = selected,
                            onClick = { vadSens = s; saved = false },
                            label = { Text(s, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ChipSelected,
                                selectedLabelColor = Color.White,
                                containerColor = ChipUnselected,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                selectedBorderColor = ChipSelected,
                                borderColor = Color(0xFF2A2F4A)
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Screen Recording Mode ───────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Screen Recording Mode", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "Fixes echo loops when recording internal audio. Enable this if AI gets stuck listening to itself during screen records.",
                        color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp
                    )
                }
                Switch(
                    checked = isScreenRecMode,
                    onCheckedChange = { isScreenRecMode = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentPurple,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = Color(0xFF1E2132)
                    )
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Save & Reconnect button ──────────────────────────────────────
            Button(
                onClick = {
                    appSettings?.ownerUsername       = username // silently handles the hidden dev code, if that's what was typed
                    appSettings?.appDisplayName     = appName
                    appSettings?.geminiApiKey       = apiKey
                    appSettings?.browserUseServerUrl = browserUseServerUrl
                    val resolvedAddr = appSettings?.currentAddressName() ?: "Boss"
                    appSettings?.aiPersona          = AppSettings.generatePersona(devName, appName, resolvedAddr)
                    appSettings?.voiceName          = voice
                    appSettings?.isDataSaverEnabled = isDataSaver
                    appSettings?.vadSensitivity     = vadSens
                    appSettings?.micGain            = micGain
                    appSettings?.autoSleepMinutes   = autoSleep
                    appSettings?.isScreenRecordingModeEnabled = isScreenRecMode
                    username = appSettings?.ownerUsername ?: "" // reflect what was actually stored (blank again if a code was consumed)
                    persona = appSettings?.aiPersona ?: persona
                    saved = true
                    onSaveAndReconnect()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (saved) GreenLive else AccentPurple
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (saved) {
                    Icon(Icons.Default.Check, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Saved & Reconnecting…", fontWeight = FontWeight.Bold)
                } else {
                    Text("Save & Reconnect", fontWeight = FontWeight.Bold)
                }
            }

            Text(
                "Settings saved to device. A new Gemini session will start.",
                color = TextSecondary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Memory Section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MemorySection(
    memory2Engine: com.soltini.app.memory2.Memory2Engine? = null,
    appSettings: AppSettings? = null
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(true) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var backupStatusMessage by remember { mutableStateOf<String?>(null) }
    var backupPassphrase by remember { mutableStateOf("") }
    var restorePendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var restorePasswordInput by remember { mutableStateOf("") }

    var unifiedMemories by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    var sessionLogEntries by remember { mutableStateOf(memory2Engine?.db?.getRecentSessionLogEntries(50) ?: emptyList()) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedTypeFilter by remember { mutableStateOf<MemoryType?>(null) }
    var editingItem by remember { mutableStateOf<MemoryItem?>(null) }
    var editTextValue by remember { mutableStateOf("") }
    var editTypeValue by remember { mutableStateOf(MemoryType.FACT) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addTextValue by remember { mutableStateOf("") }
    var addTypeValue by remember { mutableStateOf(MemoryType.FACT) }

    fun refresh() {
        unifiedMemories = if (searchQuery.isNotBlank()) {
            memory2Engine?.searchMemoriesUnified(searchQuery) ?: emptyList()
        } else {
            memory2Engine?.getAllMemoriesUnified() ?: emptyList()
        }
        sessionLogEntries = memory2Engine?.db?.getRecentSessionLogEntries(50) ?: emptyList()
    }

    DisposableEffect(Unit) {
        refresh()
        onDispose { }
    }

    fun executeRestore(uri: android.net.Uri, pass: String?) {
        val m2Db = memory2Engine?.db
        if (m2Db != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val stats = MemoryBackupManager.importFromStream(inputStream, m2Db, pass)
                    if (stats.isSuccess) {
                        backupStatusMessage = "Restored ${stats.totalCount} records (Facts: ${stats.longTermCount}, Notes: ${stats.knowledgeCount}, Legacy: ${stats.legacyMigratedCount})"
                        refresh()
                    } else {
                        backupStatusMessage = "Restore failed: ${stats.errorMessage}"
                    }
                }
            } catch (e: Exception) {
                backupStatusMessage = "Restore error: ${e.message}"
            }
        } else {
            backupStatusMessage = "Memory engine not initialized."
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            restorePendingUri = uri
            restorePasswordInput = ""
            showRestorePasswordDialog = true
        }
    }

    SectionLabel("🧠  Memory")
    Spacer(Modifier.height(12.dp))

    // Unified Memory Engine stats card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(0.4f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Memory Engine", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Facts + conversation history, deduplicated automatically", color = TextSecondary, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${unifiedMemories.size}", color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Saved Facts", color = TextSecondary, fontSize = 10.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${sessionLogEntries.size}", color = GreenLive, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Past Conversations", color = TextSecondary, fontSize = 10.sp)
                    }
                }
            }

            if (sessionLogEntries.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Recently discussed:", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sessionLogEntries.take(5).forEach { entry ->
                        Text(
                            "• ${entry.topic}",
                            color = TextPrimary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    // Stored Memories Card & Interactive Memory Manager
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2F4A))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {

            // Header row with count + Add button + expand toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, contentDescription = null,
                        tint = AccentPurple, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Memory Manager", color = TextPrimary,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "${unifiedMemories.size} items stored",
                            color = TextSecondary, fontSize = 11.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            addTextValue = ""
                            addTypeValue = MemoryType.FACT
                            showAddDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Icon(
                        Icons.Default.ExpandMore.let { if (expanded) Icons.Default.ExpandLess else it },
                        contentDescription = "Toggle memory list",
                        tint = TextSecondary, modifier = Modifier.size(24.dp).clickable {
                            expanded = !expanded
                            if (expanded) refresh()
                        }
                    )
                }
            }

            // Expandable content
            AnimatedVisibility(visible = expanded,
                enter = expandVertically(), exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    // Search Bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            unifiedMemories = if (it.isNotBlank()) {
                                memory2Engine?.searchMemoriesUnified(it) ?: emptyList()
                            } else {
                                memory2Engine?.getAllMemoriesUnified() ?: emptyList()
                            }
                        },
                        placeholder = { Text("Search memories, facts, preferences...", color = TextSecondary, fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary,
                                    modifier = Modifier.size(16.dp).clickable {
                                        searchQuery = ""
                                        refresh()
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = Color(0xFF2A2F4A),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(Modifier.height(10.dp))

                    // Filter Chips Row
                    val filterTypes = listOf(
                        null to "All",
                        MemoryType.PREFERENCE to "Preferences",
                        MemoryType.CORRECTION to "Corrections",
                        MemoryType.USER_PROFILE to "Profile",
                        MemoryType.FACT to "Facts",
                        MemoryType.PROJECT to "Projects",
                        MemoryType.ROUTINE to "Routines"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        filterTypes.forEach { (type, label) ->
                            val isSelected = selectedTypeFilter == type
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedTypeFilter = type
                                },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentBlue,
                                    selectedLabelColor = Color.White,
                                    containerColor = DarkSurfaceVariant,
                                    labelColor = TextSecondary
                                ),
                                border = null
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    val displayItems = unifiedMemories.filter {
                        selectedTypeFilter == null || it.type == selectedTypeFilter
                    }

                    if (displayItems.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (searchQuery.isNotBlank()) "No memories matching \"$searchQuery\""
                                else "No memories recorded yet in this category.",
                                color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            displayItems.take(50).forEach { item ->
                                val badgeColor = when (item.type) {
                                    MemoryType.PREFERENCE -> AccentBlue
                                    MemoryType.CORRECTION -> AmberWarning
                                    MemoryType.USER_PROFILE -> Purple
                                    MemoryType.PROJECT -> GreenLive
                                    MemoryType.ROUTINE -> AccentPurple
                                    else -> Color(0xFF6B7280)
                                }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2F4A))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(badgeColor.copy(alpha = 0.2f))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        item.type.name,
                                                        color = badgeColor,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }

                                                if (item.sensitivityLevel.equals("SENSITIVE", ignoreCase = true)) {
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(RedError.copy(alpha = 0.15f))
                                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                                    ) {
                                                        Text("Sensitive", color = RedError, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                                                    }
                                                }

                                                if (item.userConfirmed) {
                                                    Text("✓ Verified", color = GreenLive, fontSize = 9.sp)
                                                }
                                            }

                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                item.text,
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                lineHeight = 18.sp
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = "Edit Memory",
                                                tint = AccentBlue,
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .clickable {
                                                        editingItem = item
                                                        editTextValue = item.text
                                                        editTypeValue = item.type
                                                    }
                                                    .padding(6.dp)
                                            )

                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete Memory",
                                                tint = RedError.copy(alpha = 0.8f),
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .clickable {
                                                        memory2Engine?.deleteMemory(item.id)
                                                        refresh()
                                                    }
                                                    .padding(6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Clear All button
                    if (displayItems.isNotEmpty()) {
                        if (showClearConfirm) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        memory2Engine?.clearAllMemories()
                                        showClearConfirm = false
                                        refresh()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RedError),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) { Text("Yes, clear all", fontSize = 13.sp) }
                                Button(
                                    onClick = { showClearConfirm = false },
                                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) { Text("Cancel", fontSize = 13.sp) }
                            }
                        } else {
                            Button(
                                onClick = { showClearConfirm = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A1A1A)),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null,
                                    modifier = Modifier.size(16.dp), tint = RedError)
                                Spacer(Modifier.width(6.dp))
                                Text("Clear All Memories", color = RedError, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Memory Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        if (addTextValue.isNotBlank()) {
                            memory2Engine?.saveExplicitMemory(addTextValue.trim(), addTypeValue, confirmed = true)
                            refresh()
                        }
                        showAddDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text("Save Memory")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            title = { Text("Add Memory or Rule", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Record a personal fact, preference, user rule, or project detail for MYRA:", color = TextSecondary, fontSize = 12.sp)
                    OutlinedTextField(
                        value = addTextValue,
                        onValueChange = { addTextValue = it },
                        placeholder = { Text("e.g. User prefers concise answers", color = TextSecondary, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = Color(0xFF2A2F4A),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Text("Memory Category:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(MemoryType.PREFERENCE, MemoryType.FACT, MemoryType.CORRECTION, MemoryType.USER_PROFILE, MemoryType.PROJECT).forEach { t ->
                            FilterChip(
                                selected = addTypeValue == t,
                                onClick = { addTypeValue = t },
                                label = { Text(t.name, fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentBlue,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    // Edit Memory Dialog
    if (editingItem != null) {
        val item = editingItem!!
        AlertDialog(
            onDismissRequest = { editingItem = null },
            confirmButton = {
                Button(
                    onClick = {
                        if (editTextValue.isNotBlank()) {
                            memory2Engine?.updateMemory(item.id, newContent = editTextValue.trim(), newType = editTypeValue)
                            refresh()
                        }
                        editingItem = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text("Update")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            title = { Text("Edit Memory", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Modify this stored memory or rule:", color = TextSecondary, fontSize = 12.sp)
                    OutlinedTextField(
                        value = editTextValue,
                        onValueChange = { editTextValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = Color(0xFF2A2F4A),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Text("Memory Category:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(MemoryType.PREFERENCE, MemoryType.FACT, MemoryType.CORRECTION, MemoryType.USER_PROFILE, MemoryType.PROJECT).forEach { t ->
                            FilterChip(
                                selected = editTypeValue == t,
                                onClick = { editTypeValue = t },
                                label = { Text(t.name, fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentBlue,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    Spacer(Modifier.height(14.dp))

    // Encrypted Memory Backup & Restore Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(0.4f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Encrypted Memory Backup", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("AES-256 Android Keystore Protected Backup", color = TextSecondary, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Export persistent memories (facts, personal context, knowledge, and experience) as an encrypted backup to restore across devices or sync with Google Drive.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = backupPassphrase,
                onValueChange = { backupPassphrase = it },
                label = { Text("Backup Password / Passphrase (Optional)") },
                placeholder = { Text("Enter custom passphrase for PBKDF2 AES-256") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentBlue
                )
            )

            val isKeystoreAvailable = remember { MemoryBackupManager.isKeyStoreAvailable() }
            if (backupPassphrase.isBlank() && !isKeystoreAvailable) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3E1F1F))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Warning: Hardware KeyStore unavailable on this device. Without a passphrase, the backup will be exported unencrypted/weakly protected!",
                        color = Color(0xFFFF6B6B),
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
            } else if (backupPassphrase.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "🛡️ Strong PBKDF2 (100,000 rounds) AES-256 encryption active.",
                    color = GreenLive,
                    fontSize = 11.sp
                )
            }

            if (backupStatusMessage != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E2846))
                        .padding(10.dp)
                ) {
                    Text(backupStatusMessage!!, color = AccentBlue, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val m2Db = memory2Engine?.db
                        if (m2Db != null) {
                            val passToUse = backupPassphrase.ifBlank { null }
                            val res = MemoryBackupManager.exportEncryptedBackup(context, m2Db, passToUse)
                            if (res.isSuccess) {
                                val file = res.getOrNull()!!
                                backupStatusMessage = "Backup saved: ${file.name} (${file.length()} bytes)"
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "com.soltini.app.fileprovider",
                                        file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/octet-stream"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Save Backup to Google Drive / Files"))
                                } catch (_: Exception) {}
                            } else {
                                backupStatusMessage = "Backup failed: ${res.exceptionOrNull()?.message}"
                            }
                        } else {
                            backupStatusMessage = "Memory database not initialized."
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Backup Memory", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        restoreLauncher.launch("*/*")
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(0.6f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentBlue)
                    Spacer(Modifier.width(6.dp))
                    Text("Restore Memory", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (showRestorePasswordDialog && restorePendingUri != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {
                        showRestorePasswordDialog = false
                        restorePendingUri = null
                    },
                    title = { Text("Restore Memory Backup", color = TextPrimary) },
                    text = {
                        Column {
                            Text(
                                "Agar ye backup kisi password se encrypt kiya gaya tha, toh password daaliye. Agar bina password (KeyStore) backup tha, toh khali chhod kar Continue dabayein.",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = restorePasswordInput,
                                onValueChange = { restorePasswordInput = it },
                                label = { Text("Backup Passphrase") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val uri = restorePendingUri
                                val pass = restorePasswordInput.ifBlank { null }
                                showRestorePasswordDialog = false
                                restorePendingUri = null
                                if (uri != null) {
                                    executeRestore(uri, pass)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                        ) {
                            Text("Restore")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showRestorePasswordDialog = false
                            restorePendingUri = null
                        }) {
                            Text("Cancel", color = TextSecondary)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun BankingProtectionCard(context: Context) {
    val appSettings = remember { AppSettings(context) }
    val voiceManager = remember { VoiceBiometricsManager.getInstance(context) }
    var autoHideEnabled by remember { mutableStateOf(appSettings.isBankingProtectionEnabled) }
    var isPaused by remember { mutableStateOf(appSettings.isBankingModePaused || (com.soltini.app.overlay.OverlayService.getInstance()?.isHiddenForBanking() == true)) }
    var isVoiceLockEnrolled by remember { mutableStateOf(voiceManager.isEnrolled()) }
    var isVoiceLockActive by remember { mutableStateOf(voiceManager.isVoiceLockEnabled()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentBlue.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(24.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Banking App Protection", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Fixes banking apps (GPay, PhonePe, Paytm, YONO, SBI, HDFC) closing or blocking launch", color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Auto-Hide Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Hide Overlay & A11y", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Auto-hides floating orb & pauses screen reading whenever a banking app comes to foreground", color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                androidx.compose.material3.Switch(
                    checked = autoHideEnabled,
                    onCheckedChange = { checked ->
                        autoHideEnabled = checked
                        appSettings.isBankingProtectionEnabled = checked
                    },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = AccentBlue,
                        checkedTrackColor = AccentBlue.copy(alpha = 0.3f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Manual Pause Toggle Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Manual Pause (Banking Mode)", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(if (isPaused) "Overlay is PAUSED right now" else "Overlay is ACTIVE", color = if (isPaused) AmberWarning else GreenLive, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (voiceManager.isEnrolled() && voiceManager.isVoiceLockEnabled() && !voiceManager.isRecentAuthValid()) {
                            VoiceUnlockActivity.startForBankingToggle(context)
                        } else {
                            val newState = !isPaused
                            isPaused = newState
                            appSettings.isBankingModePaused = newState
                            com.soltini.app.overlay.OverlayService.getInstance()?.setHiddenForBanking(newState)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPaused) GreenLive.copy(alpha = 0.2f) else AmberWarning.copy(alpha = 0.2f),
                        contentColor = if (isPaused) GreenLive else AmberWarning
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(if (isPaused) "Resume" else "Pause Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Voice-Lock Banking Mode Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF161A2E))
                    .padding(12.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Voice-Lock Banking Mode", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    if (isVoiceLockEnrolled) "Enrolled (${voiceManager.getEnrolledSampleCount()} samples)" else "Not enrolled (Tap below to enroll)",
                                    color = if (isVoiceLockEnrolled) GreenLive else AmberWarning,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (isVoiceLockEnrolled) {
                            androidx.compose.material3.Switch(
                                checked = isVoiceLockActive,
                                onCheckedChange = { checked ->
                                    isVoiceLockActive = checked
                                    voiceManager.setVoiceLockEnabled(checked)
                                },
                                colors = androidx.compose.material3.SwitchDefaults.colors(
                                    checkedThumbColor = AccentPurple,
                                    checkedTrackColor = AccentPurple.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Protects sensitive actions (overlay resume/unpause and confirmed destructive file operations) with acoustic voice embedding verification.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            VoiceUnlockActivity.startEnrollment(context)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E385D)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isVoiceLockEnrolled) "Re-enroll Voice Signature (3 Samples)" else "Enroll Boss Voice (3 Samples)",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tip / Guidance Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceVariant)
                    .padding(10.dp)
            ) {
                Column {
                    Text("💡 Quick Control Tips:", color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "1. Notification Shade: Tap \"Pause (Banking)\" in Myra's persistent notification.\n" +
                        "2. Quick Tile: Add \"Myra Banking Mode\" tile to your Android Quick Settings panel for 1-tap toggle.\n" +
                        "3. If any bank insists on disabling Accessibility service completely, use the button below.",
                        color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Accessibility, null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open Accessibility Settings", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── Autonomous Messaging & Busy Mode Card ────────────────────────────────────
@Composable
private fun BusyModeCard(context: Context) {
    val busyManager = remember { com.soltini.app.messaging.BusyModeManager.getInstance(context) }
    var isBusy by remember { mutableStateOf(busyManager.isBusyModeActive()) }
    var reasonText by remember { mutableStateOf(busyManager.getBusyReason()) }
    var recentLogs by remember { mutableStateOf(busyManager.getRecentHistory(10)) }
    var showLogs by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isBusy) GreenLive.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isBusy) GreenLive.copy(alpha = 0.15f) else AccentBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                            tint = if (isBusy) GreenLive else AccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            "Autonomous Messaging (Busy Mode)",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            if (isBusy) "🟢 ACTIVE — Auto-replying contextually" else "⚪ INACTIVE",
                            color = if (isBusy) GreenLive else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isBusy) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }

                androidx.compose.material3.Switch(
                    checked = isBusy,
                    onCheckedChange = { checked ->
                        if (checked) {
                            busyManager.enableBusyMode(reasonText)
                            isBusy = true
                        } else {
                            busyManager.disableBusyMode()
                            isBusy = false
                        }
                        recentLogs = busyManager.getRecentHistory(10)
                    },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = GreenLive,
                        checkedTrackColor = GreenLive.copy(alpha = 0.3f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                "Jab aap busy hon (\"Myra main busy hoon\"), Myra WhatsApp, Instagram, Telegram aur SMS ke naye messages ka polite aur contextual reply khud bhejti hai.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Reason field
            OutlinedTextField(
                value = reasonText,
                onValueChange = {
                    reasonText = it
                    if (isBusy) busyManager.enableBusyMode(it)
                },
                label = { Text("Busy Reason (e.g. driving, meeting, studying)", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPrimary),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    focusedLabelColor = AccentBlue,
                    unfocusedLabelColor = TextSecondary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Loop and self-reply safety badge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E2838))
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Loop & Self-Reply Protection Active: Myra apne ya user ke messages ka reply nahi karegi. 3-minute cooldown & acknowledgment suppression active.",
                        color = Color(0xFFBAC7D5),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            if (recentLogs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLogs = !showLogs },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Recent Auto-Replies (${recentLogs.size})",
                        color = AccentBlue,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Icon(
                        if (showLogs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (showLogs) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        recentLogs.forEach { log ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(DarkSurfaceVariant)
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "${log.appName}: ${log.sender}",
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            "Auto-replied",
                                            color = GreenLive,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    if (log.incomingText.isNotBlank()) {
                                        Text(
                                            "In: \"${log.incomingText}\"",
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                            maxLines = 1
                                        )
                                    }
                                    Text(
                                        "Out: \"${log.replyText}\"",
                                        color = Color(0xFF81C784),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Notifications & Direct Reply Section (Option B) ───────────────────────────
@Composable
private fun NotificationsSettingsSection(context: Context) {
    val notifRepo = remember { NotificationRepository.getInstance(context) }
    val isGranted = SettingsPermChecks.checkNotificationAccess(context)
    val ignoredApps by notifRepo.ignoredApps.collectAsState()
    val recentNotifs by notifRepo.notifications.collectAsState()
    var newAppToIgnore by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isGranted) GreenLive.copy(alpha = 0.5f) else AmberWarning.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                if (isGranted) GreenLive.copy(alpha = 0.15f) else AmberWarning.copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isGranted) GreenLive else AmberWarning,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Notification Listener Access",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            if (isGranted) "Active • Myra can read & reply to notifications" else "Permission Required • Needed to read incoming messages",
                            color = if (isGranted) GreenLive else AmberWarning,
                            fontSize = 12.sp
                        )
                    }
                }

                if (!isGranted) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            } catch (_: Exception) {}
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Enable Notification Access", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // How it works card (Option B)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "💡 Voice Commands (On-Demand Option B):",
                    color = AccentBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "• \"Koi notification aaya kya?\" / \"Read my notifications\"\n" +
                    "• \"Rahul ko reply karo: Main 10 minute mein aa raha hoon\"\n" +
                    "• \"WhatsApp pe reply kar do: Theek hai\"\n" +
                    "• \"Chrome ke notifications ignore karo\"\n" +
                    "• \"Chrome ko unignore karo\"",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Ignored Apps Management Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Selective App Ignore List",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    "By default, all apps are allowed. Only apps you explicitly tell Myra or add below will be ignored.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Input to add ignored app
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newAppToIgnore,
                        onValueChange = { newAppToIgnore = it },
                        placeholder = { Text("App name (e.g. Chrome, Instagram)", color = TextSecondary, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newAppToIgnore.isNotBlank()) {
                                notifRepo.ignoreApp(newAppToIgnore)
                                newAppToIgnore = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape = RoundedCornerShape(10.dp),
                        enabled = newAppToIgnore.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Ignore", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (ignoredApps.isEmpty()) {
                    Text(
                        "No apps ignored. Myra reads notifications from all messaging apps when asked.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    Text(
                        "Currently Ignored Apps (${ignoredApps.size}):",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ignoredApps.forEach { appName ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(DarkSurfaceVariant)
                                    .border(1.dp, RedError.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        appName.replaceFirstChar { it.uppercase() },
                                        color = TextPrimary,
                                        fontSize = 12.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = RedError,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { notifRepo.unignoreApp(appName) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Recent Captured Notifications Preview
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Recent Captured Notifications",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            "${recentNotifs.size} in memory",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    if (recentNotifs.isNotEmpty()) {
                        TextButton(onClick = { notifRepo.clearAllNotifications() }) {
                            Text("Clear", color = RedError, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (recentNotifs.isEmpty()) {
                    Text(
                        if (isGranted) "No active notifications right now. Any notification received will appear here." else "Grant Notification Access above to start capturing notifications.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recentNotifs.take(8).forEach { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DarkSurfaceVariant.copy(alpha = 0.7f))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.appName,
                                            color = AccentBlue,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "• ${item.getTimeAgo()}",
                                            color = TextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                    if (item.canReply) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(GreenLive.copy(alpha = 0.15f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.Reply,
                                                    contentDescription = null,
                                                    tint = GreenLive,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text("Direct Reply", color = GreenLive, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    item.title,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                if (item.text.isNotBlank()) {
                                    Text(
                                        item.text,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Screen Companion Settings Section ─────────────────────────────────────────

@Composable
fun ScreenCompanionSection(
    context: Context,
    appSettings: AppSettings?
) {
    val companionManager = remember { com.soltini.app.companion.ScreenCompanionManager.getInstance(context) }
    var isLiveCompanionOn by remember { mutableStateOf(companionManager.isLiveCompanionEnabled.value) }
    var isBankingProtected by remember { mutableStateOf(appSettings?.isBankingProtectionEnabled ?: true) }
    var snapshotResult by remember { mutableStateOf<com.soltini.app.companion.ScreenCompanionManager.ScreenSnapshot?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon / Header
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(GreenLive.copy(alpha = 0.35f), DarkSurface)))
                .border(1.dp, GreenLive.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Visibility, contentDescription = null, tint = GreenLive, modifier = Modifier.size(36.dp))
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            "Live Screen Companion",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Myra sits next to you like a real companion, watching your screen and giving helpful tips, corrections, and thoughts as you work or type.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Proactive Companion Toggle Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isLiveCompanionOn) GreenLive.copy(alpha = 0.5f) else DarkBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        "Proactive Human Eye",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "When ON, Myra notices when you are typing or stuck on a screen and speaks up with sweet, helpful advice naturally.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Switch(
                    checked = isLiveCompanionOn,
                    onCheckedChange = { newState ->
                        isLiveCompanionOn = newState
                        companionManager.setLiveCompanionEnabled(newState)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = GreenLive,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = DarkSurfaceVariant
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Banking & Privacy Protection Toggle Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        "Banking & Privacy Shield",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Instantly hides observation and clears screen memory whenever banking, UPI, or password fields are open.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Switch(
                    checked = isBankingProtected,
                    onCheckedChange = { newState ->
                        isBankingProtected = newState
                        appSettings?.isBankingProtectionEnabled = newState
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentBlue,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = DarkSurfaceVariant
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Live Test Button
        Button(
            onClick = {
                isCapturing = true
                snapshotResult = companionManager.captureCurrentScreenContext()
                isCapturing = false
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Test Screen Observation Now", fontWeight = FontWeight.SemiBold)
        }

        // Test Output Preview
        snapshotResult?.let { snap ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (snap.isAvailable) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (snap.isAvailable) GreenLive else AmberWarning,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (snap.isAvailable) "Active App: ${snap.currentApp}" else "Status",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (!snap.isAvailable) {
                        Text(snap.errorMessage ?: "Screen not available", color = AmberWarning, fontSize = 12.sp)
                    } else {
                        Text(
                            snap.toHumanSummary(),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Example Voice Prompts
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "Try saying to Myra:",
                    color = AccentBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("• \"Dekho screen pe kya chal raha hai\"", color = TextPrimary, fontSize = 12.sp)
                Text("• \"Yeh email check karke batao kaisa likha hai\"", color = TextPrimary, fontSize = 12.sp)
                Text("• \"Mera code dekh ke batao kya issue hai\"", color = TextPrimary, fontSize = 12.sp)
                Text("• \"Screen companion mode on karo\"", color = TextPrimary, fontSize = 12.sp)
            }
        }
    }
}



