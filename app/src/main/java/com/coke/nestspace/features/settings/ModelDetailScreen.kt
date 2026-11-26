package com.coke.nestspace.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.plugins.host.PluginLlmModelOption

@Composable
internal fun ModelDetailScreen(
    configs: List<LlmConfig>,
    activeId: String,
    pluginModels: List<PluginLlmModelOption>,
    onAddLlm: () -> Unit,
    onAddPluginModel: (PluginLlmModelOption) -> Unit,
    onEditLlm: (LlmConfig) -> Unit,
    onDeleteLlm: (String) -> Unit,
    onSelectLlm: (String) -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    var actionMode by remember { mutableStateOf(false) }
    val configuredPluginKeys = configs.mapNotNull { config ->
        val providerId = config.pluginProviderId() ?: return@mapNotNull null
        "$providerId\u0000${config.model}"
    }.toSet()
    val availablePluginModels = pluginModels.filterNot {
        it.key in configuredPluginKeys
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    AppLanguage.tr("大模型", "Models"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onAddLlm) { Text(AppLanguage.tr("添加", "Add")) }
                if (configs.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { actionMode = !actionMode }) {
                        Text(
                            if (actionMode) {
                                AppLanguage.tr("完成", "Done")
                            } else {
                                AppLanguage.tr("操作", "Manage")
                            }
                        )
                    }
                }
            }
        }

        if (configs.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        AppLanguage.tr("还没有模型配置", "No model configured yet"),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        AppLanguage.tr(
                            "从下方选择插件模型，或手动添加接口。",
                            "Choose a plugin model below or add an endpoint manually."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(configs, key = { it.id }) { config ->
                val pluginModel = config.pluginProviderId()?.let { providerId ->
                    pluginModels.firstOrNull {
                        it.providerId == providerId && it.modelId == config.model
                    }
                }
                val isPluginConfig = config.pluginProviderId() != null
                val modelLabel = pluginModel?.modelName
                    ?: config.model.ifBlank {
                        AppLanguage.tr("未填模型", "No model")
                    }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (config.id == activeId) {
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else {
                        CardDefaults.cardColors()
                    }
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !actionMode) { onSelectLlm(config.id) }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        config.name.ifBlank { AppLanguage.tr("未命名模型", "Unnamed model") },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (config.id == activeId) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            AppLanguage.tr("当前使用", "Active"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    if (isPluginConfig) {
                                        AppLanguage.tr("插件模型", "Plugin model")
                                    } else {
                                        config.protocol.label
                                    } + " · " + modelLabel +
                                        " · " + String.format(
                                            locale,
                                            "%,d",
                                            config.contextTokens
                                        ) + " tokens",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    if (isPluginConfig) {
                                        pluginModel?.providerName ?: AppLanguage.tr(
                                            "插件未启用",
                                            "Plugin unavailable"
                                        )
                                    } else {
                                        config.baseUrl
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (actionMode) {
                                TextButton(onClick = { onDeleteLlm(config.id) }) {
                                    Text(AppLanguage.tr("删除", "Delete"))
                                }
                                TextButton(onClick = { onEditLlm(config) }) {
                                    Text(AppLanguage.tr("修改", "Edit"))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (availablePluginModels.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        AppLanguage.tr("插件模型", "Plugin models"),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        AppLanguage.tr(
                            "由已启用插件提供，无需填写 URL 或 API Key。",
                            "Provided by enabled plugins; no URL or API key required."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(availablePluginModels, key = { "plugin:${it.key}" }) { option ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                option.modelName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                option.providerName,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                String.format(
                                    locale,
                                    "%,d",
                                    option.contextTokens
                                ) + " tokens" +
                                    if (option.supportsVision) {
                                        AppLanguage.tr(" · 支持图片", " · Vision")
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = { onAddPluginModel(option) }) {
                            Text(AppLanguage.tr("添加", "Add"))
                        }
                    }
                }
            }
        }

    }
}
