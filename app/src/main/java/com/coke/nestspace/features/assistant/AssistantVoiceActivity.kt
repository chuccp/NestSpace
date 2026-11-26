package com.coke.nestspace.features.assistant

import com.coke.nestspace.container.HeadlessMirrorWindow
import com.coke.nestspace.core.i18n.AppLanguage
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.coke.nestspace.container.control.ContainerUiClient
import com.coke.nestspace.features.browser.BrowserSession
import com.coke.nestspace.design.components.FrameSpinner
import com.coke.nestspace.MainActivity
import com.coke.nestspace.agent.AssistantConversation
import com.coke.nestspace.data.LlmConfigStore
import com.coke.nestspace.features.navigation.ModelSettingsNavigation
import com.coke.nestspace.features.agent.UserGateHost
import com.coke.nestspace.features.agent.DownloadProgressCard
import com.coke.nestspace.features.agent.ModelDisconnectedIcon
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.agent.task.OperationTaskRegistry
import com.coke.nestspace.design.theme.VirtualTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService

/**
 * Siri/ChatGPT-style voice conversation sheet opened from the floating dot.
 *
 * The text field is hidden until the user taps the keyboard control. Voice
 * recognition starts automatically, final speech results are submitted
 * without an extra tap, and the sheet stays open for follow-up turns.
 * Messages and LLM history live in [AssistantConversation], shared with the
 * assistant tab.
 */
class AssistantVoiceActivity : ComponentActivity() {

