package com.soltini.app.homeautomation.ui

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.homeautomation.data.DeviceType
import com.soltini.app.ui.theme.AccentBlue
import com.soltini.app.ui.theme.AccentTeal
import com.soltini.app.ui.theme.DarkBackground
import com.soltini.app.ui.theme.DarkBorder
import com.soltini.app.ui.theme.DarkSurface
import com.soltini.app.ui.theme.DarkSurfaceVariant
import com.soltini.app.ui.theme.RedError
import com.soltini.app.ui.theme.TextPrimary
import com.soltini.app.ui.theme.TextSecondary

/**
 * AddDeviceScreen
 *
 * Dedicated full-screen wizard to register a new hardware device, validate ESP32 GPIO pins,
 * write to the unified Room database, and immediately generate the C++ firmware sketch.
 *
 * Connections:
 * - Backed by HomeAutomationViewModel (shared DeviceDao and DeviceEntity registry).
 * - On save, automatically invokes viewModel.generateFirmwareForBoard() and displays CodePreviewDialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(
    viewModel: HomeAutomationViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val boardIds by viewModel.boardIds.collectAsState()
    val generatedCode by viewModel.generatedCode.collectAsState()
    val activeBoardId by viewModel.activeBoardForCode.collectAsState()

    var deviceName by remember { mutableStateOf("") }
    var roomName by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(DeviceType.LIGHT) }
    var gpioPinText by remember { mutableStateOf("2") }
    var esp32ClientId by remember { mutableStateOf("esp32-living-room") }

    var typeExpanded by remember { mutableStateOf(false) }

    val pinNumber = gpioPinText.toIntOrNull()
    val isPinValid = pinNumber != null && pinNumber in 0..39
    val isFormValid = deviceName.isNotBlank() && roomName.isNotBlank() && isPinValid && esp32ClientId.isNotBlank()

    // ESP32 GPIO Warnings
    val pinWarning = when {
        pinNumber == null -> null
        pinNumber in 6..11 -> "⚠️ Pins 6-11 are reserved for internal SPI Flash memory. Choosing this will cause ESP32 boot loops."
        pinNumber in 34..39 -> "⚠️ Pins 34-39 are input-only on ESP32 (cannot drive relays or LEDs)."
        pinNumber == 0 || pinNumber == 2 || pinNumber == 15 -> "ℹ️ Pin $pinNumber is a strapping pin; may toggle briefly at power-on."
        else -> null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Add Smart Device",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Configure hardware pin & generate firmware",
                        color = AccentTeal,
                        fontSize = 12.sp
                    )
                }
            }

            // Form Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Device Name
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text("Device Name") },
                        placeholder = { Text("e.g. Ceiling Light, Fan, Kitchen Relay") },
                        singleLine = true,
                        colors = addTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Room Name
                    OutlinedTextField(
                        value = roomName,
                        onValueChange = { roomName = it },
                        label = { Text("Room Name") },
                        placeholder = { Text("e.g. Living Room, Master Bedroom") },
                        singleLine = true,
                        colors = addTextFieldColors(),
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
                            label = { Text("Device Classification") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                            colors = addTextFieldColors(),
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

                    // GPIO Pin
                    OutlinedTextField(
                        value = gpioPinText,
                        onValueChange = { if (it.length <= 2) gpioPinText = it.filter { c -> c.isDigit() } },
                        label = { Text("ESP32 GPIO Pin (0-39)") },
                        placeholder = { Text("e.g. 2, 4, 16, 17, 18, 19, 21, 22") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = !isPinValid,
                        colors = addTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (pinWarning != null) {
                        Text(
                            text = pinWarning,
                            color = if (pinNumber in 6..11 || pinNumber in 34..39) RedError else AccentTeal,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }

                    // ESP32 Client ID / Board Name
                    OutlinedTextField(
                        value = esp32ClientId,
                        onValueChange = { esp32ClientId = it },
                        label = { Text("ESP32 Board ID / Group") },
                        placeholder = { Text("e.g. esp32-living-room") },
                        singleLine = true,
                        colors = addTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Existing Boards Selector
                    if (boardIds.isNotEmpty()) {
                        Text(
                            text = "Existing Boards:",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            boardIds.forEach { bid ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (esp32ClientId == bid) AccentBlue.copy(alpha = 0.3f) else DarkSurfaceVariant)
                                        .clickable { esp32ClientId = bid }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        bid,
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Save & Generate Button
            Button(
                onClick = {
                    if (isFormValid) {
                        viewModel.saveDevice(deviceName, roomName, selectedType, pinNumber!!, esp32ClientId) { saved ->
                            viewModel.generateFirmwareForBoard(saved.esp32ClientId)
                        }
                    }
                },
                enabled = isFormValid,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Device & Generate Firmware", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // Code preview dialog when generated
    if (generatedCode != null && activeBoardId != null) {
        CodePreviewDialog(
            boardId = activeBoardId!!,
            code = generatedCode!!,
            onDismiss = {
                viewModel.dismissCodeDialog()
                onNavigateBack()
            }
        )
    }
}

@Composable
private fun addTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentBlue,
    unfocusedBorderColor = DarkBorder,
    focusedLabelColor = AccentBlue,
    unfocusedLabelColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = AccentBlue
)
