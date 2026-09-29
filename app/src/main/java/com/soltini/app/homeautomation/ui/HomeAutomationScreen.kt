package com.soltini.app.homeautomation.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ModeFanOff
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.homeautomation.data.DeviceEntity
import com.soltini.app.homeautomation.data.DeviceType
import com.soltini.app.homeautomation.mqtt.MqttConnectionStatus
import com.soltini.app.ui.theme.AccentBlue
import com.soltini.app.ui.theme.AccentIndigo
import com.soltini.app.ui.theme.AccentPink
import com.soltini.app.ui.theme.AccentPurple
import com.soltini.app.ui.theme.AccentTeal
import com.soltini.app.ui.theme.DarkBackground
import com.soltini.app.ui.theme.DarkBorder
import com.soltini.app.ui.theme.DarkSurface
import com.soltini.app.ui.theme.DarkSurfaceVariant
import com.soltini.app.ui.theme.GreenLive
import com.soltini.app.ui.theme.RedError
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary

/**
 * HomeAutomationScreen
 *
 * Central UI hub for managing ESP32 smart home devices, broker credentials,
 * live device controls, and on-device C++ sketch compilation/export.
 *
 * Connections:
 * - Backed by HomeAutomationViewModel which accesses the unified Room DeviceEntity table.
 * - Displays CodePreviewDialog when firmware is generated.
 * - Features FAB for opening AddDeviceDialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAutomationScreen(
    viewModel: HomeAutomationViewModel,
    onBack: () -> Unit,
    onNavigateToAddDevice: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val boardIds by viewModel.boardIds.collectAsState()
    val liveStates by viewModel.liveStates.collectAsState()
    val mqttStatus by viewModel.mqttStatus.collectAsState()
    val mqttStatusMsg by viewModel.mqttStatusMessage.collectAsState()
    val generatedCode by viewModel.generatedCode.collectAsState()
    val activeBoardId by viewModel.activeBoardForCode.collectAsState()
    val userMsg by viewModel.userMessage.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(userMsg) {
        userMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearUserMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = {
                        if (onNavigateToAddDevice != null) {
                            onNavigateToAddDevice()
                        } else {
                            showAddDialog = true
                        }
                    },
                    containerColor = AccentBlue,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Device")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Top Bar ──────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Smart Home",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ESP32 Mesh & Gemini Live",
                            color = AccentTeal,
                            fontSize = 12.sp
                        )
                    }
                }

                // MQTT Connection Status Pill
                MqttStatusBadge(
                    status = mqttStatus,
                    statusText = mqttStatusMsg,
                    onRetry = { viewModel.retryMqttConnection() }
                )
            }

            // ── Tabs ─────────────────────────────────────────────────────────────
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurface,
                contentColor = TextPrimary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = AccentBlue
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Devices (${devices.size})", fontWeight = FontWeight.Medium) },
                    icon = { Icon(Icons.Default.DeviceHub, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("WiFi & Broker", fontWeight = FontWeight.Medium) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) }
                )
            }

            // ── Tab Content ───────────────────────────────────────────────────────
            if (selectedTab == 0) {
                DeviceListTab(
                    devices = devices,
                    liveStates = liveStates,
                    onToggle = { viewModel.toggleDevice(it) },
                    onDelete = { viewModel.deleteDevice(it) },
                    onGenerateCode = { boardId -> viewModel.generateFirmwareForBoard(boardId) },
                    onAddNew = { showAddDialog = true }
                )
            } else {
                BrokerConfigTab(
                    viewModel = viewModel
                )
            }
        }
    }

    // Add Device Dialog / Wizard
    if (showAddDialog) {
        AddDeviceDialog(
            existingBoardIds = boardIds,
            onDismiss = { showAddDialog = false },
            onSave = { name, room, type, pin, boardId ->
                viewModel.saveDevice(name, room, type, pin, boardId) { savedDevice ->
                    showAddDialog = false
                    // Trigger firmware generation immediately after save
                    viewModel.generateFirmwareForBoard(savedDevice.esp32ClientId)
                }
            }
        )
    }

    // Generated Code Preview Dialog
    if (generatedCode != null && activeBoardId != null) {
        CodePreviewDialog(
            boardId = activeBoardId!!,
            code = generatedCode!!,
            onDismiss = { viewModel.dismissCodeDialog() }
        )
    }
}

/**
 * Status indicator pill showing MQTT broker health.
 */
