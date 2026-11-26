package com.coke.nestspace.features.plugins

import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.features.browser.BrowserActivity
import com.coke.nestspace.plugin.PluginSettingsField
import com.coke.nestspace.plugin.PluginSettingsField.Format
import com.coke.nestspace.plugins.host.AndroidPluginSecrets
import com.coke.nestspace.plugins.host.AndroidPluginSettings

@Composable
internal fun RenamePluginDialog(
    target: PluginActionTarget,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember(target.id, target.displayName) {
        mutableStateOf(target.displayName)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(AppLanguage.tr("重命名插件", "Rename plugin"))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(target.defaultName)
                    }
                )
                Text(
                    AppLanguage.tr(
                        "留空会恢复默认名称。插件 ID 和包名不会改变。",
                        "Leave empty to restore the default name. " +
                            "The plugin ID and package name do not change."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) {
                Text(AppLanguage.tr("保存", "Save"))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave("") }) {
                    Text(AppLanguage.tr("恢复默认", "Reset"))
                }
                TextButton(onClick = onDismiss) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        }
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PluginSettingsDialog(
    target: PluginSettingsTarget,
    saving: Boolean,
    error: String,
    onDismiss: () -> Unit,
    onSave: (PluginSettingDraft) -> Unit
) {
    val context = LocalContext.current
    val formStore = remember(target.id) {
        PluginSettingFormStore(
            AndroidPluginSettings(context, target.id),
            AndroidPluginSecrets(context, target.id)
        )
    }
    var values by remember(target.id, target.values) {
        mutableStateOf(target.values)
    }
    var secretsToClear by remember(target.id) {
        mutableStateOf(emptySet<String>())
    }
    var validationError by remember(target.id) {
        mutableStateOf("")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(target.view.title.ifBlank {
                    AppLanguage.tr("插件设置", "Plugin settings")
                })
                Text(
                    target.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (target.view.description.isNotBlank()) {
                    Text(
                        target.view.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (target.view.fields.isEmpty()) {
                    Text(
                        AppLanguage.tr(
                            "该插件没有可配置参数。",
                            "This plugin has no configurable settings."
                        )
                    )
                }
                target.view.fields.forEach { field ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            buildString {
                                append(field.title)
                                if (field.isRequired) append(" *")
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (field.description.isNotBlank()) {
                            Text(
                                field.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        when (field.type) {
                            PluginSettingsField.Type.BOOLEAN -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (values[field.id] == "true") {
                                            AppLanguage.tr("已启用", "Enabled")
                                        } else {
                                            AppLanguage.tr("已停用", "Disabled")
                                        },
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Switch(
                                        checked = values[field.id] == "true",
                                        onCheckedChange = {
                                            values = values + (
                                                field.id to it.toString()
                                            )
                                        },
                                        enabled = !saving
                                    )
                                }
                            }
                            PluginSettingsField.Type.SELECT -> {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    field.options.forEach { option ->
                                        FilterChip(
                                            selected = values[field.id] == option,
                                            onClick = {
                                                values = values + (
                                                    field.id to option
                                                )
                                            },
                                            enabled = !saving,
                                            label = { Text(option) }
                                        )
                                    }
                                }
                            }
                            else -> {
                                val secretConfigured =
                                    field.id in target.configuredSecrets
                                OutlinedTextField(
                                    value = values[field.id].orEmpty(),
                                    onValueChange = {
                                        values = values + (field.id to it)
                                        if (it.isNotBlank()) {
                                            secretsToClear -= field.id
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !saving,
                                    singleLine = field.type !=
                                        PluginSettingsField.Type.TEXT,
                                    placeholder = {
                                        val hint = if (field.type ==
                                            PluginSettingsField.Type.SECRET
                                        ) {
                                            if (secretConfigured) {
                                                AppLanguage.tr(
                                                    "已保存，留空保持不变",
                                                    "Saved; leave blank to keep"
                                                )
                                            } else {
                                                field.placeholder
                                            }
                                        } else {
                                            field.placeholder
                                        }
                                        if (hint.isNotBlank()) {
                                            Text(hint)
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = when {
                                            field.type ==
                                                PluginSettingsField.Type.INTEGER ->
                                                KeyboardType.Number
                                            field.format == Format.URL ||
                                                field.format == Format.EMAIL ->
                                                KeyboardType.Uri
                                            else -> KeyboardType.Text
                                        }
                                    )
                                )
                                if (field.type ==
                                    PluginSettingsField.Type.SECRET &&
                                    (secretConfigured ||
                                        field.id in secretsToClear)
                                ) {
                                    TextButton(
                                        enabled = !saving,
                                        onClick = {
                                            values = values + (field.id to "")
                                            secretsToClear += field.id
                                        }
                                    ) {
                                        Text(
                                            AppLanguage.tr(
                                                "清除已保存的值",
                                                "Clear saved value"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val message = validationError.ifBlank { error }
                if (message.isNotBlank()) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val invalid = formStore.validate(
                        target.view,
                        values,
                        target.configuredSecrets,
                        secretsToClear
                    )
                    if (invalid == null) {
                        validationError = ""
                        onSave(
                            PluginSettingDraft(
                                values = values,
                                secretsToClear = secretsToClear
                            )
                        )
                    } else {
                        validationError = invalid
                    }
                }
            ) {
                Text(
                    if (saving) {
                        AppLanguage.tr("正在保存…", "Saving…")
                    } else {
                        AppLanguage.tr("保存", "Save")
                    }
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        values = target.view.fields.associate {
                            it.id to it.defaultValue
                        }
                        secretsToClear = target.configuredSecrets
                        validationError = ""
                    }
                ) {
                    Text(AppLanguage.tr("恢复默认", "Reset"))
                }
                TextButton(
                    enabled = !saving,
                    onClick = onDismiss
                ) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        }
    )
}

@Composable
internal fun PluginDetailDialog(
    detail: PluginDetail,
    onBuiltinEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(detail.iconFile?.path) {
        detail.iconFile?.takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.path) }
            ?.asImageBitmap()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .heightIn(max = 780.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = detail.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Extension,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            detail.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${detail.publisher} · v${detail.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    detail.description.ifBlank {
                        AppLanguage.tr("暂无插件介绍", "No plugin description")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    detail.id,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )
                PluginCapabilitySummary(detail.capabilities)
                if (detail.builtin != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                AppLanguage.tr("启用插件", "Enable plugin"),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                if (detail.builtin.enabled) {
                                    AppLanguage.tr("当前已启用", "Currently enabled")
                                } else {
                                    AppLanguage.tr("当前已停用", "Currently disabled")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = detail.builtin.enabled,
                            onCheckedChange = onBuiltinEnabledChange
                        )
                    }
                }
                val htmlFile = detail.htmlFile
                if (htmlFile != null && htmlFile.isFile) {
                    Text(
                        AppLanguage.tr("插件页面", "Plugin page"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val webView = remember(htmlFile.path) {
                        WebView(context).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.blockNetworkLoads = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    url: String?
                                ): Boolean {
                                    val uri = url?.let {
                                        runCatching { android.net.Uri.parse(it) }
                                            .getOrNull()
                                    } ?: return true
                                    if (uri.scheme == "file") return false
                                    context.startActivity(
                                        Intent(
                                            context,
                                            BrowserActivity::class.java
                                        )
                                            .putExtra(
                                                BrowserActivity.EXTRA_URL,
                                                uri.toString()
                                            )
                                            .addFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK
                                            )
                                    )
                                    return true
                                }
                            }
                            loadUrl(htmlFile.toURI().toString())
                        }
                    }
                    DisposableEffect(webView) {
                        onDispose {
                            webView.stopLoading()
                            webView.loadUrl("about:blank")
                            webView.destroy()
                        }
                    }
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(AppLanguage.tr("关闭", "Close"))
                    }
                }
            }
        }
    }
}
