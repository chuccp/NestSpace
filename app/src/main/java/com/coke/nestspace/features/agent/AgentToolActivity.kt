package com.coke.nestspace.features.agent

import android.widget.TextView
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.container.identity.HostIdentity
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import com.coke.nestspace.plugins.host.AgentPluginSupport
import com.coke.nestspace.plugins.host.AndroidPluginCommands
import com.coke.nestspace.plugin.PluginUiDocument
import com.coke.nestspace.plugin.PluginUiDocumentParser
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun ToolActivityCard(text: String) {
    val tool = remember(text) { parseToolActivity(text) }
    val presentation = remember(tool) { presentTool(tool) }
    var expanded by remember(text) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var actionStatus by remember(text) { mutableStateOf("") }
    val accent = when {
        !presentation.ok -> MaterialTheme.colorScheme.error
        presentation.warning -> Color(0xFFFF9F0A)
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
        )
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.11f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        presentation.icon,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = accent
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            presentation.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        ToolStatusPill(
                            label = presentation.statusLabel,
                            ok = presentation.ok,
                            warning = presentation.warning
                        )
                    }
                    if (presentation.summary.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            presentation.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                            maxLines = if (expanded) 6 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) {
                        AppLanguage.tr("收起完成详情", "Hide details")
                    } else {
                        AppLanguage.tr("查看完成详情", "View details")
                    },
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            if (expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 9.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
                Text(
                    AppLanguage.tr("详细信息", "Details"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (presentation.viewKey.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    AndroidView(
                        factory = { viewContext ->
                            AgentPluginSupport.createToolView(
                                presentation.viewKey,
                                viewContext
                            ) ?: TextView(viewContext).apply {
                                this.text = presentation.detail
                                setTextIsSelectable(true)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (presentation.detail.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    val uiDocument = remember(presentation) {
                        if (presentation.rendererId == "nestspace.ui.v1") {
                            runCatching {
                                PluginUiDocumentParser.parse(
                                    presentation.detail
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                    }
                    if (uiDocument != null) {
                        PluginDocumentView(uiDocument) { action ->
                            scope.launch {
                                actionStatus = runCatching {
                                    AndroidPluginCommands()
                                        .invokeAsync(action, "{}")
                                        .get()
                                        .message
                                }.getOrElse {
                                    AppLanguage.tr(
                                        "命令执行失败",
                                        "Command failed"
                                    )
                                }
                            }
                        }
                        if (actionStatus.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                actionStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                presentation.detail,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class PluginToolDisplay(
    val title: String,
    val summary: String,
    val detail: String,
    val rendererId: String,
    val viewKey: String,
    val statusLabel: String
)

private data class ToolActivity(
    val name: String,
    val status: String,
    val body: String,
    val pluginDisplay: PluginToolDisplay?
)

private data class ToolPresentation(
    val toolName: String,
    val title: String,
    val summary: String,
    val detail: String,
    val icon: ImageVector,
    val statusLabel: String,
    val ok: Boolean,
    val warning: Boolean = false,
    val rendererId: String = "",
    val viewKey: String = ""
)

private fun parseToolActivity(text: String): ToolActivity {
    val firstLine = text.lineSequence().firstOrNull().orEmpty()
    val separator = firstLine.indexOf(" -> ")
    val name = if (separator >= 0) firstLine.substring(0, separator) else firstLine
    val status = if (separator >= 0) firstLine.substring(separator + 4).trim() else "ok"
    val rawBody = text.substringAfter('\n', "").trim()
    val firstBodyLine = rawBody.lineSequence().firstOrNull().orEmpty()
    val marker = "${AgentPluginSupport.TOOL_DISPLAY_MARKER} "
    val pluginDisplay = if (firstBodyLine.startsWith(marker)) {
        val json = runCatching {
            JSONObject(firstBodyLine.removePrefix(marker))
        }.getOrNull()
        json?.let {
            PluginToolDisplay(
                title = it.optString("title"),
                summary = it.optString("summary"),
                detail = it.optString("detail"),
                rendererId = it.optString("renderer"),
                viewKey = it.optString("view_key"),
                statusLabel = it.optString("status")
            )
        }
    } else {
        null
    }
    val body = if (pluginDisplay == null) {
        rawBody
    } else {
        rawBody.substringAfter('\n', "").trim()
    }
    return ToolActivity(name.trim(), status, body, pluginDisplay)
}

private fun presentTool(tool: ToolActivity): ToolPresentation {
    val ok = tool.status == "ok"
    val warning = tool.status == "TIMEOUT"
    val defaultStatusLabel = when {
        ok && tool.name == "spawn_agent" ->
            AppLanguage.tr("已提交", "Submitted")
        ok && tool.name == "agent_task_supplement" ->
            AppLanguage.tr("已补充", "Added")
        ok -> AppLanguage.tr("完成", "Done")
        warning -> AppLanguage.tr("超时", "Timed out")
        tool.status == "USER_DENIED" -> AppLanguage.tr("已拒绝", "Denied")
        tool.status == "TAKEOVER_SKIPPED" -> AppLanguage.tr("已跳过", "Skipped")
        tool.status == "CONTAINER_NOT_RUNNING" -> AppLanguage.tr("容器未运行", "Container not running")
        else -> AppLanguage.tr("失败", "Failed")
    }
    val json = parseJsonObject(tool.body)
    val statusLabel = tool.pluginDisplay?.statusLabel
        ?.takeIf { it.isNotBlank() }
        ?: defaultStatusLabel
    val title = tool.pluginDisplay?.title
        ?.takeIf { it.isNotBlank() }
        ?: toolTitle(tool.name)
    val summary = tool.pluginDisplay?.summary ?: toolSummary(tool, json)
    val icon = toolIcon(tool.name)
    val detail = tool.pluginDisplay?.detail ?: toolDetail(tool, json)
    return ToolPresentation(
        toolName = tool.name,
        title = title,
        summary = summary,
        detail = detail,
        rendererId = tool.pluginDisplay?.rendererId.orEmpty(),
        viewKey = tool.pluginDisplay?.viewKey.orEmpty(),
        icon = icon,
        statusLabel = statusLabel,
        ok = ok,
        warning = warning
    )
}

private fun toolTitle(name: String): String = when (name) {
    "spawn_agent" -> AppLanguage.tr("已交给 AI 处理", "Handed to AI")
    "agent_task_list" -> AppLanguage.tr("查看后台任务", "View background tasks")
    "agent_task_status" -> AppLanguage.tr("查看任务进度", "Check task progress")
    "agent_task_supplement" -> AppLanguage.tr("补充任务要求", "Add instructions")
    "agent_task_pause" -> AppLanguage.tr("暂停任务", "Pause task")
    "agent_task_resume" -> AppLanguage.tr("继续任务", "Resume task")
    "agent_task_cancel" -> AppLanguage.tr("取消任务", "Cancel task")
    "agent_task_cancel_queued" -> AppLanguage.tr("取消排队任务", "Cancel waiting tasks")
    "space_launch", "space_multi_open" -> AppLanguage.tr("打开应用", "Open app")
    "space_stop" -> AppLanguage.tr("关闭应用", "Close app")
    "space_list_apps" -> AppLanguage.tr("查看应用", "List apps")
    "space_list_installed" -> AppLanguage.tr("查看已安装应用", "List installed apps")
    "space_install_apk" -> AppLanguage.tr("安装应用", "Install app")
    "space_uninstall" -> AppLanguage.tr("卸载应用", "Uninstall app")
    "space_permissions" -> AppLanguage.tr("应用权限", "App permissions")
    "ui_observe" -> AppLanguage.tr("查看界面", "Inspect screen")
    "screen_read" -> AppLanguage.tr("智能读屏", "Read screen")
    "screen_highlight" -> AppLanguage.tr("标记屏幕内容", "Highlight screen")
    "ui_tap" -> AppLanguage.tr("点击界面", "Tap screen")
    "ui_set_text" -> AppLanguage.tr("输入文字", "Type text")
    "ui_wait_for" -> AppLanguage.tr("等待界面", "Wait for screen")
    "ui_key" -> AppLanguage.tr("返回按键", "Press key")
    "browser_open", "browser_navigate" -> AppLanguage.tr("打开网页", "Open page")
    "browser_read_text" -> AppLanguage.tr("读取网页", "Read page")
    "browser_click" -> AppLanguage.tr("点击网页", "Click page")
    "browser_download" -> AppLanguage.tr("下载文件", "Download file")
    "browser_download_status" -> AppLanguage.tr("查看下载", "Check download")
    "task_status" -> AppLanguage.tr("查看任务", "Check task")
    "task_list" -> AppLanguage.tr("任务列表", "Task list")
    "task_wait" -> AppLanguage.tr("等待任务", "Wait for task")
    "task_cancel" -> AppLanguage.tr("取消任务", "Cancel task")
    "browser_tabs", "browser_switch_tab", "browser_close_tab" ->
        AppLanguage.tr("浏览器标签", "Browser tabs")
    "system_status" -> AppLanguage.tr("检查系统", "Check system")
    "hand_to_user" -> AppLanguage.tr("交给你处理", "Hand to you")
    "appfn_list", "appfn_status", "appfn_execute" ->
        AppLanguage.tr("应用功能", "App functions")
    "space_running" -> AppLanguage.tr("查看应用状态", "Check app status")
    "schedule_task" -> AppLanguage.tr("设置提醒", "Set a reminder")
    "speech_speak" -> AppLanguage.tr("播放语音提醒", "Speak reminder")
    "speech_stop" -> AppLanguage.tr("停止语音", "Stop speaking")
    "speech_status" -> AppLanguage.tr("查看语音状态", "Check speech status")
    "memory_save", "memory_forget" -> AppLanguage.tr("整理记忆", "Update memory")
    "skill_save", "skill_read", "skill_list" -> AppLanguage.tr("整理做法", "Update a saved routine")
    "browser_hand_to_user" -> AppLanguage.tr("请你接管网页", "Take over the page")
    else -> when {
        name.startsWith("host_") -> AppLanguage.tr("操作设备", "Operate device")
        name.startsWith("mcp_") -> AppLanguage.tr("使用外部功能", "Use external service")
        else -> AppLanguage.tr("自动操作", "Automated action")
    }
}

private fun toolIcon(name: String): ImageVector = when {
    name == "spawn_agent" || name.startsWith("agent_task_") ->
        Icons.Rounded.AccountTree
    name.startsWith("browser_") -> Icons.Rounded.Public
    name.startsWith("appfn_") -> Icons.Rounded.Build
    name == "hand_to_user" -> Icons.Rounded.SmartToy
    name == "system_status" -> Icons.Rounded.Info
    name == "space_install_apk" || name == "browser_download" -> Icons.Rounded.Download
    name == "space_uninstall" -> Icons.Rounded.Delete
    name == "ui_observe" -> Icons.Rounded.Visibility
    name == "screen_read" || name == "screen_highlight" ->
        Icons.Rounded.Visibility
    name == "ui_tap" -> Icons.Rounded.TouchApp
    name == "ui_set_text" -> Icons.Rounded.Keyboard
    name == "ui_wait_for" -> Icons.Rounded.HourglassEmpty
    name == "task_status" || name == "task_wait" || name == "task_list" ->
        Icons.Rounded.HourglassEmpty
    name == "task_cancel" -> Icons.Rounded.Close
    name == "space_launch" || name == "space_multi_open" -> Icons.Rounded.Apps
    else -> Icons.Rounded.Build
}

private fun toolSummary(tool: ToolActivity, json: JSONObject?): String {
    if (tool.status != "ok") {
        return tool.body.lineSequence().firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: tool.status
    }
    if (json == null) {
        val firstLine = tool.body.lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
        return if (firstLine.startsWith("{") || firstLine.startsWith("[")) {
            AppLanguage.tr("操作已完成", "Completed")
        } else {
            firstLine.take(160)
        }
    }
    val body = tool.body
    return when (tool.name) {
        "spawn_agent" -> when (json.optString("ui_state")) {
            "waiting" -> {
                val position = json.optInt("queue_position", 0)
                if (position > 0) {
                    AppLanguage.tr(
                        "已排队，前面还有 ${position - 1} 个任务",
                        "Queued" + if (position > 1) " after ${position - 1} tasks" else ""
                    )
                } else {
                    AppLanguage.tr("已加入任务列表", "Added to the task list")
                }
            }
            "running" -> AppLanguage.tr("正在后台处理", "Working in the background")
            "paused" -> AppLanguage.tr("任务已暂停", "Task paused")
            else -> AppLanguage.tr("已交给 AI 处理", "Handed to AI")
        }
        "agent_task_list" -> AppLanguage.tr(
            "${json.optJSONArray("tasks")?.length() ?: 0} 个后台任务",
            "${json.optJSONArray("tasks")?.length() ?: 0} background tasks"
        )
        "agent_task_status",
        "agent_task_pause",
        "agent_task_resume",
        "agent_task_cancel" -> listOf(
            json.optString("goal").take(80).takeIf(String::isNotBlank),
            taskUiStateLabel(json.optString("ui_state"))
        ).filterNotNull().joinToString(" · ")
        "agent_task_supplement" -> AppLanguage.tr(
            "补充要求已交给当前任务",
            "Instructions sent to the active task"
        )
        "agent_task_cancel_queued" -> AppLanguage.tr(
            "已取消 ${json.optInt("cancelled")} 个排队任务",
            "Cancelled ${json.optInt("cancelled")} waiting tasks"
        )
        "schedule_task" -> when {
            json.has("goal") -> if (json.optBoolean("alarm")) {
                AppLanguage.tr("已设置闹钟提醒", "Alarm reminder set")
            } else {
                AppLanguage.tr("已设置定时任务", "Scheduled task created")
            }
            json.has("tasks") -> AppLanguage.tr(
                "${json.optJSONArray("tasks")?.length() ?: 0} 个定时任务",
                "${json.optJSONArray("tasks")?.length() ?: 0} scheduled tasks"
            )
            else -> AppLanguage.tr("定时任务已更新", "Scheduled task updated")
        }
        "speech_speak" -> AppLanguage.tr("已开始播放", "Playback started")
        "speech_stop" -> AppLanguage.tr("已停止播放", "Playback stopped")
        "speech_status" -> AppLanguage.tr("语音功能正常", "Speech is available")
        "space_launch", "space_multi_open" -> buildString {
            append(appLabel(json.optString("package")))
            json.optString("instance_id").takeIf { it.isNotBlank() }?.let {
                append(" · ").append(it)
            }
            if (json.optBoolean("headless")) {
                append(AppLanguage.tr(" · 无头", " · headless"))
            }
        }
        "space_list_apps" -> AppLanguage.tr(
            "${json.optJSONArray("apps")?.length() ?: 0} 个应用",
            "${json.optJSONArray("apps")?.length() ?: 0} apps"
        )
        "space_list_installed" -> AppLanguage.tr(
            "${json.optJSONArray("apps")?.length() ?: 0} 个虚拟应用",
            "${json.optJSONArray("apps")?.length() ?: 0} virtual apps"
        )
        "space_install_apk" -> json.optString("package").takeIf { it.isNotBlank() }
            ?: AppLanguage.tr("安装完成", "Installed")
        "space_uninstall" -> AppLanguage.tr(
            "已卸载 ${json.optString("package")}",
            "Uninstalled ${json.optString("package")}"
        )
        "space_permissions" -> listOf(
            json.optString("action"),
            json.optString("package"),
            json.optString("permission")
        ).filter { it.isNotBlank() }.joinToString(" · ")
        "ui_observe" -> {
            if (json.optBoolean("needs_user")) {
                val reason = when (json.optString("user_reason")) {
                    "payment" -> AppLanguage.tr("支付", "payment")
                    "captcha" -> AppLanguage.tr("验证码", "captcha")
                    "login" -> AppLanguage.tr("登录", "login")
                    "permission" -> AppLanguage.tr("权限", "permission")
                    else -> AppLanguage.tr("这一步", "this step")
                }
                AppLanguage.tr("需要你处理$reason", "Needs you to handle $reason")
            } else {
                val count = json.optInt(
                    "nodes_count",
                    json.optJSONArray("nodes")?.length() ?: 0
                )
                AppLanguage.tr("已读取 $count 个控件", "Read $count controls")
            }
        }
        "screen_read" -> {
            val blocks = json.optJSONArray("blocks")?.length() ?: 0
            val actions = json.optJSONArray("actions")?.length() ?: 0
            AppLanguage.tr(
                "$blocks 个内容区块 · $actions 个操作项",
                "$blocks content blocks · $actions actions"
            )
        }
        "screen_highlight" ->
            AppLanguage.tr(
                "已标记 ${json.optInt("count")} 个位置",
                "Highlighted ${json.optInt("count")} locations"
            )
        "ui_tap" -> humanizeTap(tool.body)
        "ui_set_text" -> AppLanguage.tr("已输入文字", "Text entered")
        "ui_wait_for" -> humanizeWait(tool.body)
        "task_status", "task_wait", "task_cancel" -> when (tool.name) {
            "task_cancel" -> AppLanguage.tr("已请求取消任务", "Cancellation requested")
            "task_status" -> AppLanguage.tr("任务进度已更新", "Task progress updated")
            else -> when {
                json.optBoolean("timed_out") ->
                    AppLanguage.tr("还在等待，稍后继续", "Still waiting")
                json.optBoolean("terminal") || json.optBoolean("changed") ->
                    AppLanguage.tr("任务状态已更新", "Task status updated")
                else -> AppLanguage.tr("继续等待任务完成", "Waiting for the task")
            }
        }
        "task_list" -> AppLanguage.tr(
            "${json.optJSONArray("tasks")?.length() ?: 0} 个任务",
            "${json.optJSONArray("tasks")?.length() ?: 0} tasks"
        )
        "ui_key" -> AppLanguage.tr("已按下返回键", "Pressed back")
        "browser_open", "browser_navigate" -> {
            val title = json.optString("title")
            val url = json.optString("url")
            listOf(title, url).firstOrNull { it.isNotBlank() } ?: AppLanguage.tr("页面已打开", "Page opened")
        }
        "browser_read_text" -> AppLanguage.tr(
            "已读取 ${body.length} 个字符",
            "Read ${body.length} characters"
        )
        "browser_download" -> {
            val name = listOf(json.optString("name"), json.optString("url"))
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            AppLanguage.tr(
                "$name · 已开始下载",
                "$name · Download started"
            )
        }
        "browser_download_status" -> AppLanguage.tr(
            when (json.optString("state")) {
                "done" -> "下载完成"
                "failed" -> "下载失败"
                else -> "下载中"
            },
            when (json.optString("state")) {
                "done" -> "Download complete"
                "failed" -> "Download failed"
                else -> "Downloading"
            }
        )
        "system_status" -> listOf(
            json.optString("android"),
            json.optString("battery_percent").takeIf { it.isNotBlank() }?.let { "$it%" }
        ).mapNotNull { it?.takeIf(String::isNotBlank) }.joinToString(" · ")
        "hand_to_user" -> when (json.optString("takeover")) {
            "completed" -> AppLanguage.tr("你已完成处理", "You finished this step")
            "skipped" -> AppLanguage.tr("你跳过了这一步", "You skipped this step")
            else -> AppLanguage.tr("已把 App 交给你", "Handed the app to you")
        }
        else -> AppLanguage.tr("操作已完成", "Completed")
    }
}

private fun humanizeTap(body: String): String {
    val line = body.lineSequence().firstOrNull().orEmpty()
    return when {
        line.startsWith("tapped ") -> AppLanguage.tr(
            "已点击 ${line.removePrefix("tapped ")}",
            "Tapped ${line.removePrefix("tapped ")}"
        )
        line == "tap failed" -> AppLanguage.tr("点击失败", "Tap failed")
        else -> line.take(160)
    }
}

private fun humanizeWait(body: String): String {
    val line = body.lineSequence().firstOrNull().orEmpty()
    return when {
        line.startsWith("found: ") -> {
            val text = line.removePrefix("found: ")
            AppLanguage.tr("已等到「$text」", "Found \"$text\"")
        }
        line.startsWith("timeout: ") -> {
            val text = line.removePrefix("timeout: ")
            AppLanguage.tr("等待「$text」超时", "Timed out waiting for \"$text\"")
        }
        else -> line.take(160)
    }
}

private fun appLabel(packageName: String): String {
    if (packageName.isBlank()) return AppLanguage.tr("应用", "App")
    val context = HostIdentity.application ?: return packageName
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0)
            .loadLabel(pm)
            .toString()
            .takeIf(String::isNotBlank)
    }.getOrNull() ?: packageName
}

private fun toolDetail(tool: ToolActivity, json: JSONObject?): String {
    if (tool.status != "ok") return friendlyError(tool.body).message
    if (json == null) {
        val body = tool.body.trim()
        return if (body.startsWith("{") || body.startsWith("[")) {
            ""
        } else {
            body.take(2_000)
        }
    }
    return when (tool.name) {
        "spawn_agent",
        "agent_task_status",
        "agent_task_pause",
        "agent_task_resume",
        "agent_task_cancel" -> buildString {
            json.optString("goal")
                .takeIf(String::isNotBlank)
                ?.let { append(AppLanguage.tr("任务：", "Task: ")).append(it).append('\n') }
            append(AppLanguage.tr("状态：", "Status: "))
                .append(taskUiStateLabel(json.optString("ui_state")))
            json.optString("progress")
                .takeIf(String::isNotBlank)
                ?.let {
                    append('\n')
                    append(AppLanguage.tr("进度：", "Progress: "))
                    append(it)
                }
            json.optString("result")
                .takeIf(String::isNotBlank)
                ?.let {
                    append('\n')
                    append(AppLanguage.tr("结果：", "Result: "))
                    append(it)
                }
        }
        "space_launch", "space_multi_open" -> buildString {
            append(AppLanguage.tr("应用：", "App: ")).append(appLabel(json.optString("package")))
            append('\n').append(AppLanguage.tr("包名：", "Package: ")).append(json.optString("package"))
            json.optString("instance_id").takeIf { it.isNotBlank() }?.let {
                append('\n').append(AppLanguage.tr("实例：", "Instance: ")).append(it)
            }
            json.optString("activity").takeIf { it.isNotBlank() }?.let {
                append('\n').append(AppLanguage.tr("页面：", "Activity: ")).append(it)
            }
            append('\n').append(AppLanguage.tr("模式：", "Mode: ")).append(
                if (json.optBoolean("headless")) {
                    AppLanguage.tr("无头（后台）", "Headless (background)")
                } else {
                    AppLanguage.tr("前台", "Foreground")
                }
            )
        }
        "ui_observe" -> buildString {
            append(AppLanguage.tr("应用：", "App: ")).append(appLabel(json.optString("package")))
            append('\n').append(AppLanguage.tr("页面：", "Activity: ")).append(json.optString("activity"))
            append('\n').append(AppLanguage.tr("控件：", "Controls: "))
                .append(json.optInt("nodes_count", json.optJSONArray("nodes")?.length() ?: 0))
                .append(AppLanguage.tr(" 个", ""))
            append('\n').append(AppLanguage.tr("需要你处理：", "Needs you: ")).append(
                if (json.optBoolean("needs_user")) {
                    json.optString("user_reason").ifBlank { AppLanguage.tr("是", "Yes") }
                } else {
                    AppLanguage.tr("否", "No")
                }
            )
        }
        "screen_read" -> buildString {
            append(AppLanguage.tr("页面：", "Page: "))
                .append(json.optString("title"))
                .append(" · ")
                .append(json.optString("page_type"))
            json.optString("summary")
                .takeIf(String::isNotBlank)
                ?.let { append('\n').append(it) }
            val blocks = json.optJSONArray("blocks")
            if (blocks != null && blocks.length() > 0) {
                append('\n').append(AppLanguage.tr("内容：", "Content:"))
                for (index in 0 until minOf(blocks.length(), 5)) {
                    val block = blocks.optJSONObject(index) ?: continue
                    append('\n').append(index + 1).append(". ")
                        .append(block.optString("text").take(180))
                }
            }
            val actions = json.optJSONArray("actions")
            if (actions != null && actions.length() > 0) {
                append('\n').append(AppLanguage.tr("可操作项：", "Actions:"))
                for (index in 0 until minOf(actions.length(), 6)) {
                    val action = actions.optJSONObject(index) ?: continue
                    append('\n').append(index + 1).append(". ")
                        .append(action.optString("label"))
                }
            }
        }
        "screen_highlight" -> AppLanguage.tr(
            "已高亮 ${json.optInt("count")} 个位置",
            "Highlighted ${json.optInt("count")} locations"
        )
        "browser_open", "browser_navigate" -> buildString {
            json.optString("title").takeIf { it.isNotBlank() }?.let {
                append(AppLanguage.tr("标题：", "Title: ")).append(it).append('\n')
            }
            append(AppLanguage.tr("地址：", "URL: ")).append(json.optString("url")).append('\n')
            append(AppLanguage.tr("状态：", "State: ")).append(
                if (json.optBoolean("loaded")) {
                    AppLanguage.tr("已加载", "Loaded")
                } else {
                    AppLanguage.tr("加载中", "Loading")
                }
            )
        }
        "system_status" -> buildString {
            append(AppLanguage.tr("系统：", "System: ")).append(json.optString("android")).append('\n')
            append(AppLanguage.tr("机型：", "Model: ")).append(json.optString("model")).append('\n')
            append(AppLanguage.tr("电量：", "Battery: ")).append(json.optString("battery_percent")).append('%').append('\n')
            append(AppLanguage.tr("网络：", "Network: ")).append(json.optString("network"))
        }
        "hand_to_user" -> buildString {
            append(AppLanguage.tr("原因：", "Reason: ")).append(json.optString("reason")).append('\n')
            append(AppLanguage.tr("结果：", "Result: ")).append(json.optString("takeover"))
        }
        "schedule_task" -> buildString {
            json.optString("goal")
                .takeIf(String::isNotBlank)
                ?.let { append(AppLanguage.tr("提醒内容：", "Reminder: ")).append(it).append('\n') }
            val nextAt = json.optLong("next_at")
            if (nextAt > 0) {
                append(AppLanguage.tr("下次时间：", "Next time: "))
                    .append(
                        SimpleDateFormat(
                            "MM-dd HH:mm:ss",
                            Locale.getDefault()
                        ).format(Date(nextAt))
                    )
                    .append('\n')
            }
            append(AppLanguage.tr("重复：", "Repeat: "))
                .append(scheduleRepeatLabel(json.optString("repeat"), json.optInt("interval_minutes")))
                .append('\n')
            append(AppLanguage.tr("方式：", "Alert: ")).append(
                if (json.optBoolean("alarm")) {
                    AppLanguage.tr("闹钟响铃和震动", "Alarm sound and vibration")
                } else {
                    AppLanguage.tr("普通通知", "Notification")
                }
            )
        }
        else -> ""
    }
}

private fun scheduleRepeatLabel(repeat: String, intervalMinutes: Int): String = when (repeat) {
    "daily" -> AppLanguage.tr("每天", "Daily")
    "weekly" -> AppLanguage.tr("每周", "Weekly")
    "interval" -> AppLanguage.tr(
        "每 $intervalMinutes 分钟",
        "Every $intervalMinutes min"
    )
    else -> AppLanguage.tr("一次", "Once")
}

private fun taskUiStateLabel(state: String): String = when (state) {
    "waiting" -> AppLanguage.tr("排队中", "Waiting")
    "running" -> AppLanguage.tr("正在处理", "Working")
    "paused" -> AppLanguage.tr("已暂停", "Paused")
    "needs_attention" -> AppLanguage.tr("需要你处理", "Needs you")
    "cancelling" -> AppLanguage.tr("正在取消", "Cancelling")
    "finished" -> AppLanguage.tr("已完成", "Finished")
    else -> state
}

private fun parseJsonObject(text: String): JSONObject? =
    runCatching { JSONObject(text) }.getOrNull()

@Composable
private fun PluginDocumentView(
    document: PluginUiDocument,
    onAction: (String) -> Unit
) {
    Column {
        if (document.title.isNotBlank()) {
            Text(
                document.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(5.dp))
        }
        document.blocks.forEach { block ->
            when (block.type) {
                "divider" -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                "button" -> Button(
                    onClick = { onAction(block.action) },
                    enabled = block.action.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(block.label.ifBlank { block.action })
                }
                "progress" -> {
                    val value = block.dataJson.toDoubleOrNull()
                        ?.div(100.0)
                        ?.coerceIn(0.0, 1.0)
                        ?.toFloat()
                    if (value == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { value },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                "key_value" -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        block.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> Text(
                    block.text.ifBlank {
                        block.label.ifBlank {
                            block.dataJson
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ToolStatusPill(label: String, ok: Boolean, warning: Boolean) {
    val color = when {
        !ok -> MaterialTheme.colorScheme.error
        warning -> Color(0xFFFF9F0A)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.10f)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}