@Composable
private fun MqttStatusBadge(
    status: MqttConnectionStatus,
    statusText: String,
    onRetry: () -> Unit
) {
    val (bgColor, dotColor, label) = when (status) {
        MqttConnectionStatus.CONNECTED -> Triple(GreenLive.copy(alpha = 0.15f), GreenLive, "MQTT Online")
        MqttConnectionStatus.CONNECTING -> Triple(AccentBlue.copy(alpha = 0.15f), AccentBlue, "Connecting...")
        MqttConnectionStatus.RECONNECTING -> Triple(AccentPink.copy(alpha = 0.15f), AccentPink, "Reconnecting")
        MqttConnectionStatus.DISCONNECTED -> Triple(DarkSurfaceVariant, TextSecondary, "Disconnected")
        MqttConnectionStatus.ERROR -> Triple(RedError.copy(alpha = 0.15f), RedError, "Offline")
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(1.dp, dotColor.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .clickable(enabled = status == MqttConnectionStatus.ERROR || status == MqttConnectionStatus.DISCONNECTED) {
                onRetry()
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = dotColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        if (status == MqttConnectionStatus.ERROR || status == MqttConnectionStatus.DISCONNECTED) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = dotColor, modifier = Modifier.size(12.dp))
        }
    }
}

/**
 * Devices tab: displays cards grouped by ESP32 board or room.
 */
@Composable
private fun DeviceListTab(
    devices: List<DeviceEntity>,
    liveStates: Map<String, String>,
    onToggle: (DeviceEntity) -> Unit,
    onDelete: (DeviceEntity) -> Unit,
    onGenerateCode: (String) -> Unit,
    onAddNew: () -> Unit
) {
    if (devices.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(AccentBlue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No Devices Configured Yet",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap '+ Add Device' below to add a relay, light, or fan and auto-generate ESP32 firmware.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onAddNew,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add First Device")
                }
            }
        }
        return
    }

    // Group devices by ESP32 Board ID
    val groupedByBoard = devices.groupBy { it.esp32ClientId }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        groupedByBoard.forEach { (boardId, boardDevices) ->
            item(key = "board_$boardId") {
                BoardHeaderCard(
                    boardId = boardId,
                    deviceCount = boardDevices.size,
                    onGenerateCode = { onGenerateCode(boardId) }
                )
            }

            items(boardDevices, key = { it.id }) { device ->
                val state = liveStates[device.mqttTopicBase] ?: "OFF"
                val isOn = state.equals("ON", ignoreCase = true)

                DeviceItemCard(
                    device = device,
                    isOn = isOn,
                    onToggle = { onToggle(device) },
                    onDelete = { onDelete(device) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(72.dp)) // Floating button padding
        }
    }
}

/**
 * Header card for a physical ESP32 board grouping with one-click code gen.
 */
