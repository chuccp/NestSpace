package com.coke.nestspace.features.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.data.AppShortcut
import com.coke.nestspace.data.VirtualApp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal data class DesktopOverlayState(
    val menuSlot: DesktopSlot?,
    val blankMenuOpen: Boolean,
    val menuPosition: IntOffset,
    val menuShortcuts: List<AppShortcut>,
    val slots: List<DesktopSlot>,
    val virtualInstanceCounts: Map<String, Int>,
    val openFolder: DesktopSlot.Folder?,
    val folders: List<DesktopSlot.Folder>,
    val appEntries: List<LauncherEntry>,
    val folderPickerApp: LauncherEntry?,
    val addingFolder: DesktopSlot.Folder?,
    val folderAppMenu: LauncherEntry?,
    val renamingFolder: DesktopSlot.Folder?,
    val renameText: String,
    val appInfo: LauncherEntry?,
    val virtualApps: List<VirtualApp>,
    val confirmUninstall: LauncherEntry?,
    val confirmDeleteInstance: LauncherEntry?,
    val confirmRemove: LauncherEntry?,
    val showUpdateBadge: Boolean
)

internal data class DesktopOverlayActions(
    val onDismissMenus: () -> Unit,
    val onMenuSizeChange: (IntSize) -> Unit,
    val onLaunchShortcut: (String, AppShortcut) -> Unit,
    val onShowFolderPicker: (LauncherEntry) -> Unit,
    val onMoveAppToEnd: (String, Boolean) -> Unit,
    val onOpenVirtualInstance: (String, String) -> Unit,
    val onShowAppInfo: (LauncherEntry) -> Unit,
    val onRequestDeleteInstance: (LauncherEntry) -> Unit,
    val onRequestUninstall: (LauncherEntry) -> Unit,
    val onRequestRemove: (LauncherEntry) -> Unit,
    val onOpenFolder: (String) -> Unit,
    val onRequestRenameFolder: (DesktopSlot.Folder) -> Unit,
    val onDissolveFolder: (String) -> Unit,
    val onCreateEmptyFolder: () -> Unit,
    val onOpenModel: () -> Unit,
    val onOpenPermissions: () -> Unit,
    val onCloseFolder: () -> Unit,
    val onRequestAddToFolder: (String) -> Unit,
    val onLaunch: (LauncherEntry) -> Unit,
    val onFolderAppMenu: (LauncherEntry) -> Unit,
    val onDismissFolderAppMenu: () -> Unit,
    val onRemoveFromFolder: (String) -> Unit,
    val onDismissFolderPicker: () -> Unit,
    val onAddToFolder: (String, String) -> Unit,
    val onDismissAddToFolder: () -> Unit,
    val onDismissRename: () -> Unit,
    val onRenameTextChange: (String) -> Unit,
    val onRenameFolder: (String, String) -> Unit,
    val onDismissAppInfo: () -> Unit,
    val onOpenAppManager: () -> Unit,
    val onDismissUninstall: () -> Unit,
    val onUninstall: (String) -> Unit,
    val onDismissDeleteInstance: () -> Unit,
    val onDeleteInstance: (String, String) -> Unit,
    val onDismissRemove: () -> Unit,
    val onRemoveFromDesktop: (String) -> Unit,
    val onRemoveSystemApp: (String) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopOverlays(
    state: DesktopOverlayState,
    actions: DesktopOverlayActions
) {
    val context = LocalContext.current
    val menuSlot = state.menuSlot
    val blankMenuOpen = state.blankMenuOpen
    val menuPosition = state.menuPosition
    val menuShortcuts = state.menuShortcuts
    val slots = state.slots
    val virtualInstanceCounts = state.virtualInstanceCounts
    val openFolder = state.openFolder
    val folders = state.folders
    val appEntries = state.appEntries
    val folderPickerApp = state.folderPickerApp
    val addingFolder = state.addingFolder
    val folderAppMenu = state.folderAppMenu
    val renamingFolder = state.renamingFolder
    val renameText = state.renameText
    val appInfo = state.appInfo
    val virtualApps = state.virtualApps
    val confirmUninstall = state.confirmUninstall
    val confirmDeleteInstance = state.confirmDeleteInstance
    val confirmRemove = state.confirmRemove
    val showUpdateBadge = state.showUpdateBadge
    menuSlot?.let { slot ->
        // Visual only: a pointer-consuming scrim would block dragging an app
        // while its context menu is open. Taps outside are handled by the root
        // observer, so the grid stays draggable underneath.
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(8f)
                .background(Color.Black.copy(alpha = 0.08f))
        )
        Surface(
            modifier = Modifier
                .offset { menuPosition }
                .width(260.dp)
                .zIndex(9f)
                .onSizeChanged { actions.onMenuSizeChange(it) },
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                when (slot) {
                    is DesktopSlot.App -> {
                        val entry = slot.entry
                        val menuIndex = slots.indexOfFirst { it.id == slot.id }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Box(Modifier.size(40.dp)) {
                                    AppGlyph(entry, Modifier.matchParentSize())
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        when (entry.kind) {
                                            LauncherKind.VIRTUAL ->
                                                AppLanguage.tr(
                                                    "虚拟应用 · 容器",
                                                    "Virtual app · container"
                                                )
                                            LauncherKind.SYSTEM ->
                                                AppLanguage.tr(
                                                    "系统应用 · 桌面",
                                                    "System app · desktop"
                                                )
                                            else -> AppLanguage.tr(
                                                "NestSpace 功能",
                                                "NestSpace feature"
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            menuShortcuts.take(4).forEach { shortcut ->
                                ShortcutAction(shortcut) {
                                    actions.onDismissMenus()
                                    actions.onLaunchShortcut(entry.packageName, shortcut)
                                }
                            }
                            if (menuShortcuts.isNotEmpty()) {
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            }
                            MenuAction(AppLanguage.tr("打开", "Open"), Icons.Default.OpenInNew) {
                                actions.onDismissMenus()
                                actions.onLaunch(entry)
                            }
                            if (folders.isNotEmpty()) {
                                MenuAction(
                                    AppLanguage.tr("添加到文件夹", "Add to folder"),
                                    Icons.Default.Folder
                                ) {
                                    actions.onDismissMenus()
                                    actions.onShowFolderPicker(entry)
                                }
                            }
                            if (menuIndex > 0) {
                                MenuAction(
                                    AppLanguage.tr("移到最前", "Move to front"),
                                    Icons.Default.VerticalAlignTop
                                ) {
                                    actions.onDismissMenus()
                                    actions.onMoveAppToEnd(entry.id, true)
                                }
                            }
                            if (menuIndex >= 0 && menuIndex < slots.lastIndex) {
                                MenuAction(
                                    AppLanguage.tr("移到最后", "Move to back"),
                                    Icons.Default.VerticalAlignBottom
                                ) {
                                    actions.onDismissMenus()
                                    actions.onMoveAppToEnd(entry.id, false)
                                }
                            }
                            MenuAction(
                                if (entry.kind == LauncherKind.VIRTUAL &&
                                    entry.instanceId != null
                                ) {
                                    AppLanguage.tr("实例信息", "Instance info")
                                } else {
                                    AppLanguage.tr("应用信息", "App info")
                                },
                                Icons.Default.Info
                            ) {
                                actions.onDismissMenus()
                                when {
                                    entry.kind == LauncherKind.VIRTUAL &&
                                        entry.instanceId != null ->
                                        actions.onOpenVirtualInstance(
                                            entry.packageName,
                                            entry.instanceId
                                        )
                                    entry.kind == LauncherKind.SYSTEM ->
                                        openSystemAppInfo(context, entry.packageName)
                                    else -> actions.onShowAppInfo(entry)
                                }
                            }
                            when (entry.kind) {
                                LauncherKind.VIRTUAL -> {
                                    val multipleInstances =
                                        (virtualInstanceCounts[entry.packageName] ?: 0) > 1
                                    MenuAction(
                                        if (multipleInstances) {
                                            AppLanguage.tr("删除此实例", "Delete this instance")
                                        } else {
                                            AppLanguage.tr("卸载应用", "Uninstall app")
                                        },
                                        Icons.Default.DeleteOutline,
                                        danger = true
                                    ) {
                                        actions.onDismissMenus()
                                        if (multipleInstances && entry.instanceId != null) {
                                            actions.onRequestDeleteInstance(entry)
                                        } else {
                                            actions.onRequestUninstall(entry)
                                        }
                                    }
                                }

                                LauncherKind.SYSTEM -> MenuAction(
                                    AppLanguage.tr("从桌面移除", "Remove from desktop"),
                                    Icons.Default.DeleteOutline,
                                    danger = true
                                ) {
                                    actions.onDismissMenus()
                                    actions.onRequestRemove(entry)
                                }

                                else -> Unit
                            }
                        }
                    }

                    is DesktopSlot.Folder -> {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                FolderGlyph(
                                    folder = slot,
                                    onWallpaper = false,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        slot.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        AppLanguage.trCount(
                                            slot.apps.size,
                                            "${slot.apps.size} 个应用",
                                            "${slot.apps.size} app",
                                            "${slot.apps.size} apps"
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            MenuAction(
                                AppLanguage.tr("打开文件夹", "Open folder"),
                                Icons.Default.FolderOpen
                            ) {
                                actions.onDismissMenus()
                                actions.onOpenFolder(slot.id)
                            }
                            MenuAction(
                                AppLanguage.tr("重命名", "Rename"),
                                Icons.Default.CreateNewFolder
                            ) {
                                actions.onDismissMenus()
                                actions.onRequestRenameFolder(slot)
                            }
                            MenuAction(
                                AppLanguage.tr("解散文件夹", "Dissolve folder"),
                                Icons.Default.DeleteOutline,
                                danger = true
                            ) {
                                actions.onDismissMenus()
                                actions.onDissolveFolder(slot.id)
                            }
                        }
                    }
                }
            }
        }
    }

    if (blankMenuOpen) {
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(8f)
                .background(Color.Black.copy(alpha = 0.08f))
        )
        Surface(
            modifier = Modifier
                .offset { menuPosition }
                .width(260.dp)
                .zIndex(9f)
                .onSizeChanged { actions.onMenuSizeChange(it) },
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                MenuAction(
                    AppLanguage.tr("新建文件夹", "New folder"),
                    Icons.Default.CreateNewFolder
                ) {
                    actions.onDismissMenus()
                    actions.onCreateEmptyFolder()
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                MenuAction(
                    AppLanguage.tr("模型设置", "Model settings"),
                    Icons.Default.SmartToy
                ) {
                    actions.onDismissMenus()
                    actions.onOpenModel()
                }
                MenuAction(
                    AppLanguage.tr("权限级别", "Permissions"),
                    Icons.Default.Security
                ) {
                    actions.onDismissMenus()
                    actions.onOpenPermissions()
                }
            }
        }
    }


    openFolder?.let { folder ->
        ModalBottomSheet(onDismissRequest = { actions.onCloseFolder() }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        folder.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { actions.onRequestAddToFolder(folder.id) }) {
                        Icon(Icons.Default.Add, contentDescription = AppLanguage.tr("添加应用到文件夹", "Add app to folder"))
                    }
                    IconButton(onClick = { actions.onCloseFolder() }) {
                        Icon(Icons.Default.Close, contentDescription = AppLanguage.tr("关闭", "Close"))
                    }
                }
                Text(
                    AppLanguage.trCount(
                        folder.apps.size,
                        "${folder.apps.size} 个应用 · 长按应用可移出",
                        "${folder.apps.size} app · long-press an app to remove it",
                        "${folder.apps.size} apps · long-press an app to remove it"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = folder.apps,
                        key = { it.id }
                    ) { app ->
                        FolderAppCell(
                            entry = app,
                            showUpdateBadge = showUpdateBadge,
                            onClick = {
                                actions.onCloseFolder()
                                actions.onLaunch(app)
                            },
                            onLongPress = { actions.onFolderAppMenu(app) },
                            onDragOut = {
                                actions.onCloseFolder()
                                actions.onDismissFolderAppMenu()
                                actions.onRemoveFromFolder(app.id)
                            }
                        )
                    }
                }
            }
        }
    }

    folderPickerApp?.let { app ->
        AlertDialog(
            onDismissRequest = { actions.onDismissFolderPicker() },
            title = { Text(AppLanguage.tr("添加到文件夹", "Add to folder")) },
            text = {
                Column {
                    folders.forEach { folder ->
                        TextButton(
                            onClick = {
                                actions.onAddToFolder(app.id, folder.id)
                                actions.onDismissFolderPicker()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(folder.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actions.onDismissFolderPicker() }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    addingFolder?.let { folder ->
        val candidates = appEntries.filter { entry ->
            folder.apps.none { it.id == entry.id }
        }
        AlertDialog(
            onDismissRequest = { actions.onDismissAddToFolder() },
            title = { Text(AppLanguage.tr("添加到「${folder.name}」", "Add to \"${folder.name}\"")) },
            text = {
                if (candidates.isEmpty()) {
                    Text(AppLanguage.tr("桌面上没有其他应用", "No other apps on the desktop"))
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(candidates, key = { it.id }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        actions.onAddToFolder(app.id, folder.id)
                                        actions.onDismissAddToFolder()
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(36.dp)) {
                                    MiniAppGlyph(app)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(app.label)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actions.onDismissAddToFolder() }) {
                    Text(AppLanguage.tr("完成", "Done"))
                }
            }
        )
    }

    folderAppMenu?.let { app ->
        AlertDialog(
            onDismissRequest = { actions.onDismissFolderAppMenu() },
            title = { Text(app.label) },
            text = { Text(AppLanguage.tr("这个应用位于文件夹中。", "This app is inside a folder.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onDismissFolderAppMenu()
                        actions.onCloseFolder()
                        actions.onLaunch(app)
                    }
                ) { Text(AppLanguage.tr("打开", "Open")) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        actions.onRemoveFromFolder(app.id)
                        actions.onDismissFolderAppMenu()
                    }
                ) { Text(AppLanguage.tr("移出文件夹", "Remove from folder")) }
            }
        )
    }

    renamingFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { actions.onDismissRename() },
            title = { Text(AppLanguage.tr("重命名文件夹", "Rename folder")) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { actions.onRenameTextChange(it) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        actions.onRenameFolder(folder.id, state.renameText.trim())
                        actions.onDismissRename()
                    }
                ) { Text(AppLanguage.tr("保存", "Save")) }
            },
            dismissButton = {
                TextButton(onClick = { actions.onDismissRename() }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    appInfo?.let { entry ->
        val app = virtualApps.firstOrNull { it.packageName == entry.packageName }
        AlertDialog(
            onDismissRequest = { actions.onDismissAppInfo() },
            icon = {
                Box(Modifier.size(52.dp)) {
                    AppGlyph(entry, Modifier.matchParentSize())
                }
            },
            title = { Text(entry.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.packageName, style = MaterialTheme.typography.bodySmall)
                    if (app != null) {
                        Text(
                            AppLanguage.tr(
                                "版本 ${app.versionName ?: "-"}（${app.versionCode}）",
                                "Version ${app.versionName ?: "-"} (${app.versionCode})"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            AppLanguage.tr(
                                "${app.instances.size} 个实例 · 虚拟空间 1",
                                "${app.instances.size} instance(s) · Virtual space 1"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            AppLanguage.tr("系统应用", "System app"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onDismissAppInfo()
                        actions.onOpenAppManager()
                    }
                ) { Text(AppLanguage.tr("应用管理", "App manager")) }
            },
            dismissButton = {
                TextButton(onClick = { actions.onDismissAppInfo() }) {
                    Text(AppLanguage.tr("关闭", "Close"))
                }
            }
        )
    }

    confirmUninstall?.let { entry ->
        AlertDialog(
            onDismissRequest = { actions.onDismissUninstall() },
            title = { Text(AppLanguage.tr("卸载应用", "Uninstall app")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "将删除 ${entry.label} 及其全部实例数据，此操作无法撤销。",
                        "This deletes ${entry.label} and all instance data. " +
                            "This cannot be undone."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Keep the icon until the engine reports success; the
                        // slot disappears from the resolved layout as soon as
                        // the installed-app list no longer contains it.
                        actions.onDismissUninstall()
                        actions.onUninstall(entry.packageName)
                    }
                ) { Text(AppLanguage.tr("卸载", "Uninstall")) }
            },
            dismissButton = {
                TextButton(onClick = { actions.onDismissUninstall() }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    confirmDeleteInstance?.let { entry ->
        AlertDialog(
            onDismissRequest = { actions.onDismissDeleteInstance() },
            title = { Text(AppLanguage.tr("删除此实例", "Delete this instance")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "只删除 ${entry.label} 的实例数据和桌面图标，其他实例不会受影响。此操作无法撤销。",
                        "This deletes only the instance data and desktop icon for " +
                            "${entry.label}. Other instances are not affected. " +
                            "This cannot be undone."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onDismissDeleteInstance()
                        entry.instanceId?.let {
                            actions.onDeleteInstance(entry.packageName, it)
                        }
                    }
                ) { Text(AppLanguage.tr("删除实例", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { actions.onDismissDeleteInstance() }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    confirmRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { actions.onDismissRemove() },
            title = { Text(AppLanguage.tr("从桌面移除", "Remove from desktop")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "把 ${entry.label} 从桌面移除？应用仍保留在系统中。",
                        "Remove ${entry.label} from the desktop? The app stays " +
                            "installed on the system."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onRemoveFromDesktop(entry.id)
                        actions.onDismissRemove()
                        actions.onRemoveSystemApp(entry.packageName)
                    }
                ) { Text(AppLanguage.tr("移除", "Remove")) }
            },
            dismissButton = {
                TextButton(onClick = { actions.onDismissRemove() }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

}
