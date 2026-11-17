package com.coke.nestspace.ui.agent

import com.coke.nestspace.i18n.AppLanguage
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coke.nestspace.R
import com.coke.nestspace.data.AgentMessage
import kotlinx.coroutines.isActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ErrorMessage(text: String) {
    val friendly = remember(text) { friendlyError(text) }
    var showDetails by remember(text) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.78f)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    friendly.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    friendly.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    lineHeight = 18.sp
                )
                if (showDetails) {
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            text,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                                .copy(alpha = 0.72f)
                        )
                    }
                }
                if (friendly.message != text.trim()) {
                    TextButton(
                        onClick = { showDetails = !showDetails },
                        modifier = Modifier.padding(top = 1.dp)
                    ) {
                        Text(
                            if (showDetails) {
                                AppLanguage.tr("收起详情", "Hide details")
                            } else {
                                AppLanguage.tr("查看详情", "View details")
                            }
                        )
                    }
                }
            }
        }
    }
}

internal data class FriendlyError(val title: String, val message: String)

internal fun friendlyError(text: String): FriendlyError {
    val raw = text.trim()
    val lower = raw.lowercase()
    val cancelled = lower.contains("job was cancelled") ||
        lower.contains("cancelled") ||
        lower.contains("canceled") ||
        raw.contains("已取消")
    val timedOut = lower.contains("timeout") || lower.contains("timed out")
    val auth = lower.contains("401") ||
        lower.contains("403") ||
        lower.contains("unauthorized") ||
        lower.contains("api key") ||
        lower.contains("authentication")
    val network = lower.contains("network") ||
        lower.contains("connect") ||
        lower.contains("unknownhost") ||
        lower.contains("socket")
    val appList = lower.contains("load the app list") ||
        raw.contains("应用列表")
    val title = when {
        cancelled -> AppLanguage.tr("操作中断了", "Action interrupted")
        timedOut -> AppLanguage.tr("等待超时", "Taking too long")
        auth -> AppLanguage.tr("连接没有通过", "Connection failed")
        network -> AppLanguage.tr("网络不太稳定", "Connection problem")
        appList -> AppLanguage.tr("应用列表没加载出来", "Couldn't load apps")
        else -> AppLanguage.tr("这次没有完成", "Couldn't finish that")
    }
    val message = when {
        cancelled -> AppLanguage.tr(
            "刚才的操作被中断了。你可以再试一次。",
            "That action was interrupted. Please try again."
        )
        timedOut -> AppLanguage.tr(
            "这次等得有点久，还没有完成。请稍后再试。",
            "This is taking longer than expected. Please try again in a moment."
        )
        auth -> AppLanguage.tr(
            "AI 服务没有通过验证，请检查连接和密钥设置。",
            "The AI service could not verify the connection. Check your connection settings."
        )
        network -> AppLanguage.tr(
            "网络连接不太稳定，请检查网络后重试。",
            "The network connection is unstable. Check it and try again."
        )
        appList -> AppLanguage.tr(
            "应用列表暂时没有加载出来，请稍后再试。",
            "The app list could not be loaded. Please try again."
        )
        raw.startsWith("{") || raw.startsWith("[") || raw.contains("Exception") ->
            AppLanguage.tr(
                "遇到了一点问题，请再试一次。",
                "Something went wrong. Please try again."
            )
        else -> raw.ifBlank {
            AppLanguage.tr("请稍后再试。", "Please try again.")
        }
    }
    return FriendlyError(title, message)
}

/**
 * CircularProgressIndicator freezes when the system animator scale is 0, which
 * is common on emulators and in "remove animations" accessibility mode. Drive
 * this small arc from the frame clock so the assistant always shows activity.
 */
@Composable
private fun ThinkingSpinner(color: Color, modifier: Modifier = Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var previous = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (previous != 0L) {
                    angle = (angle + (now - previous) / 1_000_000f * 360f / 750f) % 360f
                }
                previous = now
            }
        }
    }
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        rotate(angle) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
internal fun ThinkingRow(lastMessage: AgentMessage?) {
    val label = if (lastMessage?.role == AgentMessage.Role.TOOL) {
        AppLanguage.tr("正在继续处理…", "Continuing…")
    } else {
        AppLanguage.tr("正在思考…", "Thinking…")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ai_assistant),
            contentDescription = null,
            modifier = Modifier.size(26.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThinkingSpinner(
                    modifier = Modifier.size(13.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
