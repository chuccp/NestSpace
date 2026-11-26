package com.coke.nestspace.features.plugins

import androidx.compose.ui.graphics.vector.ImageVector
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.plugin.AgentModelPlugins
import com.coke.nestspace.plugin.AgentPlugins
import com.coke.nestspace.plugin.AgentToolHookPlugins
import com.coke.nestspace.plugin.AgentToolPlugins
import com.coke.nestspace.plugin.AgentToolPresentationPlugins
import com.coke.nestspace.plugin.PluginCapability
import com.coke.nestspace.plugin.PluginCommandPlugins
import com.coke.nestspace.plugin.PluginDescriptor
import com.coke.nestspace.plugin.PluginLlmProviders
import com.coke.nestspace.plugin.PluginManifest
import com.coke.nestspace.plugin.PluginPermission
import com.coke.nestspace.plugin.PluginSettingView
import com.coke.nestspace.plugin.PluginSpeechProviders
import com.coke.nestspace.plugin.PluginUiPlugins
import com.coke.nestspace.plugin.RuntimeProxyPlugins
import com.coke.nestspace.plugins.host.BuiltInPluginEntry
import com.coke.nestspace.plugins.host.PluginCenterStore
import com.coke.nestspace.plugins.host.PluginPackageInstaller
import com.coke.nestspace.plugins.host.PluginSourceRegistry

internal data class PluginDetail(
    val id: String,
    val title: String,
    val description: String,
    val version: String,
    val publisher: String,
    val capabilities: Set<PluginCapability>,
    val iconFile: java.io.File? = null,
    val htmlFile: java.io.File? = null,
    val builtin: BuiltInPluginEntry? = null
)

internal enum class PluginEntryKind {
    PACKAGE,
    SOURCE,
    BUILTIN,
    REGISTERED
}

internal data class PluginActionTarget(
    val id: String,
    val defaultName: String,
    val displayName: String,
    val kind: PluginEntryKind
)

internal data class PluginMenuAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

internal data class PluginSettingsTarget(
    val id: String,
    val title: String,
    val view: PluginSettingView,
    val values: Map<String, String>,
    val configuredSecrets: Set<String>
)

internal enum class PluginCategory {
    ALL,
    AGENT,
    DISPLAY,
    NETWORK,
    DATA,
    RUNTIME,
    SYSTEM
}

internal fun PluginManifest.toDetail(
    context: android.content.Context
): PluginDetail = PluginDetail(
    id = id,
    title = PluginCenterStore.displayName(context, id, name),
    description = description,
    version = version,
    publisher = publisher.ifBlank { "NestSpace" },
    capabilities = capabilities,
    iconFile = PluginSourceRegistry.iconFile(id)
        ?: PluginPackageInstaller.get(context).iconFile(id),
    htmlFile = PluginSourceRegistry.htmlPage(id)
        ?: PluginPackageInstaller.get(context).htmlPage(id)
)

internal fun PluginDescriptor.toDetail(
    description: String,
    publisher: String,
    title: String = name,
    builtin: BuiltInPluginEntry? = null
): PluginDetail = PluginDetail(
    id = id,
    title = title,
    description = description,
    version = version,
    publisher = publisher,
    capabilities = capabilities,
    builtin = builtin
)

internal fun permissionLabel(permission: PluginPermission): String =
    when (permission) {
        PluginPermission.AGENT -> AppLanguage.tr("Agent", "Agent")
        PluginPermission.RUNTIME -> AppLanguage.tr("运行时", "Runtime")
        PluginPermission.AUTOMATION -> AppLanguage.tr("自动化", "Automation")
        PluginPermission.STORAGE -> AppLanguage.tr("存储", "Storage")
        PluginPermission.SECRETS -> AppLanguage.tr("密钥", "Secrets")
        PluginPermission.CONTENT -> AppLanguage.tr("内容交互", "Content")
        PluginPermission.UI -> AppLanguage.tr("界面", "UI")
        PluginPermission.NOTIFICATIONS -> AppLanguage.tr("通知", "Notifications")
        PluginPermission.NETWORK -> AppLanguage.tr("网络", "Network")
        PluginPermission.DEVICE -> AppLanguage.tr("设备能力", "Device")
    }

