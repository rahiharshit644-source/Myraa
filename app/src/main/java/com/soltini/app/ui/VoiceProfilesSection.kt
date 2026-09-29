package com.soltini.app.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.security.*
import com.soltini.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private val AmberWarning = Color(0xFFF59E0B)

/**
 * VoiceProfilesSection
 *
 * Dedicated Material 3 interface for Multi-User Voice Profiles,
 * Profile-Scoped Access Matrices, and Emergency Biometric Controls.
 */
@Composable
fun VoiceProfilesSection(context: Context) {
    val profileManager = remember { VoiceProfileManager.getInstance(context) }
    var profiles by remember { mutableStateOf(profileManager.getAllProfiles()) }
    var isEmergencyDisabled by remember { mutableStateOf(profileManager.isEmergencyDisabled()) }
    var voiceCommandsActive by remember { mutableStateOf(profileManager.areVoiceCommandsActive()) }
    var showAddProfileDialog by remember { mutableStateOf(false) }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }
    var showAuditLogs by remember { mutableStateOf(false) }
    var auditLogs by remember { mutableStateOf(profileManager.getAuditLogs()) }

    fun refreshState() {
        profiles = profileManager.getAllProfiles()
        isEmergencyDisabled = profileManager.isEmergencyDisabled()
        voiceCommandsActive = profileManager.areVoiceCommandsActive()
        auditLogs = profileManager.getAuditLogs()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF6366F1).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = Color(0xFF818CF8),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Voice Profiles & Permission System",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        "Multi-user speaker verification, profile permissions & Keystore encryption",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Non-bypass Security Guarantee notice
            Surface(
                color = Color(0xFF1E2238),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF374151))
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = AmberWarning,
                        modifier = Modifier.size(16.dp).padding(top = 1.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Security Principle: Voice biometrics identifies the speaker to enforce Myra's internal application permissions. Myra never bypasses or attempts to unlock the Android system lock screen.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Profiles List Header + Add Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Enrolled Voice Profiles (${profiles.size})",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                TextButton(
                    onClick = { showAddProfileDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("Add Profile", fontSize = 12.sp, color = AccentBlue)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Display Profiles
            profiles.forEach { profile ->
                VoiceProfileItemCard(
                    profile = profile,
                    context = context,
                    onDelete = {
                        profileManager.deleteProfile(profile.id)
                        refreshState()
                    },
                    onEnroll = {
                        VoiceUnlockActivity.startEnrollment(context, profile.id)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Test Speaker ID Button
            OutlinedButton(
                onClick = {
                    VoiceUnlockActivity.startForBankingToggle(context)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF818CF8)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6366F1).copy(0.5f))
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Test Live Speaker Identification", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Emergency & Security Controls
            Text("Emergency & Safety Controls", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))

            // Emergency Disable Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Emergency Disable Myra", color = if (isEmergencyDisabled) AmberWarning else TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text("Instantly locks all actions and blocks all assistant voice commands", color = TextSecondary, fontSize = 10.sp)
                }
                Switch(
                    checked = isEmergencyDisabled,
                    onCheckedChange = { checked ->
                        profileManager.setEmergencyDisabled(checked)
                        refreshState()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = AmberWarning, checkedTrackColor = AmberWarning.copy(0.3f))
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Voice Commands Active Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Voice Commands Processing", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text("Toggle speech command processing without resetting enrolled models", color = TextSecondary, fontSize = 10.sp)
                }
                Switch(
                    checked = voiceCommandsActive,
                    onCheckedChange = { checked ->
                        profileManager.setVoiceCommandsActive(checked)
                        refreshState()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = AccentBlue, checkedTrackColor = AccentBlue.copy(0.3f))
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Lock / Wipe Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        profileManager.lockAllProfiles()
                        refreshState()
                    },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C324E))
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text("Lock Profiles", fontSize = 11.sp, color = Color.White)
                }

                Button(
                    onClick = { showWipeConfirmDialog = true },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A1E24))
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFFF8A80))
                    Spacer(Modifier.width(4.dp))
                    Text("Wipe Biometrics", fontSize = 11.sp, color = Color(0xFFFF8A80))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Audit Log Expandable Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF131726),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B3352))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFF818CF8), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Minimal Security Audit Log (${auditLogs.size})", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        IconButton(onClick = { showAuditLogs = !showAuditLogs }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                if (showAuditLogs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    }

                    AnimatedVisibility(visible = showAuditLogs) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            if (auditLogs.isEmpty()) {
                                Text("No voice authentication attempts logged yet.", color = TextSecondary, fontSize = 11.sp)
                            } else {
                                val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                                auditLogs.take(10).forEach { entry ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(if (entry.isSuccess) GreenLive else AmberWarning)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    "${entry.profileName ?: "Unknown"}: ${entry.actionType}",
                                                    color = TextPrimary,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 11.sp
                                                )
                                            }
                                            Text(
                                                entry.reason,
                                                color = TextSecondary,
                                                fontSize = 10.sp,
                                                maxLines = 1
                                            )
                                        }
                                        Text(
                                            sdf.format(Date(entry.timestamp)),
                                            color = Color.Gray,
                                            fontSize = 9.sp
                                        )
                                    }
                                    HorizontalDivider(color = Color(0xFF222842), thickness = 0.5.dp)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "🔒 Zero leakage: Voice recordings, embeddings, and message texts are NEVER recorded in logs.",
                                color = Color.Gray,
                                fontSize = 9.sp,
                                lineHeight = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog: Add New Profile
    if (showAddProfileDialog) {
        var newName by remember { mutableStateOf("") }
        var newRelationship by remember { mutableStateOf("Family Member") }

        AlertDialog(
            onDismissRequest = { showAddProfileDialog = false },
            title = { Text("Add Voice Profile", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "New profiles default strictly to Family/Restricted access. Private owner data is never exposed.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name (e.g. Papa, Rohan)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newRelationship,
                        onValueChange = { newRelationship = it },
                        label = { Text("Relationship / Role") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            profileManager.createProfile(newName, newRelationship, ProfileRole.FAMILY)
                            refreshState()
                            showAddProfileDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text("Create Profile")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddProfileDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // Dialog: Confirm Wipe Biometrics
    if (showWipeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmDialog = false },
            title = { Text("Wipe All Voice Biometrics?", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will delete all encrypted voice embeddings stored in the Android Keystore. Users will need to re-enroll with 10 phonetic sentences.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        profileManager.wipeAllBiometrics()
                        refreshState()
                        showWipeConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Wipe All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirmDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun VoiceProfileItemCard(
    profile: VoiceProfile,
    context: Context,
    onDelete: () -> Unit,
    onEnroll: () -> Unit
) {
    val isOwner = profile.role == ProfileRole.OWNER
    val roleColor = when (profile.role) {
        ProfileRole.OWNER -> GreenLive
        ProfileRole.FAMILY -> Color(0xFF818CF8)
        ProfileRole.GUEST -> AmberWarning
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF161B2E),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, roleColor.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(roleColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isOwner) Icons.Default.VerifiedUser else Icons.Default.Person,
                            contentDescription = null,
                            tint = roleColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = roleColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    profile.role.name,
                                    color = roleColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text("${profile.relationship} • ${if (profile.isEnrolled) "Enrolled (${profile.sampleCount}/10 samples)" else "Not Enrolled"}", color = TextSecondary, fontSize = 11.sp)
                    }
                }

                if (!isOwner) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Permission Summary Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PermissionBadge(
                    label = "Low Risk",
                    status = "ALLOW",
                    color = GreenLive
                )
                PermissionBadge(
                    label = "Medium Risk",
                    status = if (isOwner) "ALLOW" else "CONFIRM",
                    color = if (isOwner) GreenLive else Color(0xFF60A5FA)
                )
                PermissionBadge(
                    label = "Private Data / Vault",
                    status = if (isOwner) "BIOMETRIC" else "DENIED",
                    color = if (isOwner) AmberWarning else Color(0xFFEF4444)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Enroll / Re-enroll Button
            Button(
                onClick = onEnroll,
                modifier = Modifier.fillMaxWidth().height(34.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (profile.isEnrolled) Color(0xFF263254) else Color(0xFF4338CA)
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (profile.isEnrolled) "Re-enroll Voice (10 Phrases)" else "Enroll Voice (10 Phrases)",
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun PermissionBadge(label: String, status: String, color: Color) {
    Surface(
        color = Color(0xFF101322),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(0.4f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(label, color = Color.Gray, fontSize = 8.sp)
            Text(status, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}