    private var voskRecognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var speechLanguage = FloatingDotSettings.SPEECH_LANG_ZH
    private var isListening by mutableStateOf(false)
    private var available by mutableStateOf(false)
    private var preparing by mutableStateOf(false)
    private var downloadPercent by mutableStateOf<Int?>(null)
    private var status by mutableStateOf(
        AppLanguage.tr("正在准备本地语音模型…", "Preparing the local speech model…")
    )
    private var transcript by mutableStateOf("")
    private var input by mutableStateOf("")
    private var level by mutableFloatStateOf(0f)
    private var showInput by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var modelConfigured by mutableStateOf(false)
    private var speechBootstrapped = false
    private var resumeAfterReply = true
    private var userEdited = false
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelConfigured = hasConfiguredModel()
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            VirtualTheme(darkTheme = true, dynamicColor = false) {
                val messages by AssistantConversation.messages.collectAsState()
                AssistantVoiceScreen(
                    messages = messages,
                    busy = busy,
                    preparing = preparing,
                    downloadPercent = downloadPercent,
                    listening = isListening,
                    available = available,
                    status = status,
                    transcript = transcript,
                    input = input,
                    level = level,
                    showInput = showInput,
                    modelConfigured = modelConfigured,
                    onInputChange = {
                        userEdited = true
                        input = it
                    },
                    onSend = { submitInput() },
                    onStop = { AssistantConversation.cancel() },
                    onToggleListening = { toggleListening() },
                    onToggleInput = { toggleInput() },
                    onConfigureModel = { openModelSettings() },
                    onBack = { backOrClose() },
                    onClose = { finish() }
                )
                UserGateHost()
            }
        }

        val initialPrompt = intent?.getStringExtra(EXTRA_INITIAL_PROMPT)
        if (savedInstanceState == null && !initialPrompt.isNullOrBlank()) {
            intent?.removeExtra(EXTRA_INITIAL_PROMPT)
            AssistantConversation.submit(
                context = this,
                command = initialPrompt,
                queueIfBusy = true
            )
        }

        lifecycleScope.launch {
            AssistantConversation.busy.collect { value ->
                busy = value
                if (value) {
                    status = AppLanguage.tr("正在思考…", "Thinking…")
                } else if (resumeAfterReply) {
                    // Never open the microphone while this Activity is not
                    // resumed. Keep resumeAfterReply set so onResume can
                    // listen when the user comes back.
                    if (!resumed) return@collect
                    resumeAfterReply = false
                    when {
                        showInput -> status = AppLanguage.tr("输入你的问题", "Type your question")
                        preparing -> status = AppLanguage.tr(
                            "正在准备本地语音模型…",
                            "Preparing the local speech model…"
                        )
                        available && hasAudioPermission() -> startListening()
                        else -> status = if (available) {
                            AppLanguage.tr("点按圆点开始说话", "Tap the dot to start speaking")
                        } else {
                            AppLanguage.tr("语音不可用，可点键盘输入", "Voice is unavailable; use the keyboard")
                        }
                    }
                } else if (status == AppLanguage.tr("正在思考…", "Thinking…") ||
                    status == AppLanguage.tr(
                        "已加入当前任务…",
                        "Added to the current task…"
                    )
                ) {
                    status = when {
                        showInput -> AppLanguage.tr("输入你的问题", "Type your question")
                        preparing -> AppLanguage.tr(
                            "正在准备本地语音模型…",
                            "Preparing the local speech model…"
                        )
                        available -> AppLanguage.tr("点按圆点开始说话", "Tap the dot to start speaking")
                        else -> AppLanguage.tr("语音不可用，可点键盘输入", "Voice is unavailable; use the keyboard")
                    }
                }
            }
        }

        lifecycleScope.launch {
            combine(
                LocalSpeechModelStore.progress,
                LocalSpeechModelStore.metrics
            ) { progress, metrics ->
                progress to metrics
            }.collect { (progress, metrics) ->
                if (!preparing || showInput) return@collect
                val percent = progress[speechLanguage] ?: return@collect
                downloadPercent = percent
                val spec = LocalSpeechModelStore.spec(speechLanguage)
                val mirror = LocalSpeechModelStore.source.value[speechLanguage]
                val transfer = metrics[speechLanguage]
                status = when {
                    percent < 0 -> AppLanguage.tr(
                        "本地语音模型下载失败，点击键盘输入文字",
                        "Speech model download failed; tap the keyboard to type"
                    )
                    percent >= 100 -> AppLanguage.tr(
                        AppLanguage.tr("正在加载本地语音模型…", "Loading the local speech model…"),
                        "Loading the local speech model…"
                    )
                    percent == 0 && mirror == null -> AppLanguage.tr(
                        "正在选择下载线路中…",
                        "Choosing a download line…"
                    )
                    else -> {
                        val speed = transfer?.speedText()
                            ?.takeIf(String::isNotBlank)
                        val eta = transfer?.remainingText()
                            ?.takeIf(String::isNotBlank)
                        buildString {
                            append(AppLanguage.tr("线路 ", "Line "))
                            append(
                                mirror ?: AppLanguage.tr(
                                    "模型服务器",
                                    "model server"
                                )
                            )
                            append(" · $percent%")
                            speed?.let {
                                append(" · ").append(it)
                            }
                            eta?.let {
                                append(
                                    AppLanguage.tr(
                                        " · 预计剩余时间 $it",
                                        " · Estimated remaining $it"
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        bootstrapSpeechIfConfigured()
    }

    /**
     * Back belongs to the mirrored app while its small window is visible;
     * the assistant sheet is closed with the X instead. Without this, the
     * host target sits behind this Activity and never sees the back key.
     */
    private fun backOrClose() {
        val slot = HeadlessMirrorWindow.activeSlot()
        if (slot < 0) {
            finish()
            return
        }
        lifecycleScope.launch {
            if (ContainerUiClient.pressBack(this@AssistantVoiceActivity, slot) != true) {
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        modelConfigured = hasConfiguredModel()
        FloatingDotOverlay.setSuppressed(this, true)
        FloatingDotOverlay.onHostActivityResumed(this)
        bootstrapSpeechIfConfigured()
        // If the reply finished while the sheet was in the background, start
        // listening only once the Activity is visible again.
        if (resumeAfterReply && !busy && !preparing && !showInput &&
            available && hasAudioPermission()
        ) {
            resumeAfterReply = false
            startListening()
        }
    }

    override fun onPause() {
        resumed = false
        stopListening(cancel = true)
        // The sheet may be paused by another app/container rather than
        // destroyed; don't keep the dot hidden behind someone else's UI.
        FloatingDotOverlay.setSuppressed(this, false)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            prepareLocalSpeech()
        } else {
            available = false
            status = AppLanguage.tr(
                "需要麦克风权限，点击键盘输入文字",
                "Microphone permission needed; tap the keyboard to type"
            )
        }
    }

    override fun onDestroy() {
        resumeAfterReply = false
        resumed = false
        stopListening(cancel = true)
        speechService?.shutdown()
        speechService = null
        voskRecognizer?.close()
        voskRecognizer = null
        FloatingDotOverlay.setSuppressed(this, false)
        super.onDestroy()
    }

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasConfiguredModel(): Boolean {
        val state = LlmConfigStore(this).load()
        val active = state.configs.firstOrNull { it.id == state.activeId }
            ?: state.configs.firstOrNull()
        return active?.isConfigured == true
    }

    private fun bootstrapSpeechIfConfigured() {
        if (!modelConfigured || speechBootstrapped) return
        speechBootstrapped = true
        if (hasAudioPermission()) {
            prepareLocalSpeech()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
    }

    private fun openModelSettings() {
        stopListening(cancel = true)
        ModelSettingsNavigation.requestModelSettings()
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }

    private fun prepareLocalSpeech() {
        if (speechService != null) {
            available = true
            if (!busy && !showInput) startListening()
            return
        }
        if (preparing) return
        speechLanguage = FloatingDotSettings.load(this).speechLanguage
        val spec = LocalSpeechModelStore.spec(speechLanguage)
        preparing = true
        available = false
        downloadPercent = if (LocalSpeechModelStore.isReady(this, speechLanguage)) {
            null
        } else {
            0
        }
        status = if (LocalSpeechModelStore.isReady(this, speechLanguage)) {
            AppLanguage.tr("正在加载本地语音模型…", "Loading the local speech model…")
        } else {
            AppLanguage.tr(
                "正在选择最快的模型服务器…（${spec.displayName}，约 ${spec.approximateMb}MB）",
                "Choosing the fastest model server… (${spec.displayName}, " +
                    "about ${spec.approximateMb} MB)"
            )
        }
        lifecycleScope.launch {
            try {
                LocalSpeechModelStore.ensure(
                    this@AssistantVoiceActivity,
                    speechLanguage
                )
                status = AppLanguage.tr("正在加载本地语音模型…", "Loading the local speech model…")
                val model = LocalSpeechEngine.ensureLoaded(
                    this@AssistantVoiceActivity,
                    speechLanguage
                )
                val recognizer = withContext(Dispatchers.IO) {
                    Recognizer(model, SAMPLE_RATE)
                }
                voskRecognizer = recognizer
                speechService = SpeechService(recognizer, SAMPLE_RATE)
                preparing = false
                downloadPercent = null
                available = true
                if (resumed && !busy && !showInput) {
                    startListening()
                } else {
                    status = if (showInput) AppLanguage.tr("输入你的问题", "Type your question")
                    else AppLanguage.tr("点按圆点开始说话", "Tap the dot to start speaking")
                }
            } catch (cancelled: CancellationException) {
                preparing = false
                downloadPercent = null
                throw cancelled
            } catch (t: Throwable) {
                preparing = false
                downloadPercent = null
                available = false
                status = AppLanguage.tr(
                    "本地语音初始化失败：${t.message ?: t.javaClass.simpleName}",
                    "Local speech init failed: ${t.message ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    private fun toggleInput() {
        showInput = !showInput
        if (showInput) {
            stopListening(cancel = true)
            resumeAfterReply = false
            status = AppLanguage.tr("输入你的问题", "Type your question")
        } else {
            resumeAfterReply = false
            status = when {
                preparing -> AppLanguage.tr(
                            "正在准备本地语音模型…",
                            "Preparing the local speech model…"
                        )
                available -> AppLanguage.tr("正在本地聆听…", "Listening on device…")
                else -> AppLanguage.tr("本地语音不可用", "Local speech unavailable")
            }
            if (available && !busy) startListening()
        }
    }

    private fun toggleListening() {
        if (preparing) {
            Toast.makeText(
                this,
                AppLanguage.tr("本地语音模型准备中，请稍候", "Preparing the speech model, please wait"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!available) {
            showInput = true
            resumeAfterReply = false
            status = AppLanguage.tr(
                "本地语音不可用，请输入文字",
                "Local speech unavailable; please type"
            )
            return
        }
        if (busy) {
            Toast.makeText(
                this,
                AppLanguage.tr("正在处理，请稍候", "Still working, please wait"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (isListening) {
            stopListening(cancel = true)
            resumeAfterReply = false
            status = AppLanguage.tr(
                "已停止，点按圆点继续或使用键盘输入",
                "Stopped. Tap the dot to continue or use the keyboard"
            )
        } else {
            transcript = ""
            startListening()
        }
    }

    private fun startListening() {
        val service = speechService ?: return
        if (isListening) return
        val started = runCatching {
            service.startListening(VoskListener())
        }.getOrDefault(false)
        if (!started) {
            isListening = false
            status = AppLanguage.tr(
                "无法启动本地麦克风，请用键盘输入",
                "Could not start the microphone; please use the keyboard"
            )
            return
        }
        isListening = true
        level = 0f
        transcript = ""
        userEdited = false
        status = AppLanguage.tr("正在本地聆听…", "Listening on device…")
    }

    private fun stopListening(cancel: Boolean) {
        if (!isListening) return
        isListening = false
        level = 0f
        runCatching {
            if (cancel) speechService?.cancel() else speechService?.stop()
        }
    }

    private fun applySpeech(text: String, final: Boolean) {
        if (text.isBlank()) return
        transcript = text
        if (!userEdited || final) input = text
        if (final) {
            isListening = false
            level = 0f
            status = AppLanguage.tr("识别完成", "Recognized")
        }
    }

    private fun submitInput() {
        val text = input.trim().ifBlank { transcript.trim() }
        submit(text)
    }

    private fun submit(text: String) {
        if (text.isBlank()) return
        if (!modelConfigured) {
            openModelSettings()
            return
        }
        stopListening(cancel = true)
        transcript = ""
        userEdited = false
        // submit() flips busy synchronously, so capture the prior state: a
        // failed/instant run must not leave the "queued" label behind.
        val wasBusy = AssistantConversation.busy.value
        if (!AssistantConversation.submit(this, text)) {
            status = AppLanguage.tr(
                "发送失败，请重试",
                "Could not send; try again"
            )
            return
        }
        input = ""
        // Release the IME after sending. Otherwise the keyboard (or Gboard's
        // floating toolbar) consumes the first back press before the mirror
        // can receive it.
        currentFocus?.let { view ->
            getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
        status = if (wasBusy) {
            AppLanguage.tr("已加入当前任务…", "Added to the current task…")
        } else {
            AppLanguage.tr("正在思考…", "Thinking…")
        }
        resumeAfterReply = true
    }

    private inner class VoskListener : VoskRecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (!isListening) return
            val text = hypothesisText(hypothesis, "partial")
            if (text.isNotBlank()) {
                applySpeech(text, final = false)
                status = AppLanguage.tr("正在本地聆听…", "Listening on device…")
            }
        }

        override fun onResult(hypothesis: String?) {
            val text = hypothesisText(hypothesis, "text")
            if (text.isBlank()) return
            applySpeech(text, final = true)
            speechService?.cancel()
            if (!showInput) submit(text)
        }

        override fun onFinalResult(hypothesis: String?) {
            if (!isListening) return
            val text = hypothesisText(hypothesis, "text")
            if (text.isBlank()) return
            applySpeech(text, final = true)
            speechService?.cancel()
            if (!showInput) submit(text)
        }

        override fun onError(exception: Exception?) {
            isListening = false
            level = 0f
            status = AppLanguage.tr(
                "本地语音识别失败：${exception?.message ?: "未知错误"}，请用键盘输入",
                "Speech recognition failed: ${exception?.message ?: "unknown error"}; " +
                    "please use the keyboard"
            )
        }

        override fun onTimeout() {
            isListening = false
            level = 0f
            status = AppLanguage.tr(
                "没有检测到语音，点按圆点重试或用键盘输入",
                "No speech detected. Tap the dot to retry or use the keyboard"
            )
        }
    }

    private fun hypothesisText(hypothesis: String?, field: String): String {
        if (hypothesis.isNullOrBlank()) return ""
        val raw = runCatching {
            JSONObject(hypothesis).optString(field).trim()
        }.getOrElse {
            hypothesis.trim().takeIf { !it.startsWith("{") }.orEmpty()
        }
        return normalizeSpeechText(raw)
    }

    companion object {
        const val EXTRA_INITIAL_PROMPT =
            "com.coke.nestspace.features.assistant.INITIAL_PROMPT"
        const val REQUEST_AUDIO = 0xA51
        const val SAMPLE_RATE = 16_000f
    }
}
