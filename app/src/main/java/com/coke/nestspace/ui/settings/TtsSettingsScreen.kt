package com.coke.nestspace.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.plugin.PluginSpeechProvider
import com.coke.nestspace.plugin.PluginSpeechProviders
import com.coke.nestspace.speech.SpeechException
import com.coke.nestspace.speech.SpeechGateway
import com.coke.nestspace.speech.TtsConfig
import com.coke.nestspace.speech.TtsSettings
import kotlinx.coroutines.launch

@Composable
internal fun TtsSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRevision by AppSettingsEvents.revision.collectAsState()
    var config by remember { mutableStateOf(TtsSettings.load(context)) }
    var providers by remember {
        mutableStateOf(PluginSpeechProviders.snapshot())
    }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf("") }

    LaunchedEffect(settingsRevision) {
        config = TtsSettings.load(context)
        providers = PluginSpeechProviders.snapshot()
    }

    fun select(providerId: String) {
        val next = TtsConfig(providerId)
        runCatching { TtsSettings.save(context, next) }
            .onSuccess {
                config = next
                testMessage = ""
                SpeechGateway.stopAll()
                AppSettingsEvents.notifyChanged()
            }
            .onFailure {
                testMessage = AppLanguage.tr(
                    "保存失败：${it.message ?: "未知错误"}",
                    "Could not save: ${it.message ?: "unknown error"}"
                )
            }
    }

    fun test() {
        if (testing) return
        testing = true
        testMessage = ""
        scope.launch {
            try {
                SpeechGateway.speak(
                    context = context,
                    text = AppLanguage.tr(
                        "你好，这是 NestSpace 的语音测试。",
                        "Hello, this is a NestSpace speech test."
                    ),
                    providerId = config.providerId,
                    interrupt = true
                )
                testMessage = AppLanguage.tr("已加入播放队列", "Queued for playback")
            } catch (error: SpeechException) {
                testMessage = error.message ?: error.code
            } catch (error: Throwable) {
                testMessage = error.message ?: error.javaClass.simpleName
            } finally {
                testing = false
            }
        }
    }

    val selectedProviderAvailable = config.providerId ==
        TtsSettings.SYSTEM_PROVIDER_ID ||
        providers.any { it.providerId == config.providerId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                AppLanguage.tr(
                    "选择 AI 语音播报使用的 TTS。默认使用 Android 系统语音，也可以切换为已启用插件提供的音色。",
                    "Choose the TTS engine used for AI speech. Android system TTS is the " +
                        "default; enabled plugin voices can be selected instead."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            TtsProviderCard(
                title = AppLanguage.tr("系统 TTS", "System TTS"),
                summary = AppLanguage.tr(
                    "使用 Android 系统语音引擎，无需插件",
                    "Android system speech engine; no plugin required"
                ),
                selected = config.providerId == TtsSettings.SYSTEM_PROVIDER_ID,
                onClick = { select(TtsSettings.SYSTEM_PROVIDER_ID) }
            )
        }
        if (providers.isNotEmpty()) {
            item {
                Text(
                    AppLanguage.tr("插件 TTS", "Plugin TTS"),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            providers.forEach { provider ->
                item(key = provider.providerId) {
                    TtsPluginCard(
                        provider = provider,
                        selected = config.providerId == provider.providerId,
                        onClick = { select(provider.providerId) }
                    )
                }
            }
        } else {
            item {
                Text(
                    AppLanguage.tr(
                        "当前没有启用提供 TTS 能力的插件。",
                        "No enabled plugin currently provides TTS."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!selectedProviderAvailable) {
            item {
                Text(
                    AppLanguage.tr(
                        "已选择的插件 TTS 当前不可用，运行时会自动回退到系统 TTS。",
                        "The selected plugin TTS is unavailable; runtime will fall back " +
                            "to system TTS."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        item {
            Button(
                onClick = ::test,
                enabled = !testing
            ) {
                Text(
                    if (testing) {
                        AppLanguage.tr("正在试听…", "Testing…")
                    } else {
                        AppLanguage.tr("试听", "Test voice")
                    }
                )
            }
        }
        if (testMessage.isNotBlank()) {
            item {
                Text(
                    testMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TtsPluginCard(
    provider: PluginSpeechProvider,
    selected: Boolean,
    onClick: () -> Unit
) {
    TtsProviderCard(
        title = provider.displayName,
        summary = provider.providerId,
        selected = selected,
        onClick = onClick
    )
}

@Composable
private fun TtsProviderCard(
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
