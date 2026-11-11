package com.coke.nestspace.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.proxy.AppProxyManager
import com.coke.nestspace.proxy.AppProxySettings

@Composable
internal fun AppProxyScreen() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(AppProxySettings.load(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    val status by AppProxyManager.status.collectAsState()

    fun validationText(value: AppProxySettings.ValidationError): String =
        when (value) {
            AppProxySettings.ValidationError.INVALID_TYPE ->
                AppLanguage.tr("代理协议无效", "Invalid proxy protocol")
            AppProxySettings.ValidationError.HOST_REQUIRED ->
                AppLanguage.tr("请填写代理服务器地址", "Proxy host is required")
            AppProxySettings.ValidationError.INVALID_HOST ->
                AppLanguage.tr(
                    "只填写主机名或 IPv4 地址，不要带协议、端口或斜杠",
                    "Enter a hostname or IPv4 address without scheme, port or slash"
                )
            AppProxySettings.ValidationError.INVALID_PORT ->
                AppLanguage.tr("端口必须在 1-65535 之间", "Port must be between 1 and 65535")
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("应用网络代理", "App network proxy")) }
        item {
            SettingsCard(
                title = AppLanguage.tr("代理服务器", "Proxy server"),
                status = if (config.enabled) {
                    AppLanguage.tr("已启用", "Enabled")
                } else {
                    AppLanguage.tr("已关闭", "Disabled")
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            AppLanguage.tr("让应用网络请求走代理", "Proxy app network traffic"),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            AppLanguage.tr(
                                "影响 LLM、插件、MCP、更新检查等请求",
                                "Applies to LLM, plug-in, MCP and update requests"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = {
                            error = null
                            config = config.copy(enabled = it)
                        }
                    )
                }

                Text(
                    AppLanguage.tr("代理协议", "Proxy protocol"),
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = config.type == AppProxySettings.TYPE_HTTP,
                        onClick = {
                            error = null
                            config = config.copy(type = AppProxySettings.TYPE_HTTP)
                        },
                        label = { Text("HTTP") }
                    )
                    FilterChip(
                        selected = config.type == AppProxySettings.TYPE_HTTPS,
                        onClick = {
                            error = null
                            config = config.copy(type = AppProxySettings.TYPE_HTTPS)
                        },
                        label = { Text("HTTPS") }
                    )
                }
                Text(
                    AppLanguage.tr(
                        "HTTP 与 HTTPS 两种代理端点都可用于访问 HTTP/HTTPS 目标；HTTPS 表示连接到代理服务器时使用 TLS。",
                        "Both proxy endpoints can reach HTTP and HTTPS targets. HTTPS means " +
                            "the connection to the proxy server uses TLS."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = config.host,
                    onValueChange = {
                        error = null
                        config = config.copy(host = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(AppLanguage.tr("代理服务器", "Proxy host")) },
                    placeholder = { Text("127.0.0.1") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = if (config.port == AppProxySettings.NO_PORT) {
                        ""
                    } else {
                        config.port.toString()
                    },
                    onValueChange = { value ->
                        error = null
                        val digits = value.filter(Char::isDigit).take(5)
                        config = config.copy(
                            port = digits.toIntOrNull() ?: AppProxySettings.NO_PORT
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(AppLanguage.tr("端口", "Port")) },
                    placeholder = { Text("8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = config.bypass,
                    onValueChange = {
                        error = null
                        config = config.copy(bypass = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            AppLanguage.tr(
                                "直连配置（不走代理）",
                                "Direct connection rules"
                            )
                        )
                    },
                    placeholder = {
                        Text("localhost, 192.168.*, *.corp.example, api.*")
                    },
                    maxLines = 3
                )
                Text(
                    AppLanguage.tr(
                        "每行一个，或用逗号分隔。支持完整 IP/域名、域名后缀和 * 模糊匹配，" +
                            "例如 192.168.*、*.corp.example、api.*。",
                        "Use one entry per line or comma-separated values. Supports IPs, " +
                            "domains, domain suffixes and * wildcards, for example " +
                            "192.168.*, *.corp.example or api.*."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    AppLanguage.tr(
                        "暂不支持用户名/密码认证；HTTPS 代理服务器需提供系统信任的 TLS 证书。",
                        "Username/password authentication is not supported. HTTPS proxy " +
                            "servers must present a system-trusted TLS certificate."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (status.message.isNotBlank()) {
                    Text(
                        status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.applied || !config.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                Button(
                    onClick = {
                        val validation = AppProxySettings.validate(config)
                        if (validation != null) {
                            error = validationText(validation)
                        } else {
                            val result = AppProxyManager.apply(context, config)
                            error = if (result.applied || !config.enabled) null else result.message
                            Toast.makeText(
                                context.applicationContext,
                                result.message,
                                Toast.LENGTH_SHORT
                            ).show()
                            AppSettingsEvents.notifyChanged()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(AppLanguage.tr("保存并应用", "Save and apply"))
                }
            }
        }
        item {
            SettingsCard(
                title = AppLanguage.tr("浏览器代理", "Browser proxy"),
                status = AppLanguage.tr("单独设置", "Separate")
            ) {
                Text(
                    AppLanguage.tr(
                        "内置浏览器使用自己的代理设置。请打开浏览器，点击地址栏旁的代理按钮进行配置。",
                        "The built-in browser uses its own proxy setting. Open the browser " +
                            "and use the proxy button next to the address bar."
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            Text(
                if (config.enabled) {
                    AppLanguage.tr(
                        "当前代理：${AppProxySettings.proxyRule(config)}",
                        "Current proxy: ${AppProxySettings.proxyRule(config)}"
                    )
                } else {
                    AppLanguage.tr("应用代理未启用", "App proxy is disabled")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
