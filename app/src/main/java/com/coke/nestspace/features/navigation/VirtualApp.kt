package com.coke.nestspace.features.navigation

import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.HeadlessMirrorWindow
import android.Manifest
import android.content.Intent
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coke.nestspace.agent.AssistantBridge
import com.coke.nestspace.agent.AssistantConversation
import com.coke.nestspace.agent.ToolPermissionLevel
import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.features.assistant.FloatingDotOverlay
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmProtocol
import com.coke.nestspace.data.ModelOnboardingStore
import com.coke.nestspace.data.VirtualApp
import androidx.core.view.WindowCompat
import com.coke.nestspace.features.vm.VirtualViewModel
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.features.agent.AgentScreen
import com.coke.nestspace.features.agent.RunHistoryScreen
import com.coke.nestspace.features.agent.UserGateHost
import com.coke.nestspace.features.apps.AppManagerScreen
import com.coke.nestspace.features.apps.VirtualInstanceDetailScreen
import com.coke.nestspace.features.browser.BrowserActivity
import com.coke.nestspace.features.desktop.DesktopScreen
import com.coke.nestspace.features.running.RunningAppsScreen
import com.coke.nestspace.features.settings.AboutDetailScreen
import com.coke.nestspace.features.settings.AppProxyScreen
import com.coke.nestspace.features.settings.AppUpdateDetailScreen
import com.coke.nestspace.features.settings.AppearanceDetailScreen
import com.coke.nestspace.features.settings.ChildRunsScreen
import com.coke.nestspace.features.settings.FloatingDotDetailScreen
import com.coke.nestspace.features.settings.AgentToolsScreen
import com.coke.nestspace.features.settings.ModelDetailScreen
import com.coke.nestspace.features.settings.ServicesDetailScreen
import com.coke.nestspace.features.settings.LanguageDetailScreen
import com.coke.nestspace.features.settings.SlotDetailScreen
import com.coke.nestspace.features.settings.LlmConfigEditor
import com.coke.nestspace.features.settings.SystemPromptScreen
import com.coke.nestspace.features.settings.ContainerSelfCheckScreen
import com.coke.nestspace.features.settings.McpClientSettingsScreen
import com.coke.nestspace.features.settings.MemorySkillsScreen
import com.coke.nestspace.features.settings.ModelOnboardingScreen
import com.coke.nestspace.features.agent.McpTasksScreen
import com.coke.nestspace.features.settings.ScheduledTasksScreen
import com.coke.nestspace.features.settings.EvaluationReplayScreen
import com.coke.nestspace.features.settings.TokenUsageScreen
import com.coke.nestspace.features.settings.TtsSettingsScreen
import com.coke.nestspace.features.settings.ToolPermissionScreen
import com.coke.nestspace.features.settings.HostControlScreen
import com.coke.nestspace.features.settings.LocationSimulationScreen
import com.coke.nestspace.features.plugins.PluginCenterActivity
import com.coke.nestspace.features.settings.SettingsHomeScreen
import com.coke.nestspace.features.settings.SettingsCategory
import com.coke.nestspace.integrations.location.MockLocationSettings
import com.coke.nestspace.plugins.host.PluginLlmCatalog
import com.coke.nestspace.integrations.proxy.AppProxySettings
import com.coke.nestspace.integrations.update.AppUpdateManager

