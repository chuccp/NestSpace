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

internal fun virtualAppTitle(
    tab: Int,
    spacesPage: SpacesPage,
    editingLlm: LlmConfig?
): String = when {
        tab == 1 -> when (spacesPage) {
            SpacesPage.ROOT -> AppLanguage.tr("空间设置", "Space settings")
            SpacesPage.SETTINGS_APPS -> AppLanguage.tr("应用与容器", "Apps & containers")
            SpacesPage.SETTINGS_ASSISTANT -> AppLanguage.tr("AI 助手设置", "AI assistant settings")
            SpacesPage.SETTINGS_SYSTEM -> AppLanguage.tr("系统与网络", "System & network")
            SpacesPage.SETTINGS_CONTAINER_ADVANCED ->
                AppLanguage.tr("容器高级", "Container advanced")
            SpacesPage.SETTINGS_ASSISTANT_ADVANCED ->
                AppLanguage.tr("高级助手工具", "Advanced assistant tools")
            SpacesPage.APPS -> AppLanguage.tr("应用管理", "App management")
            SpacesPage.INSTANCE_DETAIL -> AppLanguage.tr("实例信息", "Instance info")
            SpacesPage.SERVICES -> AppLanguage.tr("MCP 控制服务", "MCP control service")
            SpacesPage.APPEARANCE -> AppLanguage.tr("外观", "Appearance")
            SpacesPage.FLOATING_DOT -> AppLanguage.tr("悬浮球", "Floating dot")
            SpacesPage.MODEL -> AppLanguage.tr("模型设置", "Model settings")
            SpacesPage.TOOLS -> AppLanguage.tr("插件工具", "Plugin tools")
            SpacesPage.TTS -> AppLanguage.tr("TTS 配置", "TTS settings")
            SpacesPage.MODEL_EDIT ->
                if (editingLlm?.id.isNullOrBlank()) {
                    AppLanguage.tr("添加模型", "Add model")
                } else {
                    AppLanguage.tr("修改模型", "Edit model")
                }
            SpacesPage.SYSTEM_PROMPT -> AppLanguage.tr("系统提示词", "System prompt")
            SpacesPage.MCP_CLIENTS -> AppLanguage.tr("MCP 工具", "MCP tools")
            SpacesPage.MCP_TASKS -> AppLanguage.tr("助手任务", "Assistant tasks")
            SpacesPage.SLOTS -> AppLanguage.tr("容器槽位", "Container slots")
            SpacesPage.HOST_CONTROL -> AppLanguage.tr("宿主控制（Root）", "Host control (root)")
            SpacesPage.LOCATION -> AppLanguage.tr("坐标模拟", "Location simulation")
            SpacesPage.SELF_CHECK -> AppLanguage.tr("容器自检", "Container self-check")
            SpacesPage.MEMORY -> AppLanguage.tr("记忆与技能", "Memory & skills")
            SpacesPage.SCHEDULE -> AppLanguage.tr("定时任务", "Scheduled tasks")
            SpacesPage.EVAL -> AppLanguage.tr("评测与回放", "Evaluation & replay")
            SpacesPage.RUN_HISTORY -> AppLanguage.tr("运行历史", "Run history")
            SpacesPage.CHILD_RUNS -> AppLanguage.tr("子任务记录", "Child runs")
            SpacesPage.TOKEN_USAGE -> AppLanguage.tr("Token 统计", "Token usage")
            SpacesPage.PERMISSIONS -> AppLanguage.tr("权限级别", "Permissions")
            SpacesPage.LANGUAGE -> AppLanguage.tr("语言", "Language")
            SpacesPage.APP_PROXY -> AppLanguage.tr("应用网络代理", "App network proxy")
            SpacesPage.APP_UPDATE -> AppLanguage.tr("App 更新", "App update")
            SpacesPage.ABOUT -> AppLanguage.tr("关于", "About")
        }
        tab == 2 -> AppLanguage.tr("AI 助手", "AI assistant")
        tab == 3 -> AppLanguage.tr("运行管理", "Running apps")
        else -> "NestSpace"
    }
