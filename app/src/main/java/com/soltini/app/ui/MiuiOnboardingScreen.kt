package com.soltini.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.ui.theme.AccentBlue
import com.soltini.app.ui.theme.DarkBackground
import com.soltini.app.ui.theme.DarkBorder
import com.soltini.app.ui.theme.DarkSurface
import com.soltini.app.ui.theme.DarkSurfaceVariant
import com.soltini.app.ui.theme.GreenLive
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary

private val MiuiOrange = Color(0xFFFF6D00)
private val MiuiOrangeSoft = Color(0xFFFF8F00)

/**
 * MiuiOnboardingScreen
 *
 * A full-screen multi-step guide shown ONCE on first launch for Xiaomi/POCO/Redmi devices.
 * Walks users through all four settings that prevent MIUI from aggressively killing the app:
 *
 * 1. Autostart — Security → Permissions → Autostart → toggle ON
 * 2. Battery "No restrictions" — Settings → Apps → Manage apps → app → Battery saver
 * 3. Lock in Recents — Long-press app card → tap lock icon (🔒)
 *
 * Track completion state in SharedPreferences so it only shows once.
 */
@Composable
fun MiuiOnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    var currentStep by remember { mutableIntStateOf(0) }

    val steps = remember {
        listOf(
            MiuiStep(
                icon = Icons.Default.PowerSettingsNew,
                iconTint = MiuiOrange,
                title = "Enable Autostart",
                subtitle = "MIUI kills apps that can't autostart — this is the #1 fix",
                instructions = listOf(
                    "Open Security app on your phone",
                    "Tap Permissions",
                    "Tap Autostart",
                    "Find Myra and toggle it ON"
                ),
                actionLabel = "Open Security App",
                hasAction = true
            ),
            MiuiStep(
                icon = Icons.Default.Battery5Bar,
                iconTint = GreenLive,
                title = "No Battery Restrictions",
                subtitle = "Set battery saver to \"No restrictions\" for Myra",
                instructions = listOf(
                    "Tap the button below to open Myra's app settings",
                    "Scroll down and tap Battery saver",
                    "Select No restrictions",
                    "Come back here and continue"
                ),
                actionLabel = "Open App Battery Settings",
                hasAction = true
            ),
            MiuiStep(
                icon = Icons.Default.Lock,
                iconTint = AccentBlue,
                title = "Lock in Recent Apps",
                subtitle = "Prevents \"Clear All\" from force-stopping Myra",
                instructions = listOf(
                    "Press the Recents (\u25a1) button on your phone",
                    "Find the Myra app card",
                    "Long-press the Myra card",
                    "Tap the \ud83d\udd12 lock icon that appears"
                ),
                actionLabel = null, // User must do this manually
                hasAction = false
            )
        )
    }


    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = DarkBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Header ───────────────────────────────────────────────────────
            MiuiOnboardingHeader()

            Spacer(modifier = Modifier.height(28.dp))

            // ── Step Progress Dots ────────────────────────────────────────────
            StepProgressDots(current = currentStep, total = steps.size)

            Spacer(modifier = Modifier.height(28.dp))

            // ── Animated Step Content ─────────────────────────────────────────
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    (slideInHorizontally { it } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it } + fadeOut())
                },
                label = "step_content"
            ) { step ->
                MiuiStepCard(
                    step = steps[step],
                    stepNumber = step + 1,
                    onAction = { launchMiuiAction(context, step) }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Navigation Buttons ────────────────────────────────────────────
            val isLastStep = currentStep == steps.size - 1

            Button(
                onClick = {
                    if (isLastStep) {
                        markMiuiOnboardingComplete(context)
                        onComplete()
                    } else {
                        currentStep++
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLastStep) GreenLive else MiuiOrange
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    if (isLastStep) "✓  All Done — Start Myra" else "Next Step →",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Back / Skip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AnimatedVisibility(visible = currentStep > 0) {
                    OutlinedButton(
                        onClick = { currentStep-- },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("← Back", fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = {
                        markMiuiOnboardingComplete(context)
                        onComplete()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Skip All", fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "You can re-open this guide anytime from Settings → Permissions",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────
@Composable
private fun MiuiOnboardingHeader() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val rotation by pulse.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation"
    )
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "scale"
    )

    Box(contentAlignment = Alignment.Center) {
        // Rotating outer ring
        Box(
            modifier = Modifier
                .size(90.dp)
                .rotate(rotation)
                .border(
                    width = 1.5.dp,
                    brush = Brush.sweepGradient(
                        listOf(MiuiOrange.copy(0f), MiuiOrange, MiuiOrange.copy(0f))
                    ),
                    shape = CircleShape
                )
        )
        // Pulsing center orb
        Box(
            modifier = Modifier
                .size(76.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(MiuiOrange.copy(0.4f), MiuiOrange.copy(0.05f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MiuiOrange,
                modifier = Modifier.size(36.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    Text(
        "Xiaomi Setup Required",
        style = MaterialTheme.typography.headlineSmall,
        color = TextPrimary,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "MIUI aggressively kills background apps.\nComplete these 4 steps so Myra keeps running.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        textAlign = TextAlign.Center,
        lineHeight = 21.sp
    )
}

// ── Step Progress Dots ────────────────────────────────────────────────────────
@Composable
private fun StepProgressDots(current: Int, total: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            val isActive = i == current
            val isPast = i < current
            Box(
                modifier = Modifier
                    .size(if (isActive) 28.dp else 10.dp, 10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        when {
                            isActive -> MiuiOrange
                            isPast -> GreenLive.copy(alpha = 0.7f)
                            else -> DarkBorder
                        }
                    )
            )
        }
    }
}

// ── Step Card ─────────────────────────────────────────────────────────────────
private data class MiuiStep(
    val icon: ImageVector,
    val iconTint: Color,
    val title: String,
    val subtitle: String,
    val instructions: List<String>,
    val actionLabel: String?,
    val hasAction: Boolean
)

@Composable
private fun MiuiStepCard(
    step: MiuiStep,
    stepNumber: Int,
    onAction: () -> Unit
) {
    var actionTapped by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            step.iconTint.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Icon + title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(step.iconTint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        step.icon, null,
                        tint = step.iconTint,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(step.iconTint.copy(alpha = 0.2f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "Step $stepNumber",
                                color = step.iconTint,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        step.title,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        step.subtitle,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Numbered instruction steps
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    step.instructions.forEachIndexed { idx, instruction ->
                        Row(
                            modifier = Modifier.padding(vertical = 5.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(step.iconTint.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${idx + 1}",
                                    color = step.iconTint,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                instruction,
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            // Action button (if applicable)
            if (step.hasAction && step.actionLabel != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        onAction()
                        actionTapped = true
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (actionTapped) step.iconTint.copy(alpha = 0.3f) else step.iconTint
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (actionTapped) "✓ Opened — Come back when done" else step.actionLabel,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = if (actionTapped) step.iconTint else Color.White
                    )
                }
            }
        }
    }
}

// ── Deep-link helpers ─────────────────────────────────────────────────────────

private fun launchMiuiAction(context: Context, step: Int) {
    when (step) {
        0 -> launchAutoStartSettings(context)
        1 -> launchBatterySettings(context)
        // step 2 is Lock in Recents — no deeplink, user does it manually
    }
}

/** Step 1: Autostart — try MIUI Security Center, then fall back to App Settings */
private fun launchAutoStartSettings(context: Context) {
    val intents = listOf(
        // Xiaomi / POCO / Redmi (MIUI)
        Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        },
        // Xiaomi alternative
        Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.securitycenter.MainActivity"
            )
        },
        // Generic fallback
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
    tryStartActivities(context, intents)
}

/** Step 2: Battery "No restrictions" — try MIUI Power Keeper, then App Details */
private fun launchBatterySettings(context: Context) {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val intents = mutableListOf<Intent>()

    if (manufacturer == "xiaomi" || manufacturer == "poco" || manufacturer == "redmi") {
        // Try MIUI-specific battery management for this app
        intents.add(Intent().apply {
            component = ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        // MIUI battery management list
        intents.add(Intent().apply {
            action = "miui.intent.action.POWER_HIDE_MODE_APP_LIST"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    // Universal: App info page — user navigates to Battery saver from here
    intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })

    tryStartActivities(context, intents)
}

/** Try a list of intents in order, launch the first one that resolves */
private fun tryStartActivities(context: Context, intents: List<Intent>) {
    for (intent in intents) {
        try {
            if (intent.component != null) {
                val resolved = context.packageManager.resolveActivity(intent, 0)
                if (resolved != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            } else {
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) {
            continue
        }
    }
}

// ── SharedPreferences helper ──────────────────────────────────────────────────

fun isMiuiOnboardingNeeded(context: Context): Boolean {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val isMiui = manufacturer == "xiaomi" || manufacturer == "poco" || manufacturer == "redmi"
    if (!isMiui) return false

    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    return !prefs.getBoolean("miui_onboarding_complete", false)
}

fun markMiuiOnboardingComplete(context: Context) {
    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("miui_onboarding_complete", true)
        .apply()
}
