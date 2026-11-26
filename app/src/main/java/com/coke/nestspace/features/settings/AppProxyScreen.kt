package com.coke.nestspace.features.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.integrations.proxy.AppProxyManager
import com.coke.nestspace.integrations.proxy.AppProxySettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppProxyScreen() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(AppProxySettings.load(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    val status by AppProxyManager.status.collectAsState()

    fun validationText(value: AppProxySettings.ValidationError): String =
        when (value) {
            AppProxySettings.ValidationError.INVALID_TYPE ->
                AppLanguage.tr("代理类型无效", "Invalid proxy type")
            AppProxySettings.ValidationError.HOST_REQUIRED ->
                AppLanguage.tr("请填写代理服务器地址", "Proxy host is required")
            AppProxySettings.ValidationError.INVALID_HOST ->
                AppLanguage.tr(
                    "只填写主机名或 IP 地址，不要带协议、端口或斜杠",
                    "Enter a hostname or IP address without scheme, port or slash"
                )
            AppProxySettings.ValidationError.INVALID_PORT ->
                AppLanguage.tr("端口必须在 1-65535 之间", "Port must be between 1 and 65535")
            AppProxySettings.ValidationError.USERNAME_REQUIRED ->
                AppLanguage.tr(
                    "开启身份验证后需要填写用户名",
                    "A username is required when authentication is enabled"
                )
        }

    val typeLabel = when {
        !config.enabled -> AppLanguage.tr("不使用代理", "No proxy")
        config.type == AppProxySettings.TYPE_HTTPS -> "HTTPS"
        config.type == AppProxySettings.TYPE_SOCKS -> "SOCKS5"
        else -> "HTTP"
    }
    val modeStatus = when {
        !config.enabled -> AppLanguage.tr("直连", "Direct")
        config.type == AppProxySettings.TYPE_HTTPS -> "HTTPS"
        config.type == AppProxySettings.TYPE_SOCKS -> "SOCKS5"
        else -> "HTTP"
    }

    fun selectProxyMode(enabled: Boolean, type: String = config.type) {
        error = null
        config = config.copy(
            enabled = enabled,
            type = type
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("网络代理", "Network proxy")) }
        item {
            SettingsCard(
                title = AppLanguage.tr("代理类型", "Proxy type"),
                status = modeStatus
            ) {
                ExposedDropdownMenuBox(
                    expanded = typeMenuExpanded,
                    onExpandedChange = { typeMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = typeLabel,
                        onValueChange = {},
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        readOnly = true,
                        label = { Text(AppLanguage.tr("连接方式", "Connection type")) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = typeMenuExpanded
                            )
                        }
                    )
                    ExposedDropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(AppLanguage.tr("不使用代理", "No proxy")) },
                            onClick = {
                                typeMenuExpanded = false
                                selectProxyMode(enabled = false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("HTTP") },
                            trailingIcon = {
                                if (config.enabled &&
                                    config.type == AppProxySettings.TYPE_HTTP
                                ) {
                                    Text("✓")
                                }
                            },
                            onClick = {
                                typeMenuExpanded = false
                                selectProxyMode(
                                    enabled = true,
                                    type = AppProxySettings.TYPE_HTTP
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("HTTPS") },
                            trailingIcon = {
                                if (config.enabled &&
                                    config.type == AppProxySettings.TYPE_HTTPS
                                ) {
                                    Text("✓")
                                }
                            },
                            onClick = {
                                typeMenuExpanded = false
                                selectProxyMode(
                                    enabled = true,
                                    type = AppProxySettings.TYPE_HTTPS
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("SOCKS5") },
                            trailingIcon = {
                                if (config.enabled &&
                                    config.type == AppProxySettings.TYPE_SOCKS
                                ) {
                                    Text("✓")
                                }
                            },
                            onClick = {
                                typeMenuExpanded = false
                                selectProxyMode(
                                    enabled = true,
                                    type = AppProxySettings.TYPE_SOCKS
                                )
                            }
                        )
                    }
                }
                Text(
                    AppLanguage.tr(
                        "安装到空间且遵循系统代理的应用会一起使用此代理；自行建立原始网络连接的应用可能不受影响。",
                        "Installed space apps that respect the system proxy use this proxy " +
                            "too. Apps with their own raw network connections may bypass it."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (config.enabled) {
            item {
                SettingsCard(
                    title = AppLanguage.tr("代理服务器", "Proxy server"),
                    status = modeStatus
                ) {
                    OutlinedTextField(
                        value = config.host,
                        onValueChange = {
                            error = null
                            config = config.copy(host = it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(AppLanguage.tr("服务器地址", "Server address")) },
                        placeholder = { Text("192.168.10.115") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
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
                        placeholder = {
                            Text(
                                when (config.type) {
                                    AppProxySettings.TYPE_HTTPS -> "443"
                                    AppProxySettings.TYPE_SOCKS -> "1080"
                                    else -> "8080"
                                }
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }

            item {
                SettingsCard(
                    title = AppLanguage.tr("身份验证", "Authentication"),
                    status = if (config.authenticationEnabled) {
                        AppLanguage.tr("已开启", "Enabled")
                    } else {
                        AppLanguage.tr("不需要", "Off")
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                AppLanguage.tr(
                                    "使用用户名和密码",
                                    "Use username and password"
                                ),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                AppLanguage.tr(
                                    "代理服务器要求身份验证时开启",
                                    "Turn this on when the proxy requires credentials"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.authenticationEnabled,
                            onCheckedChange = {
                                error = null
                                if (!it) showPassword = false
                                config = config.copy(authenticationEnabled = it)
                            }
                        )
                    }

                    if (config.authenticationEnabled) {
                        OutlinedTextField(
                            value = config.username,
                            onValueChange = {
                                error = null
                                config = config.copy(username = it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(AppLanguage.tr("用户名", "Username")) },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = config.password,
                            onValueChange = {
                                error = null
                                config = config.copy(password = it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(AppLanguage.tr("密码", "Password")) },
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) {
                                            Icons.Rounded.VisibilityOff
                                        } else {
                                            Icons.Rounded.Visibility
                                        },
                                        contentDescription = if (showPassword) {
                                            AppLanguage.tr("隐藏密码", "Hide password")
                                        } else {
                                            AppLanguage.tr("显示密码", "Show password")
                                        }
                                    )
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password
                            )
                        )
                        Text(
                            AppLanguage.tr(
                                "密码使用 Android Keystore 加密保存。",
                                "The password is encrypted with Android Keystore."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SettingsCard(
                    title = AppLanguage.tr("例外与直连", "Exceptions"),
                    status = AppLanguage.tr("可选", "Optional")
                ) {
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
                                    "不经过代理的地址",
                                    "Addresses that bypass the proxy"
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
                            "每行一个，或用逗号分隔。支持完整 IP/域名、域名后缀和 * 模糊匹配。",
                            "Use one entry per line or comma-separated values. Supports IPs, " +
                                "domains, suffixes and * wildcards."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            item {
                SettingsCard(
                    title = AppLanguage.tr("直连", "Direct connection"),
                    status = AppLanguage.tr("未启用", "Off")
                ) {
                    Text(
                        AppLanguage.tr(
                            "LLM、插件、MCP 和更新检查将直接连接网络，不使用代理。",
                            "LLM, plug-ins, MCP and update checks will connect directly " +
                                "without a proxy."
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item {
            SettingsCard(
                title = AppLanguage.tr("应用设置", "Apply settings"),
                status = if (config.enabled) modeStatus else {
                    AppLanguage.tr("直连", "Direct")
                }
            ) {
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
                if (config.enabled) {
                    Text(
                        AppLanguage.tr(
                            "当前代理：${AppProxySettings.proxyRule(config)}",
                            "Current proxy: ${AppProxySettings.proxyRule(config)}"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = {
                        val validation = AppProxySettings.validate(config)
                        if (validation != null) {
                            error = validationText(validation)
                        } else {
                            val result = AppProxyManager.apply(context, config)
                            error = if (result.applied || !config.enabled) {
                                null
                            } else {
                                result.message
                            }
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
            Text(
                AppLanguage.tr(
                    "此设置影响 LLM、插件、MCP、更新检查和虚拟应用。",
                    "This setting applies to LLM, plug-ins, MCP, updates and virtual apps."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
