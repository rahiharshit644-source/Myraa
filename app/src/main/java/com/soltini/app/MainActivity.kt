package com.soltini.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.soltini.app.homeautomation.ui.AddDeviceScreen
import com.soltini.app.homeautomation.ui.HomeAutomationScreen
import com.soltini.app.homeautomation.ui.HomeAutomationViewModel
import com.soltini.app.overlay.OverlayService
import com.soltini.app.ui.LogScreen
import com.soltini.app.ui.MainViewModel
import com.soltini.app.ui.MiuiOnboardingScreen
import com.soltini.app.ui.PermissionsScreen
import com.soltini.app.ui.SettingsScreen
import com.soltini.app.ui.VoiceScreen
import com.soltini.app.ui.isMiuiOnboardingNeeded
import com.soltini.app.ui.theme.AccentBlue
import com.soltini.app.ui.theme.AccentPurple
import com.soltini.app.ui.theme.AccentTeal
import com.soltini.app.ui.theme.DarkBorder
import com.soltini.app.ui.theme.DarkSurface
import com.soltini.app.ui.theme.GeminiVoiceTheme
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary

/**
 * MainActivity
 *
 * Routing logic:
 *   1. "permissions" — First launch: only asks for Microphone (required to start).
 *   2. "voice"       — Main screen with floating orb.
 *   3. "settings"    — Full permissions dashboard (overlay, accessibility, assistant role…).
 *   4. "logs"        — Debug log viewer.
 *
 * First launch detection: we only block on Microphone. The Overlay permission
 * is checked but the app still launches so the user can be guided from the Settings page.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        val homeViewModel = ViewModelProvider(this)[HomeAutomationViewModel::class.java]

        setContent {
            GeminiVoiceTheme {
                val hasMic = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                // Only block on Microphone for the very first launch.
                // Everything else is managed from the Settings page.
                // On Xiaomi/POCO/Redmi, show MIUI onboarding after mic is granted (once only).
                var currentScreen by remember {
                    mutableStateOf(
                        when {
                            !hasMic -> "permissions"
                            isMiuiOnboardingNeeded(this@MainActivity) -> "miui_onboarding"
                            else -> "voice"
                        }
                    )
                }

                // Start the overlay service as soon as we are past the mic gate
                LaunchedEffect(currentScreen) {
                    if (currentScreen == "voice" || currentScreen == "settings" || currentScreen == "logs" || currentScreen == "home_automation") {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            OverlayService.start(this@MainActivity)
                        }
                    }
                }

                val showBottomNav = currentScreen in listOf("voice", "home_automation", "research", "logs", "settings", "add_device")

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (showBottomNav) {
                            NavigationBar(
                                containerColor = DarkSurface,
                                contentColor = TextPrimary,
                                tonalElevation = 8.dp,
                                modifier = Modifier.border(width = 0.5.dp, color = DarkBorder)
                            ) {
                                NavigationBarItem(
                                    selected = currentScreen == "voice",
                                    onClick = { currentScreen = "voice" },
                                    icon = { Icon(Icons.Default.Mic, contentDescription = "Voice AI") },
                                    label = { Text("Voice AI", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AccentBlue,
                                        selectedTextColor = AccentBlue,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary,
                                        indicatorColor = AccentBlue.copy(alpha = 0.15f)
                                    )
                                )
                                NavigationBarItem(
                                    selected = currentScreen == "research",
                                    onClick = { currentScreen = "research" },
                                    icon = { Icon(Icons.Default.Search, contentDescription = "Internet Research") },
                                    label = { Text("Internet", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AccentTeal,
                                        selectedTextColor = AccentTeal,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary,
                                        indicatorColor = AccentTeal.copy(alpha = 0.15f)
                                    )
                                )
                                NavigationBarItem(
                                    selected = currentScreen == "home_automation" || currentScreen == "add_device",
                                    onClick = { currentScreen = "home_automation" },
                                    icon = { Icon(Icons.Default.ElectricBolt, contentDescription = "Smart Home") },
                                    label = { Text("Smart Home", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AccentTeal,
                                        selectedTextColor = AccentTeal,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary,
                                        indicatorColor = AccentTeal.copy(alpha = 0.15f)
                                    )
                                )
                                NavigationBarItem(
                                    selected = currentScreen == "logs",
                                    onClick = { currentScreen = "logs" },
                                    icon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "Logs") },
                                    label = { Text("Logs", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AccentPurple,
                                        selectedTextColor = AccentPurple,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary,
                                        indicatorColor = AccentPurple.copy(alpha = 0.15f)
                                    )
                                )
                                NavigationBarItem(
                                    selected = currentScreen == "settings",
                                    onClick = { currentScreen = "settings" },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                    label = { Text("Settings", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AccentBlue,
                                        selectedTextColor = AccentBlue,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary,
                                        indicatorColor = AccentBlue.copy(alpha = 0.15f)
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = innerPadding.calculateBottomPadding())
                    ) {
                        when (currentScreen) {
                            "permissions" -> {
                                // First-launch screen: only asks for Microphone
                                PermissionsScreen(
                                    onAllPermissionsGranted = {
                                        // After mic granted, check if MIUI onboarding is needed
                                        currentScreen = if (isMiuiOnboardingNeeded(this@MainActivity)) {
                                            "miui_onboarding"
                                        } else {
                                            "voice"
                                        }
                                    },
                                    modifier = Modifier
                                )
                            }
                            "miui_onboarding" -> {
                                MiuiOnboardingScreen(
                                    onComplete = { currentScreen = "voice" }
                                )
                            }
                            "logs" -> {
                                LogScreen(
                                    onBack = { currentScreen = "voice" },
                                    modifier = Modifier
                                )
                            }
                            "settings" -> {
                                // Full permissions & capabilities dashboard
                                SettingsScreen(
                                    onBack = { currentScreen = "voice" },
                                    viewModel = viewModel,
                                    onOpenHomeAutomation = { currentScreen = "home_automation" },
                                    modifier = Modifier
                                )
                            }
                            "home_automation" -> {
                                HomeAutomationScreen(
                                    viewModel = homeViewModel,
                                    onNavigateToAddDevice = { currentScreen = "add_device" },
                                    onBack = { currentScreen = "voice" },
                                    modifier = Modifier
                                )
                            }
                            "research" -> {
                                com.soltini.app.reach.ui.InternetResearchScreen(
                                    onBack = { currentScreen = "voice" },
                                    modifier = Modifier
                                )
                            }
                            "add_device" -> {
                                AddDeviceScreen(
                                    viewModel = homeViewModel,
                                    onNavigateBack = { currentScreen = "home_automation" },
                                    modifier = Modifier
                                )
                            }
                            else -> {
                                VoiceScreen(
                                    viewModel = viewModel,
                                    onOpenLogs = { currentScreen = "logs" },
                                    onOpenSettings = { currentScreen = "settings" },
                                    onOpenHomeAutomation = { currentScreen = "home_automation" },
                                    modifier = Modifier
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
