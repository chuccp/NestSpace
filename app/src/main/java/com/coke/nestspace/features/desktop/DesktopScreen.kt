package com.coke.nestspace.features.desktop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.coke.nestspace.features.assistant.FloatingDotOverlay
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.data.AppShortcut
import com.coke.nestspace.data.VirtualApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopScreen(
    virtualApps: List<VirtualApp>,
    systemApps: List<com.coke.nestspace.data.AppInfo>,
    wallpaper: ImageBitmap?,
    onLaunchVirtual: (String, String?) -> Unit,
    onLaunchSystem: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenNotebook: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenSpaces: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAppManager: () -> Unit,
    onLaunchShortcut: (String, AppShortcut) -> Unit,
    loadShortcuts: suspend (String) -> List<AppShortcut>,
    onOpenRunning: () -> Unit,
    showUpdateBadge: Boolean,
    onUninstallVirtual: (String) -> Unit,
    onDeleteVirtualInstance: (String, String) -> Unit,
    onOpenVirtualInstance: (String, String) -> Unit,
    onRemoveSystemApp: (String) -> Unit
) {
    val context = LocalContext.current
    val languageRevision = AppLanguage.revision
    val dateLabel = remember(languageRevision) {
        val chinese = AppLanguage.isChinese()
        val pattern = if (chinese) "M月d日 EEEE" else "EEEE, MMM d"
        SimpleDateFormat(pattern, if (chinese) Locale.CHINA else Locale.getDefault()).format(Date())
    }
    val entries = remember(virtualApps, systemApps, languageRevision) {
        buildEntries(virtualApps, systemApps)
    }
    val virtualInstanceCounts = remember(virtualApps) {
        virtualApps.associate { it.packageName to it.instances.size }
    }
    val appEntries = remember(entries) {
        entries.filter {
            it.kind == LauncherKind.VIRTUAL ||
                it.kind == LauncherKind.SYSTEM ||
                it.kind == LauncherKind.NOTEBOOK ||
                it.kind == LauncherKind.PLUGINS
        }
    }
    val dockEntries = remember(entries) {
        entries.filter {
            it.kind != LauncherKind.VIRTUAL &&
                it.kind != LauncherKind.SYSTEM &&
                it.kind != LauncherKind.NOTEBOOK &&
                it.kind != LauncherKind.PLUGINS
        }
    }
    val entriesById = remember(appEntries) { appEntries.associateBy { it.id } }
    var savedLayout by remember { mutableStateOf(DesktopLayoutStore.load(context)) }
    // Folders created empty keep rendering while the user adds their first
    // app. The allowance is intentionally in-memory: after a restart a folder
    // with only one app goes back to the normal auto-dissolve behavior.
    var fillingFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    val slots = remember(savedLayout, appEntries, fillingFolders) {
        resolveSlots(savedLayout, appEntries, fillingFolders)
    }
    val folders = slots.filterIsInstance<DesktopSlot.Folder>()

    fun commit(next: List<DesktopSlot>) {
        val saved = next.map { it.toSaved() }
        savedLayout = saved
        DesktopLayoutStore.save(context, saved)
    }

    fun launch(entry: LauncherEntry) {
        when (entry.kind) {
            LauncherKind.VIRTUAL -> onLaunchVirtual(entry.packageName, entry.instanceId)
            LauncherKind.SYSTEM -> onLaunchSystem(entry.packageName)
            LauncherKind.BROWSER -> onOpenBrowser()
            LauncherKind.NOTEBOOK -> onOpenNotebook()
            LauncherKind.AGENT -> onOpenAgent()
            LauncherKind.PLUGINS -> onOpenPlugins()
            LauncherKind.SPACES -> onOpenSpaces()
            LauncherKind.RUNNING -> onOpenRunning()
        }
    }

    var menuSlot by remember { mutableStateOf<DesktopSlot?>(null) }
    var menuItemBounds by remember { mutableStateOf(Rect.Zero) }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    var blankMenuPoint by remember { mutableStateOf<Offset?>(null) }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var addingToFolderId by remember { mutableStateOf<String?>(null) }
    var folderPickerApp by remember { mutableStateOf<LauncherEntry?>(null) }
    var renamingFolder by remember { mutableStateOf<DesktopSlot.Folder?>(null) }
    var renameText by remember { mutableStateOf("") }
    var folderAppMenu by remember { mutableStateOf<LauncherEntry?>(null) }
    var confirmUninstall by remember { mutableStateOf<LauncherEntry?>(null) }
    var confirmDeleteInstance by remember { mutableStateOf<LauncherEntry?>(null) }
    var confirmRemove by remember { mutableStateOf<LauncherEntry?>(null) }
    var appInfo by remember { mutableStateOf<LauncherEntry?>(null) }
    var menuShortcuts by remember { mutableStateOf<List<AppShortcut>>(emptyList()) }

    fun createEmptyFolder() {
        val name = AppLanguage.tr("文件夹", "Folder")
        val folder = DesktopSlot.Folder(
            id = "folder:${System.currentTimeMillis()}",
            name = name,
            apps = emptyList()
        )
        commit(slots + folder)
        renamingFolder = folder
        renameText = name
    }

    fun addToFolder(appId: String, folderId: String) {
        val app = entriesById[appId] ?: return
        val next = ArrayList<DesktopSlot>()
        var found = false
        var targetWasEmpty = false
        slots.forEach { slot ->
            when (slot) {
                is DesktopSlot.App -> if (slot.entry.id != appId) next += slot
                is DesktopSlot.Folder -> {
                    val remaining = slot.apps.filterNot { it.id == appId }
                    if (slot.id == folderId) {
                        targetWasEmpty = slot.apps.isEmpty()
                        next += slot.copy(apps = remaining + app)
                        found = true
                    } else if (remaining.size == 1) {
                        next += DesktopSlot.App(remaining.first())
                    } else if (remaining.isNotEmpty()) {
                        next += slot.copy(apps = remaining)
                    }
                }
            }
        }
        if (found) {
            fillingFolders = if (targetWasEmpty) {
                fillingFolders + folderId
            } else {
                fillingFolders - folderId
            }
            commit(next)
        }
    }

    fun removeFromFolder(appId: String) {
        val app = entriesById[appId] ?: return
        val next = ArrayList<DesktopSlot>()
        slots.forEach { slot ->
            when (slot) {
                is DesktopSlot.App -> next += slot
                is DesktopSlot.Folder -> {
                    val remaining = slot.apps.filterNot { it.id == appId }
                    if (remaining.size == 1) {
                        next += DesktopSlot.App(remaining.first())
                    } else if (remaining.isNotEmpty()) {
                        next += slot.copy(apps = remaining)
                    }
                }
            }
        }
        next += DesktopSlot.App(app)
        commit(next)
    }

    fun addAppToTopLevelEnd(appId: String, front: Boolean) {
        val app = entriesById[appId] ?: return
        val next = ArrayList<DesktopSlot>()
        slots.forEach { slot ->
            when (slot) {
                is DesktopSlot.App -> if (slot.entry.id != appId) next += slot
                is DesktopSlot.Folder -> {
                    val remaining = slot.apps.filterNot { it.id == appId }
                    if (remaining.size == 1) {
                        next += DesktopSlot.App(remaining.first())
                    } else if (remaining.isNotEmpty()) {
                        next += slot.copy(apps = remaining)
                    }
                }
            }
        }
        val appSlot = DesktopSlot.App(app)
        if (front) next.add(0, appSlot) else next.add(appSlot)
        commit(next)
    }

    fun renameFolder(folderId: String, name: String) {
        if (name.isBlank()) return
        commit(
            slots.map {
                if (it is DesktopSlot.Folder && it.id == folderId) {
                    it.copy(name = name)
                } else {
                    it
                }
            }
        )
    }

    fun dissolveFolder(folderId: String) {
        val next = ArrayList<DesktopSlot>()
        slots.forEach { slot ->
            if (slot is DesktopSlot.Folder && slot.id == folderId) {
                slot.apps.forEach { next += DesktopSlot.App(it) }
            } else {
                next += slot
            }
        }
        commit(next)
        fillingFolders = fillingFolders - folderId
        if (openFolderId == folderId) openFolderId = null
    }

    fun removeApp(appId: String) {
        commit(
            slots.mapNotNull { slot ->
                when (slot) {
                    is DesktopSlot.App -> if (slot.entry.id == appId) null else slot
                    is DesktopSlot.Folder -> {
                        val remaining = slot.apps.filterNot { it.id == appId }
                        when {
                            remaining.isEmpty() -> null
                            remaining.size == 1 -> DesktopSlot.App(remaining.first())
                            else -> slot.copy(apps = remaining)
                        }
                    }
                }
            }
        )
    }

    val gridState = rememberLazyGridState()
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var folderTargetIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val gridColumns = when {
        configuration.screenWidthDp >= 1_000 -> 8
        configuration.screenWidthDp >= 760 -> 7
        configuration.screenWidthDp >= 480 -> 5
        else -> 4
    }
    val tabletLayout = configuration.smallestScreenWidthDp >= 480
    val haptic = LocalHapticFeedback.current
    val dragThreshold = with(density) { 8.dp.toPx() }
    // Deliberate product rule: a small overlap creates a folder, while a
    // larger overlap (30%+ including full overlap) switches to reorder/shift.
    val folderMinOverlap = 0.02f
    val folderMaxOverlap = 0.30f

    fun showMenuFor(index: Int, bounds: Rect) {
        val slot = slots.getOrNull(index) ?: return
        menuSize = IntSize.Zero
        menuItemBounds = if (bounds == Rect.Zero) {
            Rect(40f, 180f, 280f, 300f)
        } else {
            bounds
        }
        blankMenuPoint = null
        menuSlot = slot
    }

    fun resetDrag() {
        dragIndex = -1
        dragTargetIndex = -1
        folderTargetIndex = -1
        dragOffset = Offset.Zero
        dragDistance = 0f
    }

    fun combineAppsIntoFolder(draggedId: String, targetId: String) {
        val dragged = entriesById[draggedId] ?: return
        val target = entriesById[targetId] ?: return
        if (dragged.id == target.id) return
        val folderId = "folder:${System.currentTimeMillis()}"
        val name = AppLanguage.tr("文件夹", "Folder")
        val next = ArrayList<DesktopSlot>()
        var inserted = false
        slots.forEach { slot ->
            when (slot) {
                is DesktopSlot.App -> when (slot.entry.id) {
                    draggedId -> Unit
                    targetId -> {
                        next += DesktopSlot.Folder(folderId, name, listOf(target, dragged))
                        inserted = true
                    }
                    else -> next += slot
                }
                is DesktopSlot.Folder -> next += slot
            }
        }
        if (!inserted) {
            next += DesktopSlot.Folder(folderId, name, listOf(target, dragged))
        }
        commit(next)
    }

    fun handleDragEnd() {
        val from = dragIndex
        val to = dragTargetIndex
        val folderTarget = folderTargetIndex
        val currentSlots = slots
        if (from in currentSlots.indices) {
            val moved = dragDistance > dragThreshold || (to in currentSlots.indices && to != from)
            val folderSlot = currentSlots.getOrNull(folderTarget)
            val moving = currentSlots.getOrNull(from)
            if (moved && moving is DesktopSlot.App && folderSlot is DesktopSlot.App) {
                // Dropping with enough overlap creates a folder instead of
                // merely reordering the grid.
                combineAppsIntoFolder(moving.entry.id, folderSlot.entry.id)
            } else if (moved && to in currentSlots.indices) {
                val target = currentSlots[to]
                if (target is DesktopSlot.Folder && moving is DesktopSlot.App) {
                    addToFolder(moving.entry.id, target.id)
                } else if (from != to) {
                    commit(moveSlot(currentSlots, from, to))
                }
            }
        }
        resetDrag()
    }

    val overlayOpen = menuSlot != null || blankMenuPoint != null || openFolderId != null ||
        addingToFolderId != null || folderPickerApp != null ||
        renamingFolder != null || folderAppMenu != null ||
        confirmUninstall != null || confirmDeleteInstance != null ||
            confirmRemove != null || appInfo != null
    LaunchedEffect(overlayOpen) {
        FloatingDotOverlay.setOverlayHidden(context, "desktop-overlay", overlayOpen)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    FloatingDotOverlay.setContainerDesktopVisible(context, true)
                Lifecycle.Event.ON_PAUSE ->
                    FloatingDotOverlay.setContainerDesktopVisible(context, false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            FloatingDotOverlay.setContainerDesktopVisible(context, true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            FloatingDotOverlay.setContainerDesktopVisible(context, false)
            FloatingDotOverlay.setOverlayHidden(context, "desktop-overlay", false)
        }
    }

    LaunchedEffect(openFolderId, slots) {
        if (openFolderId != null && slots.none {
                it is DesktopSlot.Folder && it.id == openFolderId
            }
        ) {
            openFolderId = null
        }
    }
    LaunchedEffect(addingToFolderId, slots) {
        if (addingToFolderId != null && slots.none {
                it is DesktopSlot.Folder && it.id == addingToFolderId
            }
        ) {
            addingToFolderId = null
        }
    }

    BackHandler(enabled = menuSlot != null || blankMenuPoint != null) {
        menuSlot = null
        blankMenuPoint = null
    }

    LaunchedEffect(menuSlot?.id) {
        val slot = menuSlot
        menuShortcuts = emptyList()
        if (slot is DesktopSlot.App && slot.entry.kind == LauncherKind.VIRTUAL) {
            val loaded = runCatching { loadShortcuts(slot.entry.packageName) }
                .getOrDefault(emptyList())
            if (menuSlot?.id == slot.id) menuShortcuts = loaded
        }
    }

    val openFolder = openFolderId?.let { id ->
        slots.filterIsInstance<DesktopSlot.Folder>().firstOrNull { it.id == id }
    }
    val addingFolder = addingToFolderId?.let { id ->
        slots.filterIsInstance<DesktopSlot.Folder>().firstOrNull { it.id == id }
    }

    val menuWidthPx = with(density) { 260.dp.roundToPx() }
    val menuGap = with(density) { 6.dp.roundToPx() }
    val menuMargin = with(density) { 12.dp.roundToPx() }
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val menuWidth = if (menuSize.width > 0) menuSize.width else menuWidthPx
    val menuPosition = if (menuItemBounds == Rect.Zero) {
        IntOffset(menuMargin, menuMargin)
    } else {
        val x = (menuItemBounds.center.x - menuWidth / 2f).roundToInt()
            .coerceIn(
                menuMargin,
                (screenWidthPx - menuWidth - menuMargin).coerceAtLeast(menuMargin)
            )
        val below = menuItemBounds.bottom.roundToInt() + menuGap
        val above = menuItemBounds.top.roundToInt() - menuSize.height - menuGap
        val y = when {
            menuSize.height > 0 && below + menuSize.height <= screenHeightPx - menuMargin ->
                below
            menuSize.height > 0 && above >= menuMargin -> above
            else -> below
        }.coerceIn(
            menuMargin,
            (screenHeightPx - menuSize.height - menuMargin).coerceAtLeast(menuMargin)
        )
        IntOffset(x, y)
    }

    val previewSlots = if (folderTargetIndex < 0 &&
        dragIndex in slots.indices &&
        dragTargetIndex in slots.indices &&
        dragIndex != dragTargetIndex
    ) {
        moveSlot(slots, dragIndex, dragTargetIndex)
    } else {
        null
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(gridState) {
                val dockHeight = with(density) { 112.dp.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    val start = down.position
                    val finished = withTimeoutOrNull(
                        viewConfiguration.longPressTimeoutMillis
                    ) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!change.pressed) break
                            if ((change.position - start).getDistance() >
                                viewConfiguration.touchSlop
                            ) {
                                break
                            }
                        }
                        true
                    }
                    if (finished == null) {
                        val onApp = gridState.layoutInfo.visibleItemsInfo.any { info ->
                            start.x >= info.offset.x &&
                                start.x < info.offset.x + info.size.width &&
                                start.y >= info.offset.y &&
                                start.y < info.offset.y + info.size.height
                        }
                        if (!onApp && start.y < size.height - dockHeight) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuSize = IntSize.Zero
                            menuItemBounds = Rect(start.x, start.y, start.x, start.y)
                            menuSlot = null
                            blankMenuPoint = start
                        }
                    } else if (menuSlot != null || blankMenuPoint != null) {
                        // The scrim is visual-only so apps stay draggable; close
                        // the menu when the same gesture turns into a tap/drag.
                        menuSlot = null
                        blankMenuPoint = null
                    }
                }
            }
    ) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.24f))
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 22.dp, end = 22.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                dateLabel,
                style = if (tabletLayout) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.titleMedium
                },
                fontWeight = FontWeight.Medium,
                color = labelColor(wallpaper != null)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (tabletLayout) 24.dp else 16.dp,
                top = if (tabletLayout) 92.dp else 78.dp,
                end = if (tabletLayout) 24.dp else 16.dp,
                bottom = 128.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = slots,
                key = { _, slot -> slot.id }
            ) { index, slot ->
                val shift = if (previewSlots != null && index != dragIndex) {
                    val newIndex = previewSlots.indexOfFirst { it.id == slot.id }
                    val currentInfo = gridState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == index }
                    val targetInfo = gridState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == newIndex }
                    if (currentInfo != null && targetInfo != null) {
                        Offset(
                            (targetInfo.offset.x - currentInfo.offset.x).toFloat(),
                            (targetInfo.offset.y - currentInfo.offset.y).toFloat()
                        )
                    } else {
                        Offset.Zero
                    }
                } else {
                    Offset.Zero
                }
                DesktopSlotCell(
                    slot = slot,
                    index = index,
                    dragging = dragIndex == index,
                    dragOffset = dragOffset,
                    shift = shift,
                    dropTarget = dragIndex != index && dragTargetIndex == index,
                    folderTarget = folderTargetIndex == index,
                    onWallpaper = wallpaper != null,
                    tabletLayout = tabletLayout,
                    showUpdateBadge = showUpdateBadge,
                    onLaunch = {
                        when (slot) {
                            is DesktopSlot.App -> launch(slot.entry)
                            is DesktopSlot.Folder -> openFolderId = slot.id
                        }
                    },
                    onDragStart = { startedIndex, bounds ->
                        dragIndex = startedIndex
                        dragTargetIndex = startedIndex
                        folderTargetIndex = -1
                        dragOffset = Offset.Zero
                        dragDistance = 0f
                        showMenuFor(startedIndex, bounds)
                    },
                    onDrag = { startedIndex, amount ->
                        if (dragIndex < 0) {
                            dragIndex = startedIndex
                            dragTargetIndex = startedIndex
                        }
                        dragOffset += amount
                        dragDistance += amount.getDistance()
                        if (dragDistance > dragThreshold && menuSlot != null) {
                            menuSlot = null
                        }
                        val info = gridState.layoutInfo.visibleItemsInfo
                        val startInfo = info.firstOrNull { it.index == dragIndex }
                        if (startInfo != null) {
                            val dragged = Rect(
                                startInfo.offset.x + dragOffset.x,
                                startInfo.offset.y + dragOffset.y,
                                startInfo.offset.x + dragOffset.x + startInfo.size.width,
                                startInfo.offset.y + dragOffset.y + startInfo.size.height
                            )
                            var bestFolder = -1
                            var bestRatio = 0f
                            info.forEach { item ->
                                if (item.index == dragIndex ||
                                    slots.getOrNull(item.index) !is DesktopSlot.App
                                ) {
                                    return@forEach
                                }
                                val target = Rect(
                                    item.offset.x.toFloat(),
                                    item.offset.y.toFloat(),
                                    (item.offset.x + item.size.width).toFloat(),
                                    (item.offset.y + item.size.height).toFloat()
                                )
                                val left = maxOf(dragged.left, target.left)
                                val top = maxOf(dragged.top, target.top)
                                val right = minOf(dragged.right, target.right)
                                val bottom = minOf(dragged.bottom, target.bottom)
                                if (right <= left || bottom <= top) return@forEach
                                val overlap = (right - left) * (bottom - top)
                                val smaller = minOf(
                                    dragged.width * dragged.height,
                                    target.width * target.height
                                )
                                if (smaller > 0f) {
                                    val ratio = overlap / smaller
                                    if (ratio > bestRatio) {
                                        bestRatio = ratio
                                        bestFolder = item.index
                                    }
                                }
                            }
                            folderTargetIndex = bestFolder.takeIf {
                                bestRatio >= folderMinOverlap && bestRatio < folderMaxOverlap
                            } ?: -1

                            if (folderTargetIndex >= 0) {
                                dragTargetIndex = folderTargetIndex
                            } else {
                                val center = Offset(
                                    startInfo.offset.x + startInfo.size.width / 2f,
                                    startInfo.offset.y + startInfo.size.height / 2f
                                ) + dragOffset
                                val target = info.minByOrNull { item ->
                                    val dx = item.offset.x + item.size.width / 2f - center.x
                                    val dy = item.offset.y + item.size.height / 2f - center.y
                                    dx * dx + dy * dy
                                }?.index ?: dragIndex
                                dragTargetIndex = target.coerceIn(
                                    0,
                                    (slots.lastIndex).coerceAtLeast(0)
                                )
                            }
                        }
                    },
                    onDragEnd = { handleDragEnd() },
                    onDragCancel = {
                        menuSlot = null
                        resetDrag()
                    }
                )
            }
        }

        LauncherDock(
            entries = dockEntries,
            onWallpaper = wallpaper != null,
            showUpdateBadge = showUpdateBadge,
            onLaunch = { launch(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }

DesktopOverlays(
    state = DesktopOverlayState(
        menuSlot = menuSlot,
        blankMenuOpen = blankMenuPoint != null,
        menuPosition = menuPosition,
        menuShortcuts = menuShortcuts,
        slots = slots,
        virtualInstanceCounts = virtualInstanceCounts,
        openFolder = openFolder,
        folders = folders,
        appEntries = appEntries,
        folderPickerApp = folderPickerApp,
        addingFolder = addingFolder,
        folderAppMenu = folderAppMenu,
        renamingFolder = renamingFolder,
        renameText = renameText,
        appInfo = appInfo,
        virtualApps = virtualApps,
        confirmUninstall = confirmUninstall,
        confirmDeleteInstance = confirmDeleteInstance,
        confirmRemove = confirmRemove,
        showUpdateBadge = showUpdateBadge
    ),
    actions = DesktopOverlayActions(
        onDismissMenus = {
            menuSlot = null
            blankMenuPoint = null
        },
        onMenuSizeChange = { menuSize = it },
        onLaunchShortcut = onLaunchShortcut,
        onShowFolderPicker = { folderPickerApp = it },
        onMoveAppToEnd = { id, front -> addAppToTopLevelEnd(id, front) },
        onOpenVirtualInstance = onOpenVirtualInstance,
        onShowAppInfo = { appInfo = it },
        onRequestDeleteInstance = { confirmDeleteInstance = it },
        onRequestUninstall = { confirmUninstall = it },
        onRequestRemove = { confirmRemove = it },
        onOpenFolder = { openFolderId = it },
        onRequestRenameFolder = {
            renamingFolder = it
            renameText = it.name
        },
        onDissolveFolder = ::dissolveFolder,
        onCreateEmptyFolder = ::createEmptyFolder,
        onOpenModel = onOpenModel,
        onOpenPermissions = onOpenPermissions,
        onCloseFolder = { openFolderId = null },
        onRequestAddToFolder = { addingToFolderId = it },
        onLaunch = ::launch,
        onFolderAppMenu = { folderAppMenu = it },
        onDismissFolderAppMenu = { folderAppMenu = null },
        onRemoveFromFolder = ::removeFromFolder,
        onDismissFolderPicker = { folderPickerApp = null },
        onAddToFolder = ::addToFolder,
        onDismissAddToFolder = { addingToFolderId = null },
        onDismissRename = { renamingFolder = null },
        onRenameTextChange = { renameText = it },
        onRenameFolder = ::renameFolder,
        onDismissAppInfo = { appInfo = null },
        onOpenAppManager = onOpenAppManager,
        onDismissUninstall = { confirmUninstall = null },
        onUninstall = onUninstallVirtual,
        onDismissDeleteInstance = { confirmDeleteInstance = null },
        onDeleteInstance = onDeleteVirtualInstance,
        onDismissRemove = { confirmRemove = null },
        onRemoveFromDesktop = ::removeApp,
        onRemoveSystemApp = onRemoveSystemApp
    )
)
}