@Composable
private fun BoardHeaderCard(
    boardId: String,
    deviceCount: Int,
    onGenerateCode: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceVariant.copy(alpha = 0.6f))
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.DeviceHub,
                contentDescription = null,
                tint = AccentIndigo,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = boardId,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "$deviceCount pins mapped",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Button(
            onClick = onGenerateCode,
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentIndigo.copy(alpha = 0.2f),
                contentColor = AccentIndigo
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text("Firmware", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Individual device card showing type icon, GPIO pin, room, topic, and switch.
 */
@Composable
private fun DeviceItemCard(
    device: DeviceEntity,
    isOn: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isOn) AccentBlue.copy(alpha = 0.5f) else DarkBorder,
                RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Device Type Icon Container
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isOn) AccentBlue.copy(alpha = 0.25f) else DarkSurfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getDeviceIcon(device.deviceType),
                        contentDescription = null,
                        tint = if (isOn) AccentBlue else TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.deviceName,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AccentTeal.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "GPIO ${device.gpioPin}",
                                color = AccentTeal,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = "${device.roomName} • ${device.mqttTopicBase}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = isOn,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentBlue,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = DarkSurfaceVariant
                    )
                )

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete device",
                        tint = RedError.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun getDeviceIcon(type: DeviceType): ImageVector {
    return when (type) {
        DeviceType.LIGHT -> Icons.Default.Lightbulb
        DeviceType.FAN -> Icons.Default.ModeFanOff
        DeviceType.LOCK -> Icons.Default.Lock
        DeviceType.RELAY -> Icons.Default.Power
        DeviceType.CUSTOM -> Icons.Default.ElectricBolt
    }
}

/**
 * Add Device Dialog form with real-time GPIO pin validation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceDialog(
    existingBoardIds: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, room: String, type: DeviceType, pin: Int, boardId: String) -> Unit
) {
    var deviceName by remember { mutableStateOf("") }
    var roomName by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(DeviceType.LIGHT) }
    var gpioPinText by remember { mutableStateOf("2") }
    var esp32ClientId by remember { mutableStateOf(existingBoardIds.firstOrNull() ?: "esp32-living-room") }

    var typeExpanded by remember { mutableStateOf(false) }
    var boardExpanded by remember { mutableStateOf(false) }

    val pinNumber = gpioPinText.toIntOrNull()
    val isPinValid = pinNumber != null && pinNumber in 0..39
    val isNameValid = deviceName.isNotBlank()
    val isRoomValid = roomName.isNotBlank()
    val isBoardValid = esp32ClientId.isNotBlank()
    val isFormValid = isNameValid && isRoomValid && isPinValid && isBoardValid

    // ESP32 GPIO Warnings (Pins 6-11 used for SPI flash, 34-39 are input only)
    val pinWarning = when {
        pinNumber == null -> null
        pinNumber in 6..11 -> "⚠️ Pins 6-11 are reserved for ESP32 internal flash memory. Do not use."
        pinNumber in 34..39 -> "⚠️ Pins 34-39 are input-only (cannot drive output relays)."
        pinNumber == 0 || pinNumber == 2 || pinNumber == 15 -> "ℹ️ Pin $pinNumber is a strapping pin; relay might pulse at boot."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, contentDescription = null, tint = AccentBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Smart Device", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Device Name
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text("Device Name") },
                    placeholder = { Text("e.g. Ceiling Light, Desk Fan") },
                    singleLine = true,
                    colors = textFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Room Name
                OutlinedTextField(
                    value = roomName,
                    onValueChange = { roomName = it },
                    label = { Text("Room Name") },
                    placeholder = { Text("e.g. Living Room, Bedroom") },
                    singleLine = true,
                    colors = textFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Device Type Dropdown
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = !typeExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedType.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Device Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        colors = textFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false },
                        modifier = Modifier.background(DarkSurfaceVariant)
                    ) {
                        DeviceType.values().forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name, color = TextPrimary) },
                                onClick = {
                                    selectedType = type
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                // GPIO Pin input (0-39)
                OutlinedTextField(
                    value = gpioPinText,
                    onValueChange = { if (it.length <= 2) gpioPinText = it.filter { char -> char.isDigit() } },
                    label = { Text("ESP32 GPIO Pin (0-39)") },
                    placeholder = { Text("e.g. 2, 4, 16, 17, 18, 19, 21, 22") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = !isPinValid,
                    colors = textFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                if (pinWarning != null) {
                    Text(
                        text = pinWarning,
                        color = if (pinNumber in 6..11 || pinNumber in 34..39) RedError else AccentTeal,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }

                // ESP32 Board Client ID
                OutlinedTextField(
                    value = esp32ClientId,
                    onValueChange = { esp32ClientId = it },
                    label = { Text("ESP32 Board ID (Group)") },
                    placeholder = { Text("e.g. esp32-living-room") },
                    singleLine = true,
                    colors = textFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Quick selector for existing boards
                if (existingBoardIds.isNotEmpty()) {
                    Text(
                        text = "Or pick existing board:",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        existingBoardIds.take(3).forEach { bid ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (esp32ClientId == bid) AccentBlue.copy(alpha = 0.25f) else DarkSurfaceVariant)
                                    .clickable { esp32ClientId = bid }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(bid, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isFormValid) {
                        onSave(deviceName, roomName, selectedType, pinNumber!!, esp32ClientId)
                    }
                },
                enabled = isFormValid,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save & Generate Code")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

/**
 * Broker and WiFi configuration tab with secure store integration.
 */
