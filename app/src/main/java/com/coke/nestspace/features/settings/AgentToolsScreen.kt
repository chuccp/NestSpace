package com.coke.nestspace.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.agent.AgentToolInfo
import com.coke.nestspace.core.i18n.AppLanguage

@Composable
internal fun AgentToolsScreen(
    tools: List<AgentToolInfo>,
    loading: Boolean,
    onToggleTool: (String, Boolean) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        listState.scrollToItem(0)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        AppLanguage.tr("插件工具", "Plugin tools"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${tools.count { it.enabled }}/${tools.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    AppLanguage.tr(
                        "仅显示已启用插件动态注册的 Agent 工具。内置工具由宿主管理，" +
                            "不会出现在这里。",
                        "Only Agent tools dynamically registered by enabled plugins are shown. " +
                            "Built-in tools are managed by the host and never appear here."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (loading && tools.isEmpty()) {
            item {
                Text(
                    AppLanguage.tr("正在加载工具…", "Loading tools…"),
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (tools.isEmpty()) {
            item {
                Text(
                    AppLanguage.tr("当前没有可用工具。", "No tools are currently available."),
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(
                tools,
                key = { "tool:${it.name}" }
            ) { tool ->
                AgentToolCard(
                    tool = tool,
                    onToggle = { onToggleTool(tool.name, it) }
                )
            }
        }
    }
}

@Composable
private fun AgentToolCard(
    tool: AgentToolInfo,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    tool.title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    tool.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (tool.mutating) {
                    Text(
                        AppLanguage.tr("会修改状态", "Changes state"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Switch(
                checked = tool.enabled,
                onCheckedChange = onToggle
            )
        }
    }
}
