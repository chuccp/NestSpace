package com.coke.nestspace.features.assistant

import com.coke.nestspace.core.i18n.AppLanguage
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coke.nestspace.features.browser.BrowserSession
import com.coke.nestspace.design.components.FrameSpinner
import com.coke.nestspace.features.agent.DownloadProgressCard
import com.coke.nestspace.features.agent.ModelDisconnectedIcon
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.agent.task.OperationTaskRegistry
import org.vosk.android.RecognitionListener as VoskRecognitionListener

@Composable
internal fun AssistantVoiceScreen(
    messages: List<AgentMessage>,
    busy: Boolean,
    preparing: Boolean,
    downloadPercent: Int?,
    listening: Boolean,
    available: Boolean,
    status: String,
    transcript: String,
    input: String,
    level: Float,
    showInput: Boolean,
    modelConfigured: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleListening: () -> Unit,
    onToggleInput: () -> Unit,
    onConfigureModel: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val accent = remember { Color(FloatingDotSettings.load(context).hostColor) }
    val focusRequester = remember { FocusRequester() }
    val downloads by BrowserSession.downloadsFlow.collectAsState()
    val activeDownload = downloads.firstOrNull {
        it.state == "downloading" || it.state == "cancelling"
    }
    val latestReply = remember(messages) {
        messages.lastOrNull {
            it.role == AgentMessage.Role.AGENT && !it.welcome
        }?.text?.trim().orEmpty()
    }
    BackHandler(onBack = onBack)
    LaunchedEffect(showInput) {
        if (showInput) runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(Color.Black.copy(alpha = 0.24f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(30.dp),
            color = Color(0xF21A1B22),
            shadowElevation = 18.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        AppLanguage.tr("NestSpace 助手", "NestSpace Assistant"),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = AppLanguage.tr("关闭", "Close"),
                            tint = Color.White.copy(alpha = 0.72f)
                        )
                    }
                }

                if (!modelConfigured) {
                    ModelSetupPrompt(
                        accent = accent,
                        onConfigure = onConfigureModel
                    )
                } else if (preparing) {
                    Text(
                        status,
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    LinearProgressIndicator(
                        progress = {
                            downloadPercent?.takeIf { it in 0..100 }?.div(100f) ?: 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent
                    )
                } else {
                    Text(
                        transcript.ifBlank { status },
                        color = Color.White.copy(alpha = if (transcript.isBlank()) 0.62f else 0.90f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!busy && latestReply.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = Color.White.copy(alpha = 0.08f)
                        ) {
                            Text(
                                latestReply,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                color = Color.White.copy(alpha = 0.92f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (!showInput) {
                        if (busy) {
                            Surface(
                                modifier = Modifier.clickable(onClick = onStop),
                                shape = RoundedCornerShape(50),
                                color = accent.copy(alpha = 0.16f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FrameSpinner(
                                        modifier = Modifier.size(18.dp),
                                        color = accent,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(9.dp))
                                    Text(
                                        AppLanguage.tr("正在处理 · 点击停止", "Working · tap to stop"),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        } else {
                            VoiceOrb(
                                listening = listening,
                                level = level,
                                accent = accent,
                                onClick = onToggleListening
                            )
                        }
                    }
                }

                activeDownload?.let { download ->
                    DownloadProgressCard(
                        download = download,
                        dark = true,
                        onCancel = download.taskId?.let { taskId ->
                            {
                                OperationTaskRegistry.requestCancel(
                                    taskId,
                                    "user cancelled"
                                )
                            }
                        }
                    )
                }

                if (modelConfigured && showInput) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(AppLanguage.tr("说点什么…", "Ask me anything…"))
                        },
                        minLines = 1,
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { if (busy) onStop() else onSend() }
                        ),
                        trailingIcon = {
                            IconButton(onClick = { if (busy) onStop() else onSend() }) {
                                Icon(
                                    if (busy) Icons.Default.Stop else Icons.Default.Send,
                                    contentDescription = if (busy) {
                                        AppLanguage.tr("停止", "Stop")
                                    } else {
                                        AppLanguage.tr("发送", "Send")
                                    },
                                    tint = accent
                                )
                            }
                        },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = accent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.24f),
                            cursorColor = accent,
                            focusedContainerColor = Color.White.copy(alpha = 0.06f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                            focusedPlaceholderColor = Color.White.copy(alpha = 0.42f),
                            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.42f)
                        )
                    )
                }

                if (modelConfigured) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onToggleInput) {
                            Icon(
                                if (showInput) Icons.Default.Mic else Icons.Default.Keyboard,
                                contentDescription = if (showInput) {
                                    AppLanguage.tr("切换到语音", "Switch to voice")
                                } else {
                                    AppLanguage.tr("切换到键盘", "Switch to keyboard")
                                },
                                tint = Color.White.copy(alpha = 0.72f)
                            )
                        }
                        Text(
                            when {
                                busy -> AppLanguage.tr("正在处理", "Working")
                                preparing -> AppLanguage.tr("正在准备语音", "Preparing voice")
                                listening -> AppLanguage.tr("正在聆听", "Listening")
                                available -> AppLanguage.tr("点按圆点开始", "Tap the dot to start")
                                else -> AppLanguage.tr("语音不可用", "Voice unavailable")
                            },
                            modifier = Modifier.weight(1f),
                            color = Color.White.copy(alpha = 0.48f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.size(48.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelSetupPrompt(
    accent: Color,
    onConfigure: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = accent.copy(alpha = 0.13f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            ModelDisconnectedIcon(
                tint = accent,
                modifier = Modifier.size(46.dp)
            )
            Text(
                AppLanguage.tr(
                    "还没有配置大模型",
                    "No model configured"
                ),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                AppLanguage.tr(
                    "配置后即可使用语音和文字对话",
                    "Configure one to use voice and text chat"
                ),
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onConfigure),
                shape = RoundedCornerShape(50),
                color = accent
            ) {
                Text(
                    AppLanguage.tr(
                        "去配置大模型",
                        "Configure a model"
                    ),
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 9.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
internal fun VoiceOrb(
    listening: Boolean,
    level: Float,
    accent: Color,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "voice-orb")
    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = if (listening) 1.06f + level * 0.05f else 1.01f,
        animationSpec = infiniteRepeatable(
            animation = tween(780, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voice-pulse"
    )
    Box(
        modifier = Modifier
            .size(112.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(106.dp)
                .scale(pulse)
                .background(accent.copy(alpha = 0.12f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(86.dp)
                .scale(pulse)
                .background(
                    Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = 0.98f),
                            accent.copy(alpha = 0.72f)
                        )
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(76.dp)
                .border(
                    width = 2.dp,
                    color = Color.White.copy(alpha = if (listening) 0.60f else 0.28f),
                    shape = CircleShape
                )
        )
        Icon(
            Icons.Default.Mic,
            contentDescription = AppLanguage.tr("语音输入", "Voice input"),
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}