@Composable
private fun BrokerConfigTab(
    viewModel: HomeAutomationViewModel
) {
    val store = viewModel.credStore

    var wifiSsid by remember { mutableStateOf(store.wifiSsid) }
    var wifiPass by remember { mutableStateOf(store.wifiPass) }
    var mqttHost by remember { mutableStateOf(store.mqttHost) }
    var mqttPort by remember { mutableStateOf(store.mqttPort.toString()) }
    var mqttUser by remember { mutableStateOf(store.mqttUser) }
    var mqttPass by remember { mutableStateOf(store.mqttPass) }

    var showWifiPass by remember { mutableStateOf(false) }
    var showMqttPass by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // WiFi Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wifi, contentDescription = null, tint = AccentTeal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Target 2.4 GHz WiFi", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = "ESP32 microcontrollers only connect to 2.4 GHz WiFi bands.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                OutlinedTextField(
                    value = wifiSsid,
                    onValueChange = { wifiSsid = it },
                    label = { Text("WiFi SSID") },
                    colors = textFieldColors(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = wifiPass,
                    onValueChange = { wifiPass = it },
                    label = { Text("WiFi Password") },
                    colors = textFieldColors(),
                    singleLine = true,
                    visualTransformation = if (showWifiPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showWifiPass = !showWifiPass }) {
                            Icon(if (showWifiPass) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, tint = TextSecondary)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // MQTT Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = AccentIndigo)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("HiveMQ Cloud / MQTT Broker", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = "Encrypted in Android Keystore. Used for TLS voice control & live updates.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                OutlinedTextField(
                    value = mqttHost,
                    onValueChange = { mqttHost = it },
                    label = { Text("MQTT Broker Host (TLS)") },
                    placeholder = { Text("e.g. xxxxxx.s1.eu.hivemq.cloud") },
                    colors = textFieldColors(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = mqttPort,
                    onValueChange = { mqttPort = it },
                    label = { Text("Port (8883 for TLS)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = textFieldColors(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = mqttUser,
                    onValueChange = { mqttUser = it },
                    label = { Text("MQTT Username") },
                    colors = textFieldColors(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = mqttPass,
                    onValueChange = { mqttPass = it },
                    label = { Text("MQTT Password") },
                    colors = textFieldColors(),
                    singleLine = true,
                    visualTransformation = if (showMqttPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showMqttPass = !showMqttPass }) {
                            Icon(if (showMqttPass) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, tint = TextSecondary)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Save Button
        Button(
            onClick = {
                val portInt = mqttPort.toIntOrNull() ?: 8883
                viewModel.saveCredentials(wifiSsid, wifiPass, mqttHost, portInt, mqttUser, mqttPass)
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save & Connect Broker", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentBlue,
    unfocusedBorderColor = DarkBorder,
    focusedLabelColor = AccentBlue,
    unfocusedLabelColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = AccentBlue
)
