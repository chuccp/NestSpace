package com.coke.nestspace.features.plugins

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.plugins.host.AndroidPluginSecrets
import com.coke.nestspace.plugins.host.AndroidPluginSettings
import com.coke.nestspace.plugins.host.PluginCenterStore
import com.coke.nestspace.plugin.PluginManifest
import com.coke.nestspace.plugins.host.PluginPackageInstaller
import com.coke.nestspace.plugins.host.PluginSourceRegistry
import com.coke.nestspace.plugins.host.PluginSourceLoader
import com.coke.nestspace.plugin.PluginSettingView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PluginCenterScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRevision by AppSettingsEvents.revision.collectAsState()
    var localRevision by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(PluginCategory.ALL) }
    var installStatus by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<PluginDetail?>(null) }
    var renameTarget by remember { mutableStateOf<PluginActionTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<PluginActionTarget?>(null) }
    var exportTarget by remember { mutableStateOf<PluginManifest?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var settingsTarget by remember { mutableStateOf<PluginSettingsTarget?>(null) }
    var settingsSaving by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf("") }

    val builtIns = remember(context, settingsRevision, localRevision) {
        PluginCenterStore.builtIns(context)
    }
    val packages = remember(context, localRevision) {
        PluginPackageInstaller.get(context).installedManifests()
    }
    val sourcePlugins = remember(context, localRevision) {
        PluginSourceRegistry.manifests()
    }
    val packageIds = packages.mapTo(HashSet()) { it.id }
    val sourceIds = sourcePlugins.mapTo(HashSet()) { it.id }
    val builtInIds = builtIns.mapTo(HashSet()) { it.descriptor.id }
    val extraRegistered = remember(settingsRevision, localRevision) {
        registeredPlugins().filterNot {
            it.id in builtInIds || it.id in packageIds || it.id in sourceIds
        }
    }
    val installedCount =
        packages.size + sourcePlugins.size + extraRegistered.size
    val visiblePackages = packages.filter { manifest ->
        manifest.matches(
            query,
            category,
            PluginCenterStore.displayName(
                context,
                manifest.id,
                manifest.name
            )
        )
    }
    val visibleSource = sourcePlugins.filter { manifest ->
        manifest.matches(
            query,
            category,
            PluginCenterStore.displayName(
                context,
                manifest.id,
                manifest.name
            )
        )
    }
    val visibleRegistered = extraRegistered.filter {
        it.matches(
            query,
            category,
            "",
            PluginCenterStore.displayName(context, it.id, it.name)
        )
    }

    val packagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        installing = true
        installStatus = AppLanguage.tr("正在安装…", "Installing…")
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PluginPackageInstaller.get(context).installFromUri(uri)
                }
            }.onSuccess { manifest ->
                installStatus = AppLanguage.tr(
                    "已安装 ${manifest.name} ${manifest.version}",
                    "Installed ${manifest.name} ${manifest.version}"
                )
                localRevision++
                AppSettingsEvents.notifyChanged()
            }.onFailure {
                installStatus = AppLanguage.tr(
                    "安装失败：${it.message ?: it.javaClass.simpleName}",
                    "Install failed: ${it.message ?: it.javaClass.simpleName}"
                )
            }
            installing = false
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/octet-stream"
        )
    ) { uri ->
        val manifest = exportTarget
        exportTarget = null
        if (uri == null || manifest == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (PluginSourceRegistry.contains(manifest.id)) {
                        PluginSourceLoader.exportToUri(
                            context,
                            manifest.id,
                            uri
                        )
                    } else {
                        PluginPackageInstaller.get(context)
                            .exportToUri(manifest.id, uri)
                    }
                }
            }.onSuccess {
                installStatus = AppLanguage.tr(
                    "已导出 ${manifest.name}",
                    "Exported ${manifest.name}"
                )
            }.onFailure {
                installStatus = AppLanguage.tr(
                    "导出失败：${it.message ?: it.javaClass.simpleName}",
                    "Export failed: ${it.message ?: it.javaClass.simpleName}"
                )
            }
        }
    }

    fun sharePackage(manifest: PluginManifest) {
        scope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) {
                    if (PluginSourceRegistry.contains(manifest.id)) {
                        PluginSourceLoader.createShareFile(
                            context,
                            manifest.id
                        )
                    } else {
                        PluginPackageInstaller.get(context)
                            .createShareFile(manifest.id)
                    }
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    file
                )
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .apply {
                        clipData = ClipData.newRawUri(file.name, uri)
                    }
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(
                    Intent.createChooser(
                        send,
                        AppLanguage.tr("分享插件", "Share plugin")
                    )
                )
            }.onFailure {
                installStatus = AppLanguage.tr(
                    "分享失败：${it.message ?: it.javaClass.simpleName}",
                    "Share failed: ${it.message ?: it.javaClass.simpleName}"
                )
            }
        }
    }

    fun openPluginSettings(
        manifest: PluginManifest,
        sourcePlugin: Boolean
    ) {
        scope.launch {
            settingsError = ""
            runCatching {
                val schemaFile = withContext(Dispatchers.IO) {
                    val file = if (sourcePlugin) {
                        PluginSourceRegistry.settingsSchemaFile(manifest.id)
                    } else {
                        PluginPackageInstaller.get(context)
                            .settingsSchemaFile(manifest.id)
                    }
                    requireNotNull(file) {
                        "Plugin does not provide a settings schema"
                    }
                }
                val view = withContext(Dispatchers.IO) {
                    PluginSettingView.importSchema(schemaFile.readText())
                }
                val loaded = withContext(Dispatchers.IO) {
                    PluginSettingFormStore(
                        AndroidPluginSettings(context, manifest.id),
                        AndroidPluginSecrets(context, manifest.id)
                    ).load(view)
                }
                PluginSettingsTarget(
                    id = manifest.id,
                    title = PluginCenterStore.displayName(
                        context,
                        manifest.id,
                        manifest.name
                    ),
                    view = view,
                    values = loaded.values,
                    configuredSecrets = loaded.configuredSecrets
                )
            }.onSuccess {
                settingsTarget = it
            }.onFailure {
                installStatus = AppLanguage.tr(
                    "无法打开设置：${it.message ?: it.javaClass.simpleName}",
                    "Could not open settings: " +
                        (it.message ?: it.javaClass.simpleName)
                )
            }
        }
    }

    fun savePluginSettings(
        target: PluginSettingsTarget,
        draft: PluginSettingDraft
    ) {
        settingsSaving = true
        settingsError = ""
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PluginSettingFormStore(
                        AndroidPluginSettings(context, target.id),
                        AndroidPluginSecrets(context, target.id)
                    ).save(target.view, draft)
                }
            }.onSuccess {
                settingsSaving = false
                settingsTarget = null
                installStatus = AppLanguage.tr(
                    "已保存 ${target.title} 的设置",
                    "Saved settings for ${target.title}"
                )
                AppSettingsEvents.notifyChanged()
            }.onFailure {
                settingsSaving = false
                settingsError = it.message ?: it.javaClass.simpleName
            }
        }
    }

    fun actionTarget(
        id: String,
        defaultName: String,
        kind: PluginEntryKind
    ) = PluginActionTarget(
        id = id,
        defaultName = defaultName,
        displayName = PluginCenterStore.displayName(
            context,
            id,
            defaultName
        ),
        kind = kind
    )

    fun renameAction(target: PluginActionTarget) = PluginMenuAction(
        AppLanguage.tr("重命名", "Rename"),
        Icons.Rounded.Edit
    ) {
        renameTarget = target
    }

    fun settingsAction(
        manifest: PluginManifest,
        sourcePlugin: Boolean
    ) = PluginMenuAction(
        AppLanguage.tr("设置", "Settings"),
        Icons.Rounded.Settings
    ) {
        openPluginSettings(manifest, sourcePlugin)
    }

    fun toggleAction(
        id: String,
        displayName: String,
        enabled: Boolean
    ) = PluginMenuAction(
        AppLanguage.tr(
            if (enabled) "停用" else "启用",
            if (enabled) "Disable" else "Enable"
        ),
        if (enabled) {
            Icons.Rounded.PauseCircleOutline
        } else {
            Icons.Rounded.PlayCircleOutline
        }
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PluginCenterStore.setEnabled(context, id, !enabled)
                }
            }.onSuccess {
                localRevision++
                installStatus = AppLanguage.tr(
                    if (enabled) {
                        "已停用 $displayName"
                    } else {
                        "已启用 $displayName"
                    },
                    if (enabled) {
                        "Disabled $displayName"
                    } else {
                        "Enabled $displayName"
                    }
                )
            }.onFailure {
                installStatus = AppLanguage.tr(
                    "操作失败：${it.message ?: it.javaClass.simpleName}",
                    "Operation failed: ${it.message ?: it.javaClass.simpleName}"
                )
            }
        }
    }

    fun packageActions(manifest: PluginManifest): List<PluginMenuAction> {
        val target = actionTarget(
            manifest.id,
            manifest.name,
            PluginEntryKind.PACKAGE
        )
        val enabled = PluginCenterStore.isEnabled(context, manifest.id)
        return buildList {
            add(toggleAction(manifest.id, target.displayName, enabled))
            if (enabled && manifest.contributions.settingsPath.isNotBlank()) {
                add(settingsAction(manifest, sourcePlugin = false))
            }
            add(
                renameAction(target)
            )
            add(
                PluginMenuAction(
                    AppLanguage.tr("分享", "Share"),
                    Icons.Rounded.Share
                ) {
                    sharePackage(manifest)
                }
            )
            add(
                PluginMenuAction(
                    AppLanguage.tr("导出", "Export"),
                    Icons.Rounded.Download
                ) {
                    exportTarget = manifest
                    exportPicker.launch(manifest.exportFileName())
                }
            )
            add(
                PluginMenuAction(
                    AppLanguage.tr("删除", "Delete"),
                    Icons.Rounded.DeleteOutline,
                    destructive = true
                ) {
                    deleteTarget = target
                }
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            PluginInstalledHeader(
                installedCount = installedCount,
                installing = installing,
                installStatus = installStatus,
                onInstall = {
                    packagePicker.launch(
                        arrayOf(
                            "application/zip",
                            "application/octet-stream"
                        )
                    )
                }
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null
                    )
                },
                placeholder = {
                    Text(
                        AppLanguage.tr(
                            "搜索插件、能力或发布者",
                            "Search plugins, capabilities or publishers"
                        )
                    )
                }
            )
        }

        item {
            PluginCategoryRow(
                selected = category,
                onSelect = { category = it }
            )
        }

        if (visiblePackages.isEmpty() &&
            visibleSource.isEmpty() &&
            visibleRegistered.isEmpty()
        ) {
            item {
                EmptyPluginResults()
            }
        }
        if (visibleSource.isNotEmpty() || visiblePackages.isNotEmpty()) {
            item { PluginSectionTitle(AppLanguage.tr("已安装插件", "Installed plugins")) }
            item {
                PluginTileGrid {
                    visibleSource.forEach { manifest ->
                        val displayName = PluginCenterStore.displayName(
                            context,
                            manifest.id,
                            manifest.name
                        )
                        val enabled = PluginCenterStore.isEnabled(
                            context,
                            manifest.id
                        )
                        PluginTile(
                            title = displayName,
                            subtitle = AppLanguage.tr(
                                if (enabled) "已安装" else "已停用",
                                if (enabled) "Installed" else "Disabled"
                            ),
                            iconFile = PluginSourceRegistry
                                .iconFile(manifest.id),
                            fallbackIcon = Icons.Rounded.Extension,
                            badge = AppLanguage.tr("源码", "DEV"),
                            enabled = enabled,
                            onOpen = {
                                detail = manifest.toDetail(context)
                            },
                            menuActions = buildList {
                                add(
                                    toggleAction(
                                        manifest.id,
                                        displayName,
                                        enabled
                                    )
                                )
                                if (enabled &&
                                    manifest.contributions.settingsPath.isNotBlank()
                                ) {
                                    add(
                                        settingsAction(
                                            manifest,
                                            sourcePlugin = true
                                        )
                                    )
                                }
                                add(
                                    renameAction(
                                        actionTarget(
                                            manifest.id,
                                            manifest.name,
                                            PluginEntryKind.SOURCE
                                        )
                                    )
                                )
                                add(
                                    PluginMenuAction(
                                        AppLanguage.tr("分享", "Share"),
                                        Icons.Rounded.Share
                                    ) {
                                        sharePackage(manifest)
                                    }
                                )
                                add(
                                    PluginMenuAction(
                                        AppLanguage.tr("导出", "Export"),
                                        Icons.Rounded.Download
                                    ) {
                                        exportTarget = manifest
                                        exportPicker.launch(
                                            manifest.exportFileName()
                                        )
                                    }
                                )
                            }
                        )
                    }
                    visiblePackages.forEach { manifest ->
                        val displayName = PluginCenterStore.displayName(
                            context,
                            manifest.id,
                            manifest.name
                        )
                        val enabled = PluginCenterStore.isEnabled(
                            context,
                            manifest.id
                        )
                        PluginTile(
                            title = displayName,
                            subtitle = AppLanguage.tr(
                                if (enabled) "已安装" else "已停用",
                                if (enabled) "Installed" else "Disabled"
                            ),
                            iconFile = PluginPackageInstaller.get(context)
                                .iconFile(manifest.id),
                            fallbackIcon = Icons.Rounded.Extension,
                            enabled = enabled,
                            onOpen = {
                                detail = manifest.toDetail(context)
                            },
                            menuActions = packageActions(manifest)
                        )
                    }
                }
            }
        }

        if (visibleRegistered.isNotEmpty()) {
            item { PluginSectionTitle(AppLanguage.tr("其它已加载插件", "Other loaded plugins")) }
            item {
                PluginTileGrid {
                    visibleRegistered.forEach { descriptor ->
                        val displayName = PluginCenterStore.displayName(
                            context,
                            descriptor.id,
                            descriptor.name
                        )
                        PluginTile(
                            title = displayName,
                            subtitle = AppLanguage.tr("已加载", "Loaded"),
                            iconFile = null,
                            fallbackIcon = Icons.Rounded.Extension,
                            onOpen = {
                                detail = descriptor.toDetail(
                                    AppLanguage.tr(
                                        "当前进程已加载",
                                        "Loaded in the current process"
                                    ),
                                    AppLanguage.tr(
                                        "外部插件",
                                        "External plugin"
                                    ),
                                    title = displayName
                                )
                            },
                            menuActions = listOf(
                                renameAction(
                                    actionTarget(
                                        descriptor.id,
                                        descriptor.name,
                                        PluginEntryKind.REGISTERED
                                    )
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenamePluginDialog(
            target = target,
            onDismiss = { renameTarget = null },
            onSave = { name ->
                runCatching {
                    PluginCenterStore.setDisplayName(
                        context,
                        target.id,
                        name
                    )
                }.onSuccess {
                    localRevision++
                    if (detail?.id == target.id) {
                        detail = detail?.copy(
                            title = PluginCenterStore.displayName(
                                context,
                                target.id,
                                target.defaultName
                            )
                        )
                    }
                    installStatus = AppLanguage.tr(
                        "已重命名为 ${PluginCenterStore.displayName(
                            context,
                            target.id,
                            target.defaultName
                        )}",
                        "Renamed to ${PluginCenterStore.displayName(
                            context,
                            target.id,
                            target.defaultName
                        )}"
                    )
                    renameTarget = null
                }.onFailure {
                    installStatus = AppLanguage.tr(
                        "重命名失败：${it.message}",
                        "Rename failed: ${it.message}"
                    )
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = {
                if (!deleting) deleteTarget = null
            },
            title = {
                Text(AppLanguage.tr("删除插件", "Delete plugin"))
            },
            text = {
                Text(
                    AppLanguage.tr(
                        "将删除 ${target.displayName} 的安装包及插件私有存储、" +
                            "缓存、SQLite 数据库、设置和密钥。此操作无法撤销。",
                        "This deletes ${target.displayName}, including its package, " +
                            "private storage, cache, SQLite databases, settings " +
                            "and secrets. This cannot be undone."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        deleteTarget = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    PluginPackageInstaller.get(context)
                                        .uninstall(target.id)
                                }
                            }.onSuccess {
                                if (detail?.id == target.id) detail = null
                                localRevision++
                                AppSettingsEvents.notifyChanged()
                                installStatus = AppLanguage.tr(
                                    "已删除 ${target.displayName}",
                                    "Deleted ${target.displayName}"
                                )
                            }.onFailure {
                                installStatus = AppLanguage.tr(
                                    "删除失败：${it.message ?: it.javaClass.simpleName}",
                                    "Delete failed: ${it.message ?: it.javaClass.simpleName}"
                                )
                            }
                            deleting = false
                        }
                    }
                ) {
                    Text(
                        AppLanguage.tr("删除", "Delete"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = { deleteTarget = null }
                ) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    settingsTarget?.let { target ->
        PluginSettingsDialog(
            target = target,
            saving = settingsSaving,
            error = settingsError,
            onDismiss = {
                if (!settingsSaving) {
                    settingsTarget = null
                    settingsError = ""
                }
            },
            onSave = { draft ->
                savePluginSettings(target, draft)
            }
        )
    }

    detail?.let { value ->
        PluginDetailDialog(
            detail = value,
            onBuiltinEnabledChange = { enabled ->
                val builtin = value.builtin
                if (builtin != null) {
                    PluginCenterStore.setEnabled(
                        context,
                        builtin.descriptor.id,
                        enabled
                    )
                    localRevision++
                    detail = value.copy(
                        builtin = builtin.copy(enabled = enabled)
                    )
                }
            },
            onDismiss = { detail = null }
        )
    }
}
