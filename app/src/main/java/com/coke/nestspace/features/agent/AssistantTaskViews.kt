package com.coke.nestspace.features.agent

import com.coke.nestspace.core.i18n.AppLanguage
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.coke.nestspace.agent.AssistantTaskCoordinator
import com.coke.nestspace.agent.AssistantTaskPrimaryAction
import com.coke.nestspace.agent.AssistantTaskUiState
import com.coke.nestspace.agent.assistantTaskGoalForDisplay
import com.coke.nestspace.AppGraph
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun AssistantTaskDialog(
    tasks: List<AssistantTaskCoordinator.TaskView>,
    onDismiss: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val context = LocalContext.current
    val activeTasks = tasks.filter { it.uiState != AssistantTaskUiState.WAITING }
    val waitingTasks = tasks.filter { it.uiState == AssistantTaskUiState.WAITING }
        .sortedBy { it.queuePosition }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp
        ) {
        Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    AppLanguage.tr("AI 任务", "AI tasks"),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onDismiss) {
                    Text(AppLanguage.tr("关闭", "Close"))
                }
            }
            if (tasks.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        AppLanguage.tr(
                            "现在没有后台任务。交给我一件事后，可以在这里看进度。",
                            "No background tasks right now. Ask me to do something and " +
                                "you can follow its progress here."
                        ),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    if (activeTasks.isNotEmpty()) {
                        item(key = "active-title") {
                            TaskSectionTitle(
                                AppLanguage.tr("正在处理", "In progress")
                            )
                        }
                        items(activeTasks, key = { it.id }) { task ->
                            AssistantTaskPopupCard(task, onOpenHistory)
                        }
                    }
                    if (waitingTasks.isNotEmpty()) {
                        item(key = "waiting-title") {
                            TaskSectionTitle(
                                AppLanguage.tr("排队中", "Waiting")
                            )
                        }
                        items(waitingTasks, key = { it.id }) { task ->
                            AssistantTaskPopupCard(task, onOpenHistory)
                        }
                    }
                }
            }
            TextButton(
                onClick = onOpenHistory,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(AppLanguage.tr("查看全部任务", "View all tasks"))
            }
        }
    }
}
}

@Composable
internal fun AssistantTaskSummary(
    tasks: List<AssistantTaskCoordinator.TaskView>,
    onOpenTasks: () -> Unit
) {
    val current = tasks.firstOrNull {
        it.uiState == AssistantTaskUiState.RUNNING
    } ?: tasks.firstOrNull()
        ?: return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        ),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    if (AppLanguage.isChinese()) {
                        "正在处理 · 共 ${tasks.size} 个任务"
                    } else {
                        "Working · ${tasks.size} " +
                            if (tasks.size == 1) "task" else "tasks"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    assistantTaskGoalForDisplay(current.goal),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onOpenTasks) {
                Text(AppLanguage.tr("查看全部", "View all"))
            }
        }
    }
}

@Composable
internal fun TaskSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun AssistantTaskPopupCard(
    task: AssistantTaskCoordinator.TaskView,
    onOpenHistory: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        assistantTaskUiLabel(task.uiState),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (task.uiState == AssistantTaskUiState.CANCELLING) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        assistantTaskGoalForDisplay(task.goal),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (task.queuePosition > 0) {
                    Text(
                        AppLanguage.tr(
                            "第 ${task.queuePosition} 个",
                            "#${task.queuePosition}"
                        ),
                        modifier = Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (task.primaryAction != AssistantTaskPrimaryAction.NONE || task.canCancel) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (task.primaryAction) {
                        AssistantTaskPrimaryAction.PAUSE -> TextButton(
                            onClick = { AssistantTaskCoordinator.pause(task.id) }
                        ) {
                            Text(AppLanguage.tr("暂停", "Pause"))
                        }
                        AssistantTaskPrimaryAction.RESUME -> TextButton(
                            onClick = {
                                AssistantTaskCoordinator.resume(
                                    context,
                                    AppGraph.registry(context),
                                    task.id
                                )
                            }
                        ) {
                            Text(AppLanguage.tr("继续", "Resume"))
                        }
                        AssistantTaskPrimaryAction.HANDLE -> TextButton(
                            onClick = onOpenHistory
                        ) {
                            Text(AppLanguage.tr("去处理", "Handle"))
                        }
                        AssistantTaskPrimaryAction.VIEW_RESULT -> Unit
                        AssistantTaskPrimaryAction.RETRY_CLEANUP,
                        AssistantTaskPrimaryAction.NONE -> Unit
                    }
                    if (task.canCancel) {
                        TextButton(
                            onClick = { AssistantTaskCoordinator.cancel(task.id) },
                            enabled = task.uiState != AssistantTaskUiState.CANCELLING
                        ) {
                            Text(
                                AppLanguage.tr("取消任务", "Cancel task"),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