internal enum class SpacesPage {
    ROOT,
    SETTINGS_APPS,
    SETTINGS_ASSISTANT,
    SETTINGS_SYSTEM,
    SETTINGS_CONTAINER_ADVANCED,
    SETTINGS_ASSISTANT_ADVANCED,
    APPS,
    INSTANCE_DETAIL,
    SERVICES,
    APPEARANCE,
    FLOATING_DOT,
    MODEL,
    MODEL_EDIT,
    TOOLS,
    TTS,
    SYSTEM_PROMPT,
    MCP_CLIENTS,
    MCP_TASKS,
    SLOTS,
    HOST_CONTROL,
    LOCATION,
    SELF_CHECK,
    MEMORY,
    SCHEDULE,
    EVAL,
    RUN_HISTORY,
    CHILD_RUNS,
    TOKEN_USAGE,
    PERMISSIONS,
    LANGUAGE,
    APP_PROXY,
    APP_UPDATE,
    ABOUT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualApp(viewModel: VirtualViewModel = viewModel()) {
    val apps by viewModel.apps.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val virtualApps by viewModel.virtualApps.collectAsState()
    val selectedSystemApps by viewModel.desktopSystemPackages.collectAsState()
    val wallpaper by viewModel.wallpaper.collectAsState()
    val installing by viewModel.installing.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val llmConfig by viewModel.llmConfig.collectAsState()
    val llmConfigs by viewModel.llmConfigs.collectAsState()
    val activeLlmId by viewModel.activeLlmId.collectAsState()
    val agentTools by viewModel.agentTools.collectAsState()
    val agentToolsLoading by viewModel.agentToolsLoading.collectAsState()
    val llmTesting by viewModel.llmTesting.collectAsState()
    val agentBusy by viewModel.agentBusy.collectAsState()
    val mcpStatus by viewModel.mcpStatus.collectAsState()
    val updateState by AppUpdateManager.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var spacesPage by remember { mutableStateOf(SpacesPage.ROOT) }
    var lastHomeBackAt by remember { mutableStateOf(0L) }
    var mirrorBackEnabled by remember {
        mutableStateOf(HeadlessMirrorWindow.isBackEnabled())
    }
    var childRunsFromAssistant by remember { mutableStateOf(false) }
    var mcpTasksFromAssistant by remember { mutableStateOf(false) }
    var historyFromAssistant by remember { mutableStateOf(false) }
    var modelSettingsFromAssistant by remember { mutableStateOf(false) }
    var assistantSettingsFromAssistant by remember { mutableStateOf(false) }
    var permissionsFromAssistant by remember { mutableStateOf(false) }
    var editingLlm by remember { mutableStateOf<LlmConfig?>(null) }
    var toolsBackPage by remember { mutableStateOf(SpacesPage.ROOT) }
    var ttsBackPage by remember { mutableStateOf(SpacesPage.ROOT) }
    var instanceDetailPackage by remember { mutableStateOf("") }
    var instanceDetailId by remember { mutableStateOf("") }
    var instanceDetailFromDesktop by remember { mutableStateOf(false) }
    var updatePromptArmed by remember { mutableStateOf(false) }
    var showUpdatePrompt by remember { mutableStateOf(false) }
    val apkMimeTypes = arrayOf(
        "application/vnd.android.package-archive",
        "application/octet-stream"
    )
    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.installApks(uris)
    }
    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::setWallpaper)
    }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Whether or not the notification is allowed, the MCP service can
            // still run; the permission only controls its notification.
            viewModel.startMcp()
        }
    val context = LocalContext.current
    var appProxyConfig by remember(context) {
        mutableStateOf(AppProxySettings.load(context))
    }
    LaunchedEffect(viewModel, context) {
        viewModel.llmToast.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val uiScope = rememberCoroutineScope()
    val modelOnboardingStore = remember(context) { ModelOnboardingStore(context) }
    var modelOnboardingCompleted by remember(context) {
        mutableStateOf(modelOnboardingStore.isCompleted() || llmConfigs.isNotEmpty())
    }
    val showModelOnboarding = !modelOnboardingCompleted && llmConfigs.isEmpty()
    val completeModelOnboarding: () -> Unit = {
        modelOnboardingStore.markCompleted()
        modelOnboardingCompleted = true
    }
    var slotMode by remember {
        mutableIntStateOf(ContainerProcessPool.slotLimitMode(context))
    }
    var slotLimit by remember {
        mutableIntStateOf(ContainerProcessPool.slotLimit(context))
    }
    var hostControlEnabled by remember {
        mutableStateOf(com.coke.nestspace.integrations.host.HostControlSettings.isEnabled(context))
    }
    var locationSimulation by remember {
        mutableStateOf(MockLocationSettings.load(context))
    }
    var permissionLevel by remember {
        mutableStateOf(ToolPermissionSettings.load(context))
    }
    var customPermissions by remember {
        mutableStateOf(ToolPermissionSettings.customCategories(context))
    }
    val settingsRevision by AppSettingsEvents.revision.collectAsState()
    val taskCenterRequest by DesktopNavigation.taskCenterRequests.collectAsState()
    var taskCenterFocusId by remember { mutableStateOf<String?>(null) }
    val pluginModels = remember(settingsRevision) {
        PluginLlmCatalog.snapshot()
    }
    LaunchedEffect(settingsRevision) {
        slotMode = ContainerProcessPool.slotLimitMode(context)
        slotLimit = ContainerProcessPool.slotLimit(context)
        hostControlEnabled = com.coke.nestspace.integrations.host.HostControlSettings.isEnabled(context)
        locationSimulation = MockLocationSettings.load(context)
        permissionLevel = ToolPermissionSettings.load(context)
        customPermissions = ToolPermissionSettings.customCategories(context)
        appProxyConfig = AppProxySettings.load(context)
    }
    val startMcp: () -> Unit = {
        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startMcp()
        }
    }

    LaunchedEffect(Unit) {
        DesktopNavigation.requests.collect { tab = 0 }
    }
    LaunchedEffect(taskCenterRequest.requestId) {
        if (taskCenterRequest.requestId > 0) {
            taskCenterFocusId = taskCenterRequest.taskId
            mcpTasksFromAssistant = true
            spacesPage = SpacesPage.MCP_TASKS
            tab = 1
        }
    }
    LaunchedEffect(context) {
        updatePromptArmed = AppUpdateManager.checkOncePerDay(context).hasUnreadUpdate
    }
    LaunchedEffect(
        updateState.release?.version,
        updateState.skippedVersion,
        updatePromptArmed,
        showModelOnboarding
    ) {
        if (!showModelOnboarding &&
            updatePromptArmed &&
            AppUpdateManager.shouldPrompt(context, updateState)
        ) {
            AppUpdateManager.markPrompted(context, updateState)
            updatePromptArmed = false
            showUpdatePrompt = true
        }
    }
    LaunchedEffect(Unit) {
        val openModelSettings = {
            completeModelOnboarding()
            modelSettingsFromAssistant = true
            spacesPage = SpacesPage.MODEL
            tab = 1
        }
        if (ModelSettingsNavigation.consumeRequest()) {
            openModelSettings()
        }
        ModelSettingsNavigation.requests.collect {
            if (ModelSettingsNavigation.consumeRequest()) {
                openModelSettings()
            }
        }
    }
    LaunchedEffect(AppLanguage.revision) {
        AppLanguage.revision
        AssistantConversation.refreshWelcome()
    }
    LaunchedEffect(messages, agentBusy) {
        AssistantBridge.publish(agentBusy, messages)
    }
    LaunchedEffect(Unit) {
        AssistantBridge.sendRequests.collect { viewModel.runAgent(it) }
    }
    LaunchedEffect(llmConfigs.isNotEmpty()) {
        if (llmConfigs.isNotEmpty() && !modelOnboardingCompleted) {
            completeModelOnboarding()
        }
    }
    LaunchedEffect(Unit) {
        AssistantBridge.externalRequest.collect { text ->
            if (!text.isNullOrBlank()) {
                tab = 2
                AssistantBridge.consumeExternalRequest()
                viewModel.runAgent(text)
            }
        }
    }
    LaunchedEffect(tab) {
        if (tab == 0) lastHomeBackAt = 0L
        if (tab != 1) {
            spacesPage = SpacesPage.ROOT
            editingLlm = null
            childRunsFromAssistant = false
            historyFromAssistant = false
            modelSettingsFromAssistant = false
            assistantSettingsFromAssistant = false
            permissionsFromAssistant = false
        }
    }
    // Space management and running-apps management belong to the container
    // surface, so keep the container color there instead of the host color.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, tab) {
        val containerTab = tab == 1 || tab == 3
        fun apply(visible: Boolean) {
            FloatingDotOverlay.setContainerSurfaceVisible(
                context,
                "container-ui",
                visible && containerTab
            )
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> apply(true)
                Lifecycle.Event.ON_PAUSE -> apply(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        apply(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            FloatingDotOverlay.setContainerSurfaceVisible(context, "container-ui", false)
        }
    }

    val rootView = LocalView.current
    LaunchedEffect(tab, wallpaper) {
        val activity = rootView.context.findActivity() ?: return@LaunchedEffect
        WindowCompat.getInsetsController(activity.window, rootView)
            .isAppearanceLightStatusBars = tab != 0 || wallpaper == null
    }

    val inSpacesDetail = tab == 1 && spacesPage != SpacesPage.ROOT
    // Detail pages contain switches, text fields and bottom actions along the
    // right edge; the floating dot otherwise sits on top of them.
    DisposableEffect(inSpacesDetail) {
        FloatingDotOverlay.setOverlayHidden(context, "settings-detail", inSpacesDetail)
        onDispose {
            FloatingDotOverlay.setOverlayHidden(context, "settings-detail", false)
        }
    }
    DisposableEffect(showModelOnboarding) {
        FloatingDotOverlay.setOverlayHidden(context, "model-onboarding", showModelOnboarding)
        onDispose {
            FloatingDotOverlay.setOverlayHidden(context, "model-onboarding", false)
        }
    }
    val navigateBack: () -> Unit = {
        when {
            tab != 1 -> tab = 0
            spacesPage == SpacesPage.CHILD_RUNS && childRunsFromAssistant -> {
                childRunsFromAssistant = false
                spacesPage = SpacesPage.ROOT
                tab = 2
            }
            spacesPage == SpacesPage.MCP_TASKS && mcpTasksFromAssistant -> {
                mcpTasksFromAssistant = false
                spacesPage = SpacesPage.ROOT
                tab = 2
            }
            spacesPage == SpacesPage.RUN_HISTORY && historyFromAssistant -> {
                historyFromAssistant = false
                spacesPage = SpacesPage.ROOT
                tab = 2
            }
            spacesPage == SpacesPage.MODEL_EDIT -> {
                editingLlm = null
                spacesPage = SpacesPage.MODEL
            }
            spacesPage == SpacesPage.TOOLS -> spacesPage = toolsBackPage
            spacesPage == SpacesPage.TTS -> spacesPage = ttsBackPage
            spacesPage == SpacesPage.INSTANCE_DETAIL -> {
                if (instanceDetailFromDesktop) {
                    spacesPage = SpacesPage.ROOT
                    tab = 0
                } else {
                    spacesPage = SpacesPage.APPS
                }
            }
            spacesPage == SpacesPage.MODEL && modelSettingsFromAssistant -> {
                modelSettingsFromAssistant = false
                spacesPage = SpacesPage.ROOT
                tab = 2
            }
            spacesPage == SpacesPage.PERMISSIONS && permissionsFromAssistant -> {
                permissionsFromAssistant = false
                spacesPage = SpacesPage.ROOT
                tab = 2
            }
            spacesPage == SpacesPage.SETTINGS_ASSISTANT &&
                assistantSettingsFromAssistant -> {
                assistantSettingsFromAssistant = false
                spacesPage = SpacesPage.ROOT
                tab = 2
            }
            spacesPage in setOf(
                SpacesPage.HOST_CONTROL,
                SpacesPage.SELF_CHECK
            ) -> spacesPage = SpacesPage.SETTINGS_CONTAINER_ADVANCED
            spacesPage in setOf(
                SpacesPage.MCP_CLIENTS,
                SpacesPage.MEMORY,
                SpacesPage.SCHEDULE,
                SpacesPage.EVAL,
                SpacesPage.CHILD_RUNS,
                SpacesPage.TOKEN_USAGE,
                SpacesPage.SERVICES
            ) -> spacesPage = SpacesPage.SETTINGS_ASSISTANT_ADVANCED
            spacesPage in setOf(
                SpacesPage.APPS,
                SpacesPage.SLOTS,
                SpacesPage.LOCATION
            ) -> spacesPage = SpacesPage.SETTINGS_APPS
            spacesPage in setOf(
                SpacesPage.MODEL,
                SpacesPage.SYSTEM_PROMPT,
                SpacesPage.PERMISSIONS,
                SpacesPage.FLOATING_DOT
            ) -> spacesPage = SpacesPage.SETTINGS_ASSISTANT
            spacesPage in setOf(
                SpacesPage.APPEARANCE,
                SpacesPage.LANGUAGE,
                SpacesPage.APP_PROXY
            ) -> spacesPage = SpacesPage.SETTINGS_SYSTEM
            spacesPage == SpacesPage.SETTINGS_CONTAINER_ADVANCED ->
                spacesPage = SpacesPage.SETTINGS_APPS
            spacesPage == SpacesPage.SETTINGS_ASSISTANT_ADVANCED ->
                spacesPage = SpacesPage.SETTINGS_ASSISTANT
            spacesPage != SpacesPage.ROOT -> spacesPage = SpacesPage.ROOT
            else -> tab = 0
        }
    }
    BackHandler(enabled = tab != 0) { navigateBack() }
    BackHandler(enabled = showModelOnboarding) {
        completeModelOnboarding()
    }
    DisposableEffect(Unit) {
        val listener: (Boolean) -> Unit = { mirrorBackEnabled = it }
        HeadlessMirrorWindow.addBackEnabledListener(listener)
        onDispose { HeadlessMirrorWindow.removeBackEnabledListener(listener) }
    }
    BackHandler(
        enabled = tab == 0 && !showModelOnboarding && !mirrorBackEnabled
    ) {
        val now = SystemClock.elapsedRealtime()
        if (lastHomeBackAt > 0L && now - lastHomeBackAt <= 2_000L) {
            context.findActivity()?.finish()
        } else {
            lastHomeBackAt = now
            Toast.makeText(
                context,
                AppLanguage.tr("再按一次返回退出", "Press back again to exit"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val appBarTitle = virtualAppTitle(tab, spacesPage, editingLlm)

    Scaffold(
            topBar = {
                if (tab != 0) {
                    TopAppBar(
                        title = { Text(appBarTitle) },
                        navigationIcon = {
                            IconButton(onClick = navigateBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = if (inSpacesDetail) {
                                        AppLanguage.tr("返回空间设置", "Back to space settings")
                                    } else {
                                        AppLanguage.tr("返回桌面", "Back to desktop")
                                    }
                                )
                            }
                        }
                    )
                }
            }
        ) { padding ->
        val contentPadding = if (tab == 0) {
            PaddingValues(bottom = padding.calculateBottomPadding())
        } else {
            padding
        }
        Box(Modifier.padding(contentPadding)) {
            when (tab) {
                0 -> DesktopScreen(
                    virtualApps = virtualApps.filter { it.spaceId == "virtual-1" },
                    systemApps = apps.filter { it.packageName in selectedSystemApps },
                    wallpaper = wallpaper,
                    onLaunchVirtual = { packageName, instanceId ->
                        viewModel.launchVirtual(packageName, instanceId)
                    },
                    onLaunchSystem = viewModel::launchApp,
                    onOpenBrowser = {
                        context.startActivity(Intent(context, BrowserActivity::class.java))
                    },
                    onOpenNotebook = {
                        context.startActivity(
                            Intent(context, com.coke.nestspace.features.notebook.NotebookActivity::class.java)
                        )
                    },
                    onOpenAgent = { tab = 2 },
                    onOpenPlugins = {
                        context.startActivity(
                            Intent(context, PluginCenterActivity::class.java)
                        )
                    },
                    onOpenSpaces = { spacesPage = SpacesPage.ROOT; tab = 1 },
                    onOpenModel = {
                        modelSettingsFromAssistant = false
                        spacesPage = SpacesPage.MODEL
                        tab = 1
                    },
                    onOpenPermissions = {
                        permissionsFromAssistant = false
                        spacesPage = SpacesPage.PERMISSIONS
                        tab = 1
                    },
                    onOpenAppManager = { spacesPage = SpacesPage.APPS; tab = 1 },
                    onLaunchShortcut = viewModel::launchAppShortcut,
                    loadShortcuts = viewModel::loadAppShortcuts,
                    onOpenRunning = { tab = 3 },
                    showUpdateBadge = updateState.hasUnreadUpdate,
                    onUninstallVirtual = viewModel::uninstallVirtual,
                    onDeleteVirtualInstance = viewModel::deleteVirtualInstance,
                    onOpenVirtualInstance = { packageName, instanceId ->
                        instanceDetailPackage = packageName
                        instanceDetailId = instanceId
                        instanceDetailFromDesktop = true
                        spacesPage = SpacesPage.INSTANCE_DETAIL
                        tab = 1
                    },
                    onRemoveSystemApp = viewModel::toggleDesktopSystemApp
                )

                1 -> when (spacesPage) {
                    SpacesPage.ROOT,
                    SpacesPage.SETTINGS_APPS,
                    SpacesPage.SETTINGS_ASSISTANT,
                    SpacesPage.SETTINGS_SYSTEM,
                    SpacesPage.SETTINGS_CONTAINER_ADVANCED,
                    SpacesPage.SETTINGS_ASSISTANT_ADVANCED -> {
                        SettingsHomeScreen(
                            category = when (spacesPage) {
                                SpacesPage.SETTINGS_APPS -> SettingsCategory.APPS
                                SpacesPage.SETTINGS_ASSISTANT -> SettingsCategory.ASSISTANT
                                SpacesPage.SETTINGS_SYSTEM -> SettingsCategory.SYSTEM
                                SpacesPage.SETTINGS_CONTAINER_ADVANCED ->
                                    SettingsCategory.CONTAINER_ADVANCED
                                SpacesPage.SETTINGS_ASSISTANT_ADVANCED ->
                                    SettingsCategory.ASSISTANT_ADVANCED
                                else -> null
                            },
                            appCount = virtualApps.count { it.spaceId == "virtual-1" },
                            mcpStatus = mcpStatus,
                            hasWallpaper = wallpaper != null,
                            llmConfig = llmConfig,
                            languageMode = AppLanguage.mode(),
                            slotLimitMode = slotMode,
                            slotLimit = slotLimit,
                            hostControlEnabled = hostControlEnabled,
                            locationSimulation = locationSimulation,
                            onOpenApps = { spacesPage = SpacesPage.APPS },
                            onOpenHostControl = { spacesPage = SpacesPage.HOST_CONTROL },
                            onOpenLocationSimulation = { spacesPage = SpacesPage.LOCATION },
                            appProxy = appProxyConfig,
                            onOpenAppProxy = { spacesPage = SpacesPage.APP_PROXY },
                            onOpenServices = { spacesPage = SpacesPage.SERVICES },
                            onOpenAppearance = { spacesPage = SpacesPage.APPEARANCE },
                            onOpenFloatingDot = { spacesPage = SpacesPage.FLOATING_DOT },
                            onOpenModel = { spacesPage = SpacesPage.MODEL },
                            onOpenTools = {
                                toolsBackPage = if (
                                    spacesPage == SpacesPage.SETTINGS_ASSISTANT
                                ) {
                                    SpacesPage.SETTINGS_ASSISTANT
                                } else {
                                    SpacesPage.ROOT
                                }
                                spacesPage = SpacesPage.TOOLS
                            },
                            onOpenTts = {
                                ttsBackPage = if (
                                    spacesPage == SpacesPage.SETTINGS_ASSISTANT
                                ) {
                                    SpacesPage.SETTINGS_ASSISTANT
                                } else {
                                    SpacesPage.ROOT
                                }
                                spacesPage = SpacesPage.TTS
                            },
                            onOpenSystemPrompt = { spacesPage = SpacesPage.SYSTEM_PROMPT },
                            onOpenMcpClients = { spacesPage = SpacesPage.MCP_CLIENTS },
                            onOpenMemory = { spacesPage = SpacesPage.MEMORY },
                            onOpenSchedule = { spacesPage = SpacesPage.SCHEDULE },
                            onOpenEvaluation = { spacesPage = SpacesPage.EVAL },
                            onOpenChildRuns = {
                                childRunsFromAssistant = false
                                spacesPage = SpacesPage.CHILD_RUNS
                            },
                            onOpenTokenUsage = { spacesPage = SpacesPage.TOKEN_USAGE },
                            permissionLevel = permissionLevel,
                            onOpenPermissions = { spacesPage = SpacesPage.PERMISSIONS },
                            onOpenSlots = { spacesPage = SpacesPage.SLOTS },
                            onOpenSelfCheck = { spacesPage = SpacesPage.SELF_CHECK },
                            onOpenLanguage = { spacesPage = SpacesPage.LANGUAGE },
                            updateState = updateState,
                            onOpenUpdate = { spacesPage = SpacesPage.APP_UPDATE },
                            onOpenAbout = { spacesPage = SpacesPage.ABOUT },
                            onOpenCategory = { category ->
                                spacesPage = when (category) {
                                    SettingsCategory.APPS -> SpacesPage.SETTINGS_APPS
                                    SettingsCategory.ASSISTANT -> SpacesPage.SETTINGS_ASSISTANT
                                    SettingsCategory.SYSTEM -> SpacesPage.SETTINGS_SYSTEM
                                    SettingsCategory.CONTAINER_ADVANCED ->
                                        SpacesPage.SETTINGS_CONTAINER_ADVANCED
                                    SettingsCategory.ASSISTANT_ADVANCED ->
                                        SpacesPage.SETTINGS_ASSISTANT_ADVANCED
                                }
                            }
                        )
                    }

                    SpacesPage.APPS -> AppManagerScreen(
                        virtualApps = virtualApps.filter { it.spaceId == "virtual-1" },
                        systemApps = apps,
                        installing = installing,
                        notice = notice,
                        onInstallApk = { apkPicker.launch(apkMimeTypes) },
                        onLaunchVirtual = { packageName, instanceId ->
                            viewModel.launchVirtual(packageName, instanceId)
                        },
                        onMultiOpen = viewModel::multiOpenVirtual,
                        onUninstall = viewModel::uninstallVirtual,
                        onOpenInstance = { packageName, instanceId ->
                            instanceDetailPackage = packageName
                            instanceDetailId = instanceId
                            instanceDetailFromDesktop = false
                            spacesPage = SpacesPage.INSTANCE_DETAIL
                        },
                        onImportApps = viewModel::importHostApps,
                        onRefreshApps = viewModel::refreshApps
                    )

                    SpacesPage.INSTANCE_DETAIL -> VirtualInstanceDetailScreen(
                        virtualApps = virtualApps.filter { it.spaceId == "virtual-1" },
                        packageName = instanceDetailPackage,
                        instanceId = instanceDetailId,
                        onLaunch = { packageName, instanceId ->
                            viewModel.launchVirtual(packageName, instanceId)
                        },
                        onClearData = viewModel::clearVirtualInstanceData
                    )

                    SpacesPage.SERVICES -> ServicesDetailScreen(
                        mcpStatus = mcpStatus,
                        token = mcpStatus.token.ifBlank { viewModel.mcpToken() },
                        onStartMcp = startMcp,
                        onStopMcp = viewModel::stopMcp,
                        onToggleLan = viewModel::setMcpLan,
                        onOpenOverlaySettings = viewModel::openOverlaySettings
                    )

                    SpacesPage.APPEARANCE -> AppearanceDetailScreen(
                        hasWallpaper = wallpaper != null,
                        onPickWallpaper = { wallpaperPicker.launch(arrayOf("image/*")) },
                        onClearWallpaper = viewModel::clearWallpaper
                    )

                    SpacesPage.FLOATING_DOT -> FloatingDotDetailScreen()

                    SpacesPage.MODEL -> ModelDetailScreen(
                        configs = llmConfigs,
                        activeId = activeLlmId,
                        pluginModels = pluginModels,
                        onAddLlm = {
                            editingLlm = LlmConfig.defaultsFor(LlmProtocol.ANTHROPIC)
                            spacesPage = SpacesPage.MODEL_EDIT
                        },
                        onAddPluginModel = viewModel::usePluginLlmModel,
                        onEditLlm = {
                            editingLlm = it
                            spacesPage = SpacesPage.MODEL_EDIT
                        },
                        onDeleteLlm = viewModel::deleteLlmConfig,
                        onSelectLlm = viewModel::selectLlmConfig
                    )

                    SpacesPage.TOOLS -> {
                        LaunchedEffect(Unit) {
                            viewModel.refreshAgentTools()
                        }
                        AgentToolsScreen(
                            tools = agentTools,
                            loading = agentToolsLoading,
                            onToggleTool = viewModel::setAgentToolEnabled
                        )
                    }

                    SpacesPage.TTS -> TtsSettingsScreen()

                    SpacesPage.MODEL_EDIT -> {
                        val config = editingLlm
                        if (config != null) {
                            LlmConfigEditor(
                                config = config,
                                testing = llmTesting,
                                onSave = {
                                    viewModel.saveLlmConfig(it)
                                    editingLlm = null
                                    spacesPage = SpacesPage.MODEL
                                },
                                onTest = viewModel::testLlm
                            )
                        }
                    }

                    SpacesPage.SYSTEM_PROMPT -> SystemPromptScreen()

                    SpacesPage.MCP_CLIENTS -> McpClientSettingsScreen()
                    SpacesPage.MCP_TASKS -> McpTasksScreen(taskCenterFocusId)

                    SpacesPage.HOST_CONTROL -> HostControlScreen()
                    SpacesPage.LOCATION -> LocationSimulationScreen()
                    SpacesPage.SLOTS -> SlotDetailScreen(
                        mode = slotMode,
                        limit = slotLimit,
                        onSelect = { value ->
                            // Lowering the limit kills slots synchronously;
                            // keep the wait off the main thread.
                            uiScope.launch {
                                withContext(Dispatchers.IO) {
                                    ContainerProcessPool.applySlotLimit(context, value)
                                }
                                slotMode = ContainerProcessPool.slotLimitMode(context)
                                slotLimit = ContainerProcessPool.slotLimit(context)
                            }
                        }
                    )

                    SpacesPage.SELF_CHECK -> ContainerSelfCheckScreen()

                    SpacesPage.MEMORY -> MemorySkillsScreen()

                    SpacesPage.SCHEDULE -> ScheduledTasksScreen()

                    SpacesPage.EVAL -> EvaluationReplayScreen()

                    SpacesPage.RUN_HISTORY -> RunHistoryScreen(
                        context = context,
                        busy = agentBusy,
                        onRestore = { run, events ->
                            if (AssistantConversation.restoreRun(run, events)) {
                                historyFromAssistant = false
                                spacesPage = SpacesPage.ROOT
                                tab = 2
                            }
                        }
                    )

                    SpacesPage.CHILD_RUNS -> ChildRunsScreen()

                    SpacesPage.TOKEN_USAGE -> TokenUsageScreen()

                    SpacesPage.PERMISSIONS -> ToolPermissionScreen(
                        level = permissionLevel,
                        customCategories = customPermissions,
                        onSelectLevel = { level ->
                            permissionLevel = level
                            ToolPermissionSettings.save(context, level)
                            AppSettingsEvents.notifyChanged()
                        },
                        onToggleCategory = { category, enabled ->
                            val next = if (enabled) {
                                customPermissions + category
                            } else {
                                customPermissions - category
                            }
                            customPermissions = next
                            permissionLevel = ToolPermissionLevel.CUSTOM
                            ToolPermissionSettings.save(context, ToolPermissionLevel.CUSTOM)
                            ToolPermissionSettings.saveCustomCategories(context, next)
                            AppSettingsEvents.notifyChanged()
                        }
                    )

                    SpacesPage.LANGUAGE -> LanguageDetailScreen(
                        mode = AppLanguage.mode(),
                        onSelect = { AppLanguage.setMode(context, it) }
                    )

                    SpacesPage.APP_PROXY -> AppProxyScreen()

                    SpacesPage.APP_UPDATE -> AppUpdateDetailScreen(
                        state = updateState,
                        onCheck = {
                            uiScope.launch {
                                AppUpdateManager.checkNow(context)
                            }
                        },
                        onDownload = { AppUpdateManager.openDownload(context) },
                        onOpenRelease = { AppUpdateManager.openReleasePage(context) },
                        onSkip = { AppUpdateManager.skipCurrentVersion(context) },
                        onRestoreSkipped = {
                            AppUpdateManager.restoreSkippedVersion(context)
                        }
                    )

                    SpacesPage.ABOUT -> AboutDetailScreen()
                }

                3 -> RunningAppsScreen(
                    apps = virtualApps
                )

                2 -> AgentScreen(
                    messages = messages,
                    llmConfig = llmConfig,
                    llmConfigs = llmConfigs,
                    activeLlmId = activeLlmId,
                    busy = agentBusy,
                    onSend = viewModel::runAgent,
                    onStop = viewModel::stopAgent,
                    onClear = viewModel::clearMessages,
                    onOpenHistory = {
                        historyFromAssistant = true
                        spacesPage = SpacesPage.RUN_HISTORY
                        tab = 1
                    },
                    onOpenMcpTasks = {
                        taskCenterFocusId = null
                        mcpTasksFromAssistant = true
                        spacesPage = SpacesPage.MCP_TASKS
                        tab = 1
                    },
                    onOpenSettings = {
                        assistantSettingsFromAssistant = true
                        spacesPage = SpacesPage.SETTINGS_ASSISTANT
                        tab = 1
                    },
                    onOpenModelSettings = {
                        modelSettingsFromAssistant = true
                        spacesPage = SpacesPage.MODEL
                        tab = 1
                    },
                    permissionLevel = permissionLevel,
                    onPermissionLevelChange = { level ->
                        permissionLevel = level
                        ToolPermissionSettings.save(context, level)
                        AppSettingsEvents.notifyChanged()
                    },
                    onOpenPermissionSettings = {
                        permissionsFromAssistant = true
                        spacesPage = SpacesPage.PERMISSIONS
                        tab = 1
                    },
                    onOpenChildRuns = {
                        childRunsFromAssistant = true
                        spacesPage = SpacesPage.CHILD_RUNS
                        tab = 1
                    },
                    onSelectModel = viewModel::selectLlmConfig,
                    onReasoningEffortChange = viewModel::setReasoningEffort
                )
            }
        }
    }

    if (showModelOnboarding) {
        ModelOnboardingScreen(
            onConfigure = {
                completeModelOnboarding()
                modelSettingsFromAssistant = false
                editingLlm = LlmConfig.defaultsFor(LlmProtocol.ANTHROPIC)
                spacesPage = SpacesPage.MODEL_EDIT
                tab = 1
            },
            onSkip = completeModelOnboarding
        )
    }

    val updateRelease = updateState.release
    if (showUpdatePrompt && updateRelease != null) {
        AlertDialog(
            onDismissRequest = { showUpdatePrompt = false },
            title = {
                Text(AppLanguage.tr("发现新版本", "Update available"))
            },
            text = {
                Text(
                    AppLanguage.tr(
                        "NestSpace ${updateRelease.tagName} 已发布。前往 App 更新页查看更新内容并下载。",
                        "NestSpace ${updateRelease.tagName} is available. Open App update " +
                            "to review the release and download it."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUpdatePrompt = false
                        spacesPage = SpacesPage.APP_UPDATE
                        tab = 1
                    }
                ) {
                    Text(AppLanguage.tr("去更新", "Update"))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            AppUpdateManager.skipCurrentVersion(context)
                            showUpdatePrompt = false
                        }
                    ) {
                        Text(AppLanguage.tr("跳过此版本", "Skip"))
                    }
                    TextButton(onClick = { showUpdatePrompt = false }) {
                        Text(AppLanguage.tr("稍后", "Later"))
                    }
                }
            }
        )
    }

    UserGateHost()
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