internal fun categoryLabel(category: PluginCategory): String = when (category) {
    PluginCategory.ALL -> AppLanguage.tr("全部", "All")
    PluginCategory.AGENT -> AppLanguage.tr("Agent", "Agent")
    PluginCategory.DISPLAY -> AppLanguage.tr("显示", "Display")
    PluginCategory.NETWORK -> AppLanguage.tr("网络", "Network")
    PluginCategory.DATA -> AppLanguage.tr("数据", "Data")
    PluginCategory.RUNTIME -> AppLanguage.tr("运行时", "Runtime")
    PluginCategory.SYSTEM -> AppLanguage.tr("系统", "System")
}

internal fun PluginManifest.matches(
    query: String,
    category: PluginCategory,
    displayName: String = name
): Boolean {
    val haystack = buildString {
        append(displayName).append(' ')
        append(name).append(' ')
        append(id).append(' ')
        append(publisher).append(' ')
        append(description).append(' ')
        capabilities.forEach { append(it.name).append(' ') }
        PluginPermission.groupsFor(capabilities)
            .forEach { append(it.id()).append(' ') }
    }.lowercase()
    return (query.isBlank() || haystack.contains(query.trim().lowercase())) &&
        capabilities.matchesCategory(category)
}

internal fun PluginDescriptor.matches(
    query: String,
    category: PluginCategory,
    description: String = "",
    displayName: String = name
): Boolean {
    val haystack = buildString {
        append(displayName).append(' ')
        append(name).append(' ')
        append(id).append(' ')
        append(description).append(' ')
        capabilities.forEach { append(it.name).append(' ') }
        PluginPermission.groupsFor(capabilities)
            .forEach { append(it.id()).append(' ') }
    }.lowercase()
    return (query.isBlank() || haystack.contains(query.trim().lowercase())) &&
        capabilities.matchesCategory(category)
}

internal fun PluginManifest.exportFileName(): String =
    "$id-$version.nest".replace(Regex("[^A-Za-z0-9._-]"), "_")

private fun Set<PluginCapability>.matchesCategory(
    category: PluginCategory
): Boolean = when (category) {
    PluginCategory.ALL -> true
    PluginCategory.AGENT -> any {
        it.name.startsWith("AGENT_") ||
            it == PluginCapability.LLM_PROVIDERS ||
            it == PluginCapability.SPEECH_PROVIDERS ||
            it == PluginCapability.COMMANDS
    }
    PluginCategory.DISPLAY -> any {
        it.name.startsWith("UI_") ||
            it == PluginCapability.AGENT_TOOL_PRESENTATION
    }
    PluginCategory.NETWORK ->
        PluginCapability.NETWORK in this ||
            PluginCapability.WEBSOCKET in this
    PluginCategory.DATA -> any {
        it in setOf(
            PluginCapability.STORAGE,
            PluginCapability.CACHE,
            PluginCapability.DATABASE,
            PluginCapability.SETTINGS,
            PluginCapability.SECRETS,
            PluginCapability.FILE_PICKER,
            PluginCapability.FILE_EXPORT
        )
    }
    PluginCategory.RUNTIME -> any {
        it == PluginCapability.RUNTIME_LAUNCH_POLICY
    }
    PluginCategory.SYSTEM -> any {
        it == PluginCapability.ANDROID_APIS ||
            it == PluginCapability.NOTIFICATIONS ||
            it == PluginCapability.SHARE ||
            it == PluginCapability.CLIPBOARD
    }
}

internal fun registeredPlugins(): List<PluginDescriptor> {
    val result = LinkedHashMap<String, PluginDescriptor>()
    RuntimeProxyPlugins.snapshot().forEach { result[it.id] = it }
    AgentPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    PluginSpeechProviders.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    AgentModelPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    AgentToolPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    AgentToolHookPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    AgentToolPresentationPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    PluginCommandPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    PluginUiPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    PluginLlmProviders.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    return result.values
        .filterNot { PluginSourceRegistry.contains(it.id) }
        .sortedBy { it.name.lowercase() }
}
