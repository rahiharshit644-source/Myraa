package com.soltini.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soltini.app.learning.ExperienceLearningEngine
import com.soltini.app.learning.ExperienceMemory
import com.soltini.app.scheduler.ScheduledTask
import com.soltini.app.scheduler.ScheduledTaskManager
import com.soltini.app.scheduler.ScheduledTaskStatus
import com.soltini.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * ScheduledTasksScreen
 *
 * Dedicated control dashboard for Future Scheduled Tasks and Learned Skills / Experiences.
 * Allows viewing, rescheduling, cancelling, retrying, and inspecting verified execution results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheduler = remember { ScheduledTaskManager.getInstance(context) }
    val learningEngine = remember { ExperienceLearningEngine.getInstance(context) }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Tasks, 1: Experiences
    var tasks by remember { mutableStateOf<List<ScheduledTask>>(emptyList()) }
    var experiences by remember { mutableStateOf<List<ExperienceMemory>>(emptyList()) }
    var selectedTaskForDetails by remember { mutableStateOf<ScheduledTask?>(null) }
    var taskToReschedule by remember { mutableStateOf<ScheduledTask?>(null) }

    fun refreshData() {
        tasks = scheduler.getAllTasks()
        experiences = learningEngine.getAllLearnedExperiences()
    }

    LaunchedEffect(Unit) {
        refreshData()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedTab == 0) "Scheduled Tasks" else "Learned Experiences",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
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
                    IconButton(onClick = { refreshData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentTeal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Tab Selector
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurface,
                contentColor = AccentBlue,
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
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scheduled (${tasks.count { it.isPending }})")
                        }
                    },
                    selectedContentColor = AccentBlue,
                    unselectedContentColor = TextSecondary
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Learned Skills (${experiences.size})")
                        }
                    },
                    selectedContentColor = AccentPurple,
                    unselectedContentColor = TextSecondary
                )
            }

            if (selectedTab == 0) {
                // Scheduled Tasks List
                if (tasks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Schedule, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("No scheduled tasks yet.", color = TextSecondary, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("Say: \"MYRA, shaam 7 baje Rahul ko call karna\"", color = TextSecondary.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(tasks, key = { it.id }) { task ->
                            ScheduledTaskCard(
                                task = task,
                                onCancel = {
                                    scheduler.cancelTask(task.id)
                                    refreshData()
                                },
                                onRetry = {
                                    scheduler.retryTask(task.id)
                                    refreshData()
                                },
                                onDelete = {
                                    scheduler.deleteTask(task.id)
                                    refreshData()
                                },
                                onReschedule = {
                                    taskToReschedule = task
                                },
                                onClickDetails = {
                                    selectedTaskForDetails = task
                                }
                            )
                        }
                    }
                }
            } else {
                // Learned Experiences List
                if (experiences.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("No learned patterns yet.", color = TextSecondary, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("MYRA learns autonomously from tasks and user corrections.", color = TextSecondary.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(experiences, key = { it.id }) { exp ->
                            LearnedExperienceCard(
                                experience = exp,
                                onDelete = {
                                    learningEngine.deleteExperience(exp.id)
                                    refreshData()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Task Details Dialog
    if (selectedTaskForDetails != null) {
        val t = selectedTaskForDetails!!
        AlertDialog(
            onDismissRequest = { selectedTaskForDetails = null },
            containerColor = DarkSurface,
            title = { Text(t.title, color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Command: \"${t.command}\"", color = TextSecondary, fontSize = 13.sp)
                    Text("Status: ${t.status.name}", color = getStatusColor(t.status), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Scheduled At: ${formatTimestamp(t.scheduledAt)}", color = TextSecondary, fontSize = 13.sp)
                    if (t.parameters.isNotEmpty()) {
                        Text("Parameters:", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        for ((k, v) in t.parameters) {
                            Text("  • $k: $v", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                    if (!t.result.isNullOrBlank()) {
                        Text("Execution Result:", color = GreenLive, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(t.result!!, color = TextPrimary, fontSize = 12.sp)
                    }
                    if (!t.error.isNullOrBlank()) {
                        Text("Failure Reason:", color = RedError, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(t.error!!, color = RedError.copy(alpha = 0.9f), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedTaskForDetails = null }) {
                    Text("Close", color = AccentBlue)
                }
            }
        )
    }

    // Reschedule Dialog (+1 Hour or +1 Day)
    if (taskToReschedule != null) {
        val t = taskToReschedule!!
        AlertDialog(
            onDismissRequest = { taskToReschedule = null },
            containerColor = DarkSurface,
            title = { Text("Reschedule Task", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select a new execution time for \"${t.title}\":", color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val newTime = System.currentTimeMillis() + 30 * 60 * 1000L
                            scheduler.rescheduleTask(t.id, newTime)
                            taskToReschedule = null
                            refreshData()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("In 30 Minutes", color = TextPrimary)
                    }
                    Button(
                        onClick = {
                            val newTime = System.currentTimeMillis() + 60 * 60 * 1000L
                            scheduler.rescheduleTask(t.id, newTime)
                            taskToReschedule = null
                            refreshData()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("In 1 Hour", color = TextPrimary)
                    }
                    Button(
                        onClick = {
                            val newTime = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
                            scheduler.rescheduleTask(t.id, newTime)
                            taskToReschedule = null
                            refreshData()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Tomorrow Same Time", color = TextPrimary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { taskToReschedule = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
fun ScheduledTaskCard(
    task: ScheduledTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onReschedule: () -> Unit,
    onClickDetails: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, DarkBorder, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (task.taskType) {
                        "CALL" -> Icons.Default.Phone
                        "SEND_MESSAGE" -> Icons.Default.Send
                        "REMINDER" -> Icons.Default.Alarm
                        else -> Icons.Default.PlayArrow
                    }
                    Icon(icon, contentDescription = null, tint = AccentTeal, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = task.title,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusBadge(task.status)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "Command: \"${task.command}\"",
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Event, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = formatTimestamp(task.scheduledAt),
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            if (!task.result.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Result: ${task.result}",
                    color = GreenLive,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!task.error.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Error: ${task.error}",
                    color = RedError,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onClickDetails, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("Details", fontSize = 12.sp, color = AccentBlue)
                }
                if (task.isPending) {
                    TextButton(onClick = onReschedule, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("Reschedule", fontSize = 12.sp, color = AccentTeal)
                    }
                    TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("Cancel", fontSize = 12.sp, color = AmberConnecting)
                    }
                } else if (task.status == ScheduledTaskStatus.FAILED) {
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("Retry", fontSize = 12.sp, color = AccentTeal)
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun LearnedExperienceCard(
    experience: ExperienceMemory,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, DarkBorder, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lightbulb, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = experience.taskType,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (experience.userConfirmed) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentTeal.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("User Confirmed", color = AccentTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "Goal: \"${experience.goal}\"",
                color = TextSecondary,
                fontSize = 12.sp
            )

            if (experience.successfulMethod.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Winning Method: ${experience.successfulMethod}",
                    color = TextPrimary,
                    fontSize = 12.sp
                )
            }

            if (experience.toolsUsed.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tools: ${experience.toolsUsed.joinToString(" → ")}",
                    color = AccentBlue,
                    fontSize = 12.sp
                )
            }

            if (experience.failedMethods.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Avoided Failures: ${experience.failedMethods.joinToString(", ")}",
                    color = RedError.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Confidence: ${(experience.confidence * 100).toInt()}% • Successes: ${experience.successCount} • Failures: ${experience.failureCount}",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: ScheduledTaskStatus) {
    val color = getStatusColor(status)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(status.name, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun getStatusColor(status: ScheduledTaskStatus): Color = when (status) {
    ScheduledTaskStatus.SCHEDULED, ScheduledTaskStatus.WAITING -> AccentTeal
    ScheduledTaskStatus.EXECUTING -> AccentBlue
    ScheduledTaskStatus.COMPLETED -> GreenLive
    ScheduledTaskStatus.FAILED -> RedError
    ScheduledTaskStatus.CANCELLED, ScheduledTaskStatus.SKIPPED -> TextSecondary
    ScheduledTaskStatus.WAITING_FOR_CONFIRMATION -> AmberConnecting
}

private fun formatTimestamp(timeMs: Long): String {
    val sdf = SimpleDateFormat("h:mm a, d MMM yyyy", Locale.getDefault())
    return sdf.format(Date(timeMs))
}
