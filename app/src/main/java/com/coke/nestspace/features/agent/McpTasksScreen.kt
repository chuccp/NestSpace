package com.coke.nestspace.features.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.AppGraph
import com.coke.nestspace.agent.AssistantTaskCoordinator
import com.coke.nestspace.agent.AssistantTaskPrimaryAction
import com.coke.nestspace.agent.AssistantTaskUiState
import com.coke.nestspace.agent.assistantTaskGoalForDisplay
import com.coke.nestspace.core.time.formatShortDateTime
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.agent.task.OperationTaskRegistry

/** Unified task center for user-created and externally requested work. */
@Composable
internal fun McpTasksScreen(initialTaskId: String? = null) {
    val operationTasks by OperationTaskRegistry.tasksFlow.collectAsState()
    val tasks = remember(operationTasks) { AssistantTaskCoordinator.list() }
    val active = tasks.filter {
        it.uiState == AssistantTaskUiState.RUNNING ||
            it.uiState == AssistantTaskUiState.PAUSED ||
            it.uiState == AssistantTaskUiState.NEEDS_ATTENTION ||
            it.uiState == AssistantTaskUiState.CANCELLING
    }
    val waiting = tasks.filter { it.uiState == AssistantTaskUiState.WAITING }
        .sortedBy { it.queuePosition }
    val finished = tasks.filter { it.uiState == AssistantTaskUiState.FINISHED }
        .sortedByDescending { it.updatedAt }
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                AppLanguage.tr(
                    "有什么需要做的，交给 AI 助手就行。任务会按顺序完成，你也可以暂停或取消。",
                    "Tell the assistant what you need. Tasks finish one at a time, and you can " +
                        "pause or cancel them."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        taskSection("正在处理", "In progress", active, initialTaskId)
        taskSection("排队中", "Waiting", waiting, initialTaskId)
        taskSection("最近完成", "Recently finished", finished, initialTaskId)
        if (tasks.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        AppLanguage.tr("还没有任务", "No tasks yet"),
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.taskSection(
    zh: String,
    en: String,
    tasks: List<AssistantTaskCoordinator.TaskView>,
    initiallyExpandedTaskId: String?
) {
    if (tasks.isEmpty()) return
    item(key = "section:$zh") {
        Text(
            AppLanguage.tr(zh, en),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
    items(tasks, key = { "${zh}:${it.id}" }) { task ->
        AssistantTaskCard(
            task = task,
            initiallyExpanded = task.id == initiallyExpandedTaskId
        )
    }
}

@Composable
private fun AssistantTaskCard(
    task: AssistantTaskCoordinator.TaskView,
    initiallyExpanded: Boolean
) {
    val context = LocalContext.current
    var expanded by remember(task.id, initiallyExpanded) {
        mutableStateOf(initiallyExpanded)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.AccountTree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        assistantTaskGoalForDisplay(task.goal),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) 8 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(
                            if (task.source == "mcp") {
                                AppLanguage.tr("外部任务", "External task")
                            } else {
                                AppLanguage.tr("AI 助手", "AI assistant")
                            },
                            uiStateLabel(task.uiState),
                            if (task.queuePosition > 0) {
                                AppLanguage.tr(
                                    "前面还有 ${(task.queuePosition - 1).coerceAtLeast(0)} 个任务",
                                    "${(task.queuePosition - 1).coerceAtLeast(0)} ahead"
                                )
                            } else {
                                null
                            },
                            formatShortDateTime(task.createdAt)
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val detail = when {
                task.result?.isNotBlank() == true -> task.result
                task.error?.isNotBlank() == true -> task.error
                else -> task.progress
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) {
                        if (task.uiState == AssistantTaskUiState.FINISHED) 60 else 20
                    } else {
                        3
                    },
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (task.uiState == AssistantTaskUiState.CANCELLING) {
                Text(
                    AppLanguage.tr(
                        "正在取消，等待资源清理…",
                        "Cancelling; waiting for cleanup…"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (task.primaryAction != AssistantTaskPrimaryAction.NONE || task.canCancel) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            onClick = { expanded = true }
                        ) {
                            Text(AppLanguage.tr("处理", "Handle"))
                        }
                        AssistantTaskPrimaryAction.VIEW_RESULT -> TextButton(
                            onClick = { expanded = true }
                        ) {
                            Text(AppLanguage.tr("查看结果", "View result"))
                        }
                        AssistantTaskPrimaryAction.RETRY_CLEANUP,
                        AssistantTaskPrimaryAction.NONE -> Unit
                    }
                    if (task.canCancel) {
                        OutlinedButton(
                            onClick = { AssistantTaskCoordinator.cancel(task.id) }
                        ) {
                            Text(AppLanguage.tr("取消", "Cancel"))
                        }
                    }
                }
            }
        }
    }
}

private fun uiStateLabel(state: AssistantTaskUiState): String = when (state) {
    AssistantTaskUiState.WAITING -> AppLanguage.tr("等待中", "Waiting")
    AssistantTaskUiState.RUNNING -> AppLanguage.tr("执行中", "Running")
    AssistantTaskUiState.PAUSED -> AppLanguage.tr("已暂停", "Paused")
    AssistantTaskUiState.NEEDS_ATTENTION -> AppLanguage.tr("需要处理", "Needs attention")
    AssistantTaskUiState.CANCELLING -> AppLanguage.tr("取消中", "Cancelling")
    AssistantTaskUiState.FINISHED -> AppLanguage.tr("已结束", "Finished")
}
