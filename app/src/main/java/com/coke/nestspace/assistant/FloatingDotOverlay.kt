package com.coke.nestspace.assistant

import android.animation.ValueAnimator
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.MainActivity
import com.coke.nestspace.AppGraph
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.coke.nestspace.agent.AssistantTaskCoordinator
import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.speech.AssistantSpeech
import com.coke.nestspace.speech.TtsSettings
import com.coke.nestspace.screenreader.ScreenReaderSuggestion
import com.coke.nestspace.screenreader.ScreenReaderSuggestionStore
import com.coke.nestspace.screenreader.ScreenReaderActionType
import com.coke.nestspace.task.OperationTaskRegistry
import com.coke.nestspace.tools.ContainerDisplayMode
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * iPhone assistive-touch style AI dot.
 *
 * Lives in the main process as a system overlay so it stays visible over both
 * the NestSpace desktop and virtual-app container windows. The color follows
 * the surface underneath: host screens use hostColor, containers use
 * containerColor. Tapping opens a compact tool panel with voice entry and
 * current worker-task status.
 */
object FloatingDotOverlay {

    private const val TAG = "FloatingDot"
    const val ACTION_MODE = "com.coke.nestspace.FLOATING_DOT_MODE"
    const val EXTRA_CONTAINER = "container"

    private var windowManager: WindowManager? = null
    private var dotView: DotView? = null
    private var params: WindowManager.LayoutParams? = null
    private var toolsView: ToolsPanelView? = null
    private var toolsTaskJob: Job? = null
    private var readerView: ReaderPanelView? = null
    private var readerTaskJob: Job? = null
    private var readerSpeechJob: Job? = null
    private var readerTaskId: String? = null
    private var spokenReaderTaskId: String? = null
    private var containerMode = false
    private var dockedEdge = DockEdge.RIGHT
    private var edgeRevealed = false
    private var autoDockRunnable: Runnable? = null
    /** Reasons currently showing a container-owned host surface (desktop, spaces). */
    private val containerSurfaces =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var suppressed = false
    private var assistantPageVisible = false
    private val overlayReasons = mutableSetOf<String>()
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val toolsScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingHostMode: Runnable? = null
    private var modeReceiver: BroadcastReceiver? = null
    private var configurationCallbacks: ComponentCallbacks? = null
    private var lastWindowHeight = 0

    @Synchronized
    fun ensure(context: Context) {
        // Container slots run as :c1... virtual hosts and may be renamed to
        // the target package. They must never create another system overlay.
        if (!isHostProcess()) return
        val app = context.applicationContext
        appContext = app
        val config = FloatingDotSettings.load(app)
        if (!config.enabled || !Settings.canDrawOverlays(app)) {
            removeView()
            return
        }
        registerModeReceiver(app)
        registerConfigurationCallbacks(app)
        if (dotView == null) {
            createView(app, config)
        } else {
            applyColor(config)
            clampToCurrentBounds(app)
        }
    }

    fun reload(context: Context) {
        ensure(context)
    }

    fun setSuppressed(context: Context, value: Boolean) {
        suppressed = value
        applyVisibility()
        if (!value) ensure(context)
    }

    /**
     * The in-app AI assistant page hides the dot: it has its own conversation
     * UI and the dot would otherwise overlap the input/keyboard. Kept separate
     * from [setSuppressed] so the voice Activity and the assistant tab can be
     * hidden independently without one clearing the other's state.
     */
    fun setAssistantPageVisible(context: Context, visible: Boolean) {
        assistantPageVisible = visible
        applyVisibility()
        if (!visible) ensure(context)
    }

    /**
     * Generic suppression for host UI that should not be covered, such as an
     * open desktop menu or folder sheet. Reasons are independent so closing a
     * menu doesn't override the voice Activity's suppression (and vice versa).
     */
    fun setOverlayHidden(context: Context, reason: String, hidden: Boolean) {
        if (hidden) overlayReasons.add(reason) else overlayReasons.remove(reason)
        applyVisibility()
        if (!hidden) ensure(context)
    }

    private fun isHidden(): Boolean =
        suppressed || assistantPageVisible || overlayReasons.isNotEmpty()

    private fun applyVisibility() {
        val hidden = isHidden()
        dotView?.visibility = if (
            hidden || toolsView != null || readerView != null
        ) {
            View.GONE
        } else {
            View.VISIBLE
        }
        if (hidden) {
            dismissToolsPanel()
            dismissReaderPanel()
        }
    }

    fun currentContainerMode(): Boolean =
        containerMode || containerSurfaces.isNotEmpty()

    fun currentColor(context: Context): Int {
        val config = FloatingDotSettings.load(context)
        return if (currentContainerMode()) config.containerColor else config.hostColor
    }

    /**
     * The NestSpace desktop is the container's home surface: it should use the
     * container color even when no virtual app Activity is open. Host pages
     * (settings, system apps) keep the host color; the built-in browser is
     * container-owned and therefore keeps the container color too.
     */
    fun setContainerDesktopVisible(context: Context, visible: Boolean) {
        setContainerSurfaceVisible(context, "desktop", visible)
    }

    /**
     * Keep the container color while a container-owned host surface is on
     * screen. Uses independent reasons so the desktop and the Spaces tab
     * cannot clear each other during a tab switch.
     */
    fun setContainerSurfaceVisible(
        context: Context,
        reason: String,
        visible: Boolean
    ) {
        if (visible) containerSurfaces.add(reason) else containerSurfaces.remove(reason)
        ensure(context)
        applyColor(FloatingDotSettings.load(context))
    }

    /** Host activity resumed: reset container mode; desktop/spaces re-apply it. */
    fun onHostActivityResumed(context: Context) {
        cancelPendingHostMode()
        containerMode = false
        ensure(context)
        applyColor(FloatingDotSettings.load(context))
    }

    /**
     * Container host Activity resumed/paused. False is debounced so switching
     * between two container Activities does not flicker host color.
     */
    fun onModeBroadcast(context: Context, intent: Intent) {
        val container = intent.getBooleanExtra(EXTRA_CONTAINER, false)
        ensure(context)
        if (container) {
            cancelPendingHostMode()
            containerMode = true
            // Entering a container means the assistant page is no longer the
            // visible surface, even if its lifecycle event has not arrived yet.
            assistantPageVisible = false
            applyColor(FloatingDotSettings.load(context))
        } else {
            cancelPendingHostMode()
            val runnable = Runnable {
                pendingHostMode = null
                containerMode = false
                applyColor(FloatingDotSettings.load(context))
            }
            pendingHostMode = runnable
            handler.postDelayed(runnable, HOST_MODE_DELAY_MS)
        }
    }

    fun publishContainerMode(context: Context, container: Boolean) {
        // StageAActivity's package name is the virtual target, not NestSpace.
        val intent = Intent(ACTION_MODE)
            .setPackage(HostIdentity.packageName)
            .putExtra(EXTRA_CONTAINER, container)
        context.sendBroadcast(intent)
    }

    private fun registerModeReceiver(app: Context) {
        if (modeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent != null) onModeBroadcast(context?.applicationContext ?: app, intent)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(
                    receiver,
                    android.content.IntentFilter(ACTION_MODE),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, android.content.IntentFilter(ACTION_MODE))
            }
            modeReceiver = receiver
        }.onFailure { Log.w(TAG, "mode receiver register failed", it) }
    }

    private fun unregisterModeReceiver(app: Context) {
        val receiver = modeReceiver ?: return
        modeReceiver = null
        runCatching { app.unregisterReceiver(receiver) }
    }

    private fun registerConfigurationCallbacks(app: Context) {
        if (configurationCallbacks != null) return
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(
                newConfig: android.content.res.Configuration
            ) {
                handler.post {
                    appContext?.let { current ->
                        dismissToolsPanel()
                        clampToCurrentBounds(current)
                    }
                }
            }

            override fun onLowMemory() = Unit
        }
        app.registerComponentCallbacks(callbacks)
        configurationCallbacks = callbacks
    }

    private fun unregisterConfigurationCallbacks(app: Context) {
        val callbacks = configurationCallbacks ?: return
        configurationCallbacks = null
        runCatching { app.unregisterComponentCallbacks(callbacks) }
    }

    private fun cancelPendingHostMode() {
        pendingHostMode?.let { handler.removeCallbacks(it) }
        pendingHostMode = null
    }

    private fun applyColor(config: FloatingDotSettings.Config) {
        dotView?.let { view ->
            view.dotColor = if (currentContainerMode()) config.containerColor else config.hostColor
            view.alpha = config.opacity / 100f
            view.visibility = if (
                isHidden() || toolsView != null || readerView != null
            ) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
    }

    private fun createView(app: Context, config: FloatingDotSettings.Config) {
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val density = app.resources.displayMetrics.density
        val size = (56 * density).toInt()
        val margin = (10 * density).toInt()
        val peek = (18 * density).toInt()
        val bounds = windowBounds(app, wm)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val initialY = if (config.y >= 0) config.y else (bounds.height() * 0.62f).toInt()
        dockedEdge = if (config.x >= 0 && config.x < bounds.width() / 2) {
            DockEdge.LEFT
        } else {
            DockEdge.RIGHT
        }
        edgeRevealed = !config.docked
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (edgeRevealed && config.x >= 0) {
                revealedX(dockedEdge, bounds, size, margin)
            } else {
                dockedX(dockedEdge, bounds, size, peek)
            }
            y = initialY
        }
        clampY(layoutParams, bounds, size, margin)

        val view = DotView(app).apply {
            dotColor = if (currentContainerMode()) config.containerColor else config.hostColor
            alpha = config.opacity / 100f
            visibility = if (isHidden()) View.GONE else View.VISIBLE
            onTap = {
                cancelAutoDock()
                toggleToolsPanel(app)
            }
            onDrag = { dx, dy ->
                dismissToolsPanel()
                cancelAutoDock()
                edgeRevealed = true
                val currentBounds = windowBounds(app, wm)
                val collapsedX = dockedX(
                    dockedEdge,
                    currentBounds,
                    size,
                    peek
                )
                val revealedX = revealedX(
                    dockedEdge,
                    currentBounds,
                    size,
                    margin
                )
                val minX = minOf(collapsedX, revealedX)
                val maxX = maxOf(collapsedX, revealedX)
                layoutParams.x = (layoutParams.x + dx).toInt()
                    .coerceIn(minX, maxX)
                layoutParams.y = (layoutParams.y + dy).toInt()
                clampY(layoutParams, currentBounds, size, margin)
                runCatching { wm.updateViewLayout(this, layoutParams) }
            }
            onDragEnd = {
                val currentBounds = windowBounds(app, wm)
                val collapsedX = dockedX(
                    dockedEdge,
                    currentBounds,
                    size,
                    peek
                )
                val revealedX = revealedX(
                    dockedEdge,
                    currentBounds,
                    size,
                    margin
                )
                val midpoint = (collapsedX + revealedX) / 2f
                edgeRevealed = when (dockedEdge) {
                    DockEdge.LEFT -> layoutParams.x > midpoint
                    DockEdge.RIGHT -> layoutParams.x < midpoint
                }
                layoutParams.x = if (edgeRevealed) revealedX else collapsedX
                clampY(layoutParams, currentBounds, size, margin)
                runCatching { wm.updateViewLayout(this, layoutParams) }
                FloatingDotSettings.savePosition(
                    app,
                    layoutParams.x,
                    layoutParams.y,
                    docked = !edgeRevealed
                )
                if (edgeRevealed) scheduleAutoDock(app, wm)
            }
        }

        val added = runCatching {
            wm.addView(view, layoutParams)
            true
        }.onFailure { Log.w(TAG, "floating dot overlay failed", it) }.getOrDefault(false)
        if (!added) return

        windowManager = wm
        dotView = view
        params = layoutParams
        lastWindowHeight = bounds.height()
        if (edgeRevealed) scheduleAutoDock(app, wm)
    }

    private fun clampToCurrentBounds(app: Context) {
        val wm = windowManager ?: return
        val view = dotView ?: return
        val layoutParams = params ?: return
        val density = app.resources.displayMetrics.density
        val margin = (10 * density).toInt()
        val peek = (18 * density).toInt()
        val bounds = windowBounds(app, wm)
        if (lastWindowHeight > 0 && lastWindowHeight != bounds.height()) {
            layoutParams.y = (
                layoutParams.y.toFloat() /
                    lastWindowHeight *
                    bounds.height()
                ).roundToInt()
        }
        lastWindowHeight = bounds.height()
        layoutParams.x = if (edgeRevealed) {
            revealedX(dockedEdge, bounds, layoutParams.width, margin)
        } else {
            dockedX(dockedEdge, bounds, layoutParams.width, peek)
        }
        clampY(layoutParams, bounds, layoutParams.width, margin)
        runCatching { wm.updateViewLayout(view, layoutParams) }
    }

    private fun windowBounds(app: Context, wm: WindowManager): android.graphics.Rect =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds
            } else {
                android.graphics.Rect(
                    0,
                    0,
                    app.resources.displayMetrics.widthPixels,
                    app.resources.displayMetrics.heightPixels
                )
            }
        }.getOrElse {
            android.graphics.Rect(
                0,
                0,
                app.resources.displayMetrics.widthPixels,
                app.resources.displayMetrics.heightPixels
            )
        }

    private fun clampY(
        layoutParams: WindowManager.LayoutParams,
        bounds: android.graphics.Rect,
        size: Int,
        margin: Int
    ) {
        val minY = (64 * (size / 64f)).toInt()
        val maxY = maxOf(minY, bounds.height() - size - (100 * (size / 64f)).toInt())
        layoutParams.y = layoutParams.y.coerceIn(minY, maxY)
    }

    private fun dockedX(
        edge: DockEdge,
        bounds: android.graphics.Rect,
        size: Int,
        peek: Int
    ): Int = when (edge) {
        DockEdge.LEFT -> -size + peek
        DockEdge.RIGHT -> bounds.width() - peek
    }

    private fun revealedX(
        edge: DockEdge,
        bounds: android.graphics.Rect,
        size: Int,
        margin: Int
    ): Int = when (edge) {
        DockEdge.LEFT -> margin
        DockEdge.RIGHT -> bounds.width() - size - margin
    }

    private fun revealDot(app: Context, wm: WindowManager) {
        val layoutParams = params ?: return
        val density = app.resources.displayMetrics.density
        val margin = (10 * density).toInt()
        val bounds = windowBounds(app, wm)
        edgeRevealed = true
        layoutParams.x = revealedX(
            dockedEdge,
            bounds,
            layoutParams.width,
            margin
        )
        clampY(layoutParams, bounds, layoutParams.width, margin)
        runCatching { wm.updateViewLayout(dotView, layoutParams) }
        FloatingDotSettings.savePosition(
            app,
            layoutParams.x,
            layoutParams.y,
            docked = false
        )
    }

    private fun scheduleAutoDock(app: Context, wm: WindowManager) {
        cancelAutoDock()
        val runnable = Runnable {
            autoDockRunnable = null
            val layoutParams = params ?: return@Runnable
            val density = app.resources.displayMetrics.density
            val peek = (18 * density).toInt()
            val bounds = windowBounds(app, wm)
            edgeRevealed = false
            layoutParams.x = dockedX(
                dockedEdge,
                bounds,
                layoutParams.width,
                peek
            )
            runCatching { wm.updateViewLayout(dotView, layoutParams) }
            FloatingDotSettings.savePosition(
                app,
                layoutParams.x,
                layoutParams.y,
                docked = true
            )
        }
        autoDockRunnable = runnable
        handler.postDelayed(runnable, EDGE_AUTO_DOCK_DELAY_MS)
    }

    private fun cancelAutoDock() {
        autoDockRunnable?.let(handler::removeCallbacks)
        autoDockRunnable = null
    }

    @Synchronized
    private fun removeView() {
        appContext?.let {
            unregisterModeReceiver(it)
            unregisterConfigurationCallbacks(it)
        }
        cancelAutoDock()
        dismissToolsPanel()
        dismissReaderPanel()
        val view = dotView ?: return
        dotView = null
        params = null
        view.animate().cancel()
        runCatching { windowManager?.removeView(view) }
        windowManager = null
        lastWindowHeight = 0
    }

    private fun openAssistant(app: Context) {
        val intent = Intent(app, AssistantVoiceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "open assistant failed", it) }
    }

    private fun openTasks(app: Context) {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            .putExtra(MainActivity.EXTRA_OPEN_TASKS, true)
        runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "open tasks failed", it) }
    }

    private fun openScreenReader(app: Context) {
        dismissToolsPanel()
        showReaderPanel(app)
        startScreenReaderTask(
            app,
            AppLanguage.tr(
                "读取当前容器页面并解释屏幕。先调用 screen_read，不要执行页面动作。" +
                    "根据页面和我最近的要求给出一个推荐意图及最多三个替代选项；" +
                    "意图不明确时直接询问我。需要定位时调用 screen_highlight。",
                "Read and explain the current container screen. Call screen_read first and " +
                    "do not execute page actions. Offer one recommended intent and at most " +
                    "three alternatives; ask directly when intent is ambiguous. Call " +
                    "screen_highlight when locating content."
            )
        )
    }

    private fun showReaderPanel(app: Context) {
        if (readerView != null) return
        val wm = windowManager ?: return
        val bounds = windowBounds(app, wm)
        val density = app.resources.displayMetrics.density
        val width = minOf(
            (430 * density).toInt(),
            bounds.width() - (32 * density).toInt()
        )
        val height = minOf(
            (320 * density).toInt(),
            bounds.height() - (32 * density).toInt()
        )
        val margin = (16 * density).toInt()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val layoutParams = WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((bounds.width() - width) / 2)
                .coerceIn(margin, maxOf(margin, bounds.width() - width - margin))
            y = (bounds.height() - height - (76 * density).toInt())
                .coerceIn(margin, maxOf(margin, bounds.height() - height - margin))
        }
        val panel = ReaderPanelView(
            context = app,
            accentColor = currentColor(app),
            canSpeak = TtsSettings.load(app).enabled,
            onPrimary = {
                val active = readerTaskId
                    ?.let { AssistantTaskCoordinator.get(it, "local-llm") }
                if (active != null && !active.state.terminal) {
                    AssistantTaskCoordinator.cancel(active.id, "local-llm")
                } else {
                    startScreenReaderTask(
                        app,
                        AppLanguage.tr(
                            "重新读取当前容器页面并解释屏幕，不执行页面动作。",
                            "Read and explain the current container screen again without " +
                                "executing page actions."
                        )
                    )
                }
            },
            onSpeak = {
                val result = readerTaskId
                    ?.let { AssistantTaskCoordinator.get(it, "local-llm") }
                    ?.result
                if (!result.isNullOrBlank()) {
                    speakReaderResult(app, result)
                }
            },
            onSuggestion = { suggestion ->
                if (suggestion.type == ScreenReaderActionType.OPEN_APP) {
                    startReaderTask(
                        app = app,
                        goal = suggestion.goal,
                        agentMode = AssistantTaskCoordinator.AgentMode.ASSISTANT,
                        displayTitle = AppLanguage.tr("打开应用", "Open app")
                    )
                } else {
                    startScreenReaderTask(app, suggestion.goal)
                }
            },
            onClose = ::dismissReaderPanel
        )
        val added = runCatching {
            wm.addView(panel, layoutParams)
            true
        }.onFailure { Log.w(TAG, "screen reader overlay failed", it) }
            .getOrDefault(false)
        if (!added) return
        readerView = panel
        dotView?.visibility = View.GONE
        panel.alpha = 0f
        panel.translationY = 18f * density
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .start()
    }

    private fun startScreenReaderTask(app: Context, goal: String) {
        startReaderTask(
            app = app,
            goal = goal,
            agentMode = AssistantTaskCoordinator.AgentMode.SCREEN_READER,
            displayTitle = AppLanguage.tr("屏幕解读", "Screen reading")
        )
    }

    private fun startReaderTask(
        app: Context,
        goal: String,
        agentMode: AssistantTaskCoordinator.AgentMode,
        displayTitle: String
    ) {
        readerSpeechJob?.cancel()
        readerSpeechJob = null
        AssistantSpeech.stop()
        spokenReaderTaskId = null
        readerTaskJob?.cancel()
        readerTaskId?.let(ScreenReaderSuggestionStore::clear)
        readerView?.showWorking()
        val result = AssistantTaskCoordinator.enqueue(
            context = app,
            registry = AppGraph.registry(app),
            ownerId = "local-llm",
            goal = goal,
            extraContext = "",
            displayMode = ContainerDisplayMode.VISIBLE,
            source = if (agentMode == AssistantTaskCoordinator.AgentMode.SCREEN_READER) {
                "screen_reader"
            } else {
                "assistant"
            },
            displayTitle = displayTitle,
            agentMode = agentMode
        )
        when (result) {
            is AssistantTaskCoordinator.EnqueueResult.Error -> {
                readerTaskId = null
                readerView?.showError(result.message)
            }
            is AssistantTaskCoordinator.EnqueueResult.Queued -> {
                readerTaskId = result.task.id
                observeReaderTask(app, result.task.id)
            }
        }
    }

    private fun observeReaderTask(app: Context, taskId: String) {
        readerTaskJob?.cancel()
        readerTaskJob = toolsScope.launch {
            OperationTaskRegistry.tasksFlow.collectLatest {
                val task = AssistantTaskCoordinator.get(taskId, "local-llm")
                    ?: return@collectLatest
                readerView?.update(task)
                val terminal = task.state.terminal
                if (terminal &&
                    spokenReaderTaskId != taskId &&
                    !task.result.isNullOrBlank()
                ) {
                    spokenReaderTaskId = taskId
                    speakReaderResult(app, task.result)
                }
            }
        }
    }

    private fun speakReaderResult(app: Context, text: String) {
        val config = TtsSettings.load(app)
        if (!config.enabled) return
        readerSpeechJob?.cancel()
        AssistantSpeech.stop()
        readerSpeechJob = toolsScope.launch {
            if (readerView == null || isHidden()) return@launch
            runCatching {
                AssistantSpeech.speak(
                    context = app,
                    text = text,
                    providerId = config.providerId,
                    interrupt = true
                )
            }
        }
    }

    private fun dismissReaderPanel() {
        readerSpeechJob?.cancel()
        readerSpeechJob = null
        AssistantSpeech.stop()
        readerTaskJob?.cancel()
        readerTaskJob = null
        readerTaskId?.let(ScreenReaderSuggestionStore::clear)
        readerTaskId = null
        spokenReaderTaskId = null
        val view = readerView ?: return
        readerView = null
        runCatching { windowManager?.removeView(view) }
        dotView?.visibility = if (isHidden()) View.GONE else View.VISIBLE
    }

    private fun toggleToolsPanel(app: Context) {
        if (toolsView != null) {
            dismissToolsPanel()
        } else {
            showToolsPanel(app)
        }
    }

    private fun showToolsPanel(app: Context) {
        val wm = windowManager ?: return
        val dotParams = params ?: return
        val bounds = windowBounds(app, wm)
        val density = app.resources.displayMetrics.density
        val width = (244 * density).toInt()
        val height = (112 * density).toInt()
        val margin = (10 * density).toInt()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val panelParams = WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (dotParams.x + dotParams.width / 2 > bounds.width() / 2) {
                dotParams.x + dotParams.width - width
            } else {
                dotParams.x
            }.coerceIn(margin, maxOf(margin, bounds.width() - width - margin))
            val desiredY =
                dotParams.y +
                    dotParams.height / 2 -
                    height / 2
            y = desiredY.coerceIn(
                margin,
                maxOf(margin, bounds.height() - height - margin)
            )
        }
        val panel = ToolsPanelView(
            context = app,
            accentColor = currentColor(app),
            onVoice = {
                dismissToolsPanel()
                openAssistant(app)
            },
            onTasks = {
                dismissToolsPanel()
                openTasks(app)
            },
            onRead = {
                dismissToolsPanel()
                openScreenReader(app)
            },
            onClose = ::dismissToolsPanel
        )
        val added = runCatching {
            wm.addView(panel, panelParams)
            true
        }.onFailure { Log.w(TAG, "floating tools panel failed", it) }
            .getOrDefault(false)
        if (!added) return
        toolsView = panel
        dotView?.visibility = View.GONE
        panel.pivotX =
            if (panelParams.x + panelParams.width / 2 > bounds.width() / 2) {
                panelParams.width.toFloat()
            } else {
                0f
            }
        panel.pivotY = panelParams.height / 2f
        panel.alpha = 0f
        panel.scaleX = 0.18f
        panel.scaleY = 0.18f
        panel.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .start()
        updateToolsTaskCount()
        toolsTaskJob = toolsScope.launch {
            OperationTaskRegistry.tasksFlow.collectLatest {
                updateToolsTaskCount()
            }
        }
    }

    private fun updateToolsTaskCount() {
        val count = OperationTaskRegistry.tasksFlow.value.count {
            it.kind == AssistantTaskCoordinator.TASK_KIND && !it.terminal
        }
        toolsView?.updateTaskCount(count)
    }

    private fun dismissToolsPanel() {
        toolsTaskJob?.cancel()
        toolsTaskJob = null
        val view = toolsView ?: return
        toolsView = null
        runCatching { windowManager?.removeView(view) }
        val app = appContext
        val wm = windowManager
        if (!isHidden() && app != null && wm != null) {
            revealDot(app, wm)
            scheduleAutoDock(app, wm)
        }
        dotView?.visibility = if (isHidden()) View.GONE else View.VISIBLE
    }

    private fun isHostProcess(): Boolean {
        val frameworkName = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                null
            }
        }.getOrNull()
        if (frameworkName != null && frameworkName != HostIdentity.packageName) return false
        val cmdline = runCatching {
            File("/proc/self/cmdline").readText().trim('\u0000')
        }.getOrNull().orEmpty()
        if (cmdline.isNotBlank()) return cmdline == HostIdentity.packageName
        return frameworkName == HostIdentity.packageName
    }

    private enum class DockEdge { LEFT, RIGHT }

    private const val HOST_MODE_DELAY_MS = 350L
    private const val EDGE_AUTO_DOCK_DELAY_MS = 10_000L

    /** Manifest receiver: also starts the main process if it was killed. */
    class ModeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onModeBroadcast(context.applicationContext, intent)
        }
    }

    private class ReaderPanelView(
        context: Context,
        private val accentColor: Int,
        private val canSpeak: Boolean,
        private val onPrimary: () -> Unit,
        private val onSpeak: () -> Unit,
        private val onSuggestion: (ScreenReaderSuggestion) -> Unit,
        private val onClose: () -> Unit
    ) : LinearLayout(context) {
        private val density = resources.displayMetrics.density
        private val statusText: TextView
        private val speakButton: View
        private val bodyText: TextView
        private val bodyScroll: ScrollView
        private val readingIndicator: ReadingIndicatorView
        private val footerRow: LinearLayout
        private var terminal = false
        private var footerKey: String? = null

        init {
            orientation = VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            elevation = dp(14).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(0xE61B1C24.toInt())
                setStroke(dp(1), ColorUtils.setAlphaComponent(accentColor, 100))
            }

            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = AppLanguage.tr("智能读屏", "Screen reader")
                            setTextColor(Color.WHITE)
                            textSize = 18f
                            setTypeface(typeface, Typeface.BOLD)
                            layoutParams = LayoutParams(0, dp(32), 1f)
                        }
                    )
                    speakButton = speakerButton(onSpeak)
                    addView(
                        speakButton,
                        LayoutParams(dp(30), dp(30)).apply {
                            marginEnd = dp(5)
                        }
                    )
                    statusText = TextView(context).apply {
                        text = AppLanguage.tr("准备中", "Preparing")
                        setTextColor(ColorUtils.setAlphaComponent(accentColor, 255))
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setPadding(dp(9), 0, dp(9), 0)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(12).toFloat()
                            setColor(ColorUtils.setAlphaComponent(accentColor, 48))
                        }
                        layoutParams = LayoutParams(dp(68), dp(24))
                    }
                    addView(statusText)
                    addView(
                        TextView(context).apply {
                            text = "×"
                            contentDescription = AppLanguage.tr("关闭读屏", "Close screen reader")
                            gravity = Gravity.CENTER
                            setTextColor(0xFFCAC7D2.toInt())
                            textSize = 22f
                            isClickable = true
                            setOnClickListener { onClose() }
                            layoutParams = LayoutParams(dp(34), dp(30)).apply {
                                marginStart = dp(5)
                            }
                        }
                    )
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(34))
            )

            readingIndicator = ReadingIndicatorView(context).apply {
                visibility = View.VISIBLE
            }
            addView(
                readingIndicator,
                LayoutParams(LayoutParams.MATCH_PARENT, dp(18)).apply {
                    topMargin = dp(7)
                }
            )

            bodyText = TextView(context).apply {
                setTextColor(0xFFF4F4F8.toInt())
                textSize = 14f
                setLineSpacing(dp(3).toFloat(), 1.08f)
                text = AppLanguage.tr(
                    "正在读取当前页面…",
                    "Reading the current screen…"
                )
            }
            bodyScroll = ScrollView(context).apply {
                isFillViewport = true
                addView(
                    bodyText,
                    LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT
                    )
                )
            }
            addView(
                bodyScroll,
                LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = dp(10)
                }
            )

            footerRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(
                HorizontalScrollView(context).apply {
                    isFillViewport = true
                    isHorizontalScrollBarEnabled = false
                    addView(
                        footerRow,
                        LayoutParams(
                            LayoutParams.WRAP_CONTENT,
                            LayoutParams.MATCH_PARENT
                        )
                    )
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(42)).apply {
                    topMargin = dp(12)
                }
            )

            renderFooter(
                key = "working",
                stop = { onPrimary() }
            )
            speakButton.visibility = View.GONE
        }

        fun showWorking() {
            terminal = false
            statusText.text = AppLanguage.tr("读屏中", "Reading")
            setBody("", AppLanguage.tr(
                "正在理解当前页面，请稍候…",
                "Understanding the current screen…"
            ))
            readingIndicator.show()
            renderFooter(
                key = "working",
                stop = { onPrimary() }
            )
        }

        fun showError(message: String) {
            terminal = true
            statusText.text = AppLanguage.tr("失败", "Failed")
            setBody("error:$message", message)
            readingIndicator.hide()
            speakButton.visibility = View.GONE
            renderFooter(
                key = "error:$message",
                retry = { onPrimary() }
            )
        }

        fun update(task: AssistantTaskCoordinator.TaskView) {
            terminal = task.state.terminal
            statusText.text = when (task.state) {
                OperationTaskRegistry.State.QUEUED,
                OperationTaskRegistry.State.PREPARING ->
                    AppLanguage.tr("等待中", "Waiting")
                OperationTaskRegistry.State.RUNNING,
                OperationTaskRegistry.State.FINALIZING ->
                    AppLanguage.tr("读取中", "Reading")
                OperationTaskRegistry.State.PAUSED ->
                    AppLanguage.tr("已暂停", "Paused")
                OperationTaskRegistry.State.WAITING_USER ->
                    AppLanguage.tr("待处理", "Needs you")
                OperationTaskRegistry.State.CANCELLING ->
                    AppLanguage.tr("停止中", "Stopping")
                OperationTaskRegistry.State.SUCCEEDED ->
                    AppLanguage.tr("已完成", "Done")
                OperationTaskRegistry.State.FAILED ->
                    AppLanguage.tr("失败", "Failed")
                OperationTaskRegistry.State.CANCELLED ->
                    AppLanguage.tr("已停止", "Stopped")
            }
            val workingBody = when (task.state) {
                OperationTaskRegistry.State.QUEUED,
                OperationTaskRegistry.State.PREPARING ->
                    AppLanguage.tr(
                        "正在准备读屏，请稍候…",
                        "Preparing the screen reader…"
                    )
                OperationTaskRegistry.State.CANCELLING ->
                    AppLanguage.tr("正在停止读屏…", "Stopping the screen reader…")
                OperationTaskRegistry.State.PAUSED ->
                    AppLanguage.tr("读屏已暂停。", "Screen reading is paused.")
                else -> AppLanguage.tr(
                    "正在理解当前页面，请稍候…",
                    "Understanding the current screen…"
                )
            }
            val body = when {
                task.state == OperationTaskRegistry.State.CANCELLED ->
                    AppLanguage.tr(
                        "读屏已停止。",
                        "Screen reading stopped."
                    )
                !task.result.isNullOrBlank() -> task.result
                !task.error.isNullOrBlank() -> task.error
                !terminal -> workingBody
                else -> AppLanguage.tr(
                    "读屏已完成。",
                    "Screen reading finished."
                )
            }
            setBody("${task.state}:${task.progressRevision}:$body", body)
            if (!terminal) {
                readingIndicator.show()
                speakButton.visibility = View.GONE
                renderFooter(
                    key = "running:${task.state}",
                    stop = { onPrimary() }
                )
                return
            }
            readingIndicator.hide()
            speakButton.visibility = if (
                canSpeak &&
                task.state == OperationTaskRegistry.State.SUCCEEDED &&
                !task.result.isNullOrBlank()
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
            val suggestions = ScreenReaderSuggestionStore.get(task.id)
            val key = buildString {
                append(task.id)
                append('|')
                append(task.state)
                append('|')
                append(canSpeak && !task.result.isNullOrBlank())
                suggestions.forEach {
                    append('|').append(it.label)
                }
            }
            when {
                task.state == OperationTaskRegistry.State.SUCCEEDED &&
                    suggestions.isNotEmpty() -> renderFooter(
                        key = key,
                        suggestions = suggestions,
                        onSuggestion = onSuggestion
                    )
                else -> renderFooter(
                    key = key,
                    retry = { onPrimary() }
                )
            }
        }

        private fun setBody(key: String, value: String) {
            if (bodyText.text.toString() == value && bodyText.tag == key) return
            val old = bodyText.text
            bodyText.text = value
            bodyText.tag = key
            if (old != value) bodyScroll.scrollTo(0, 0)
        }

        private fun renderFooter(
            key: String,
            stop: (() -> Unit)? = null,
            retry: (() -> Unit)? = null,
            suggestions: List<ScreenReaderSuggestion> = emptyList(),
            onSuggestion: ((ScreenReaderSuggestion) -> Unit)? = null
        ) {
            if (footerKey == key) return
            footerKey = key
            footerRow.removeAllViews()
            when {
                stop != null -> footerRow.addView(
                    actionButton(
                        label = AppLanguage.tr("停止", "Stop"),
                        contentDescription = AppLanguage.tr("停止读屏", "Stop screen reader"),
                        onClick = stop
                    )
                )
                retry != null -> footerRow.addView(
                    actionButton(
                        label = AppLanguage.tr("重试", "Retry"),
                        contentDescription = AppLanguage.tr("重试读屏", "Retry screen reader"),
                        onClick = retry
                    )
                )
                else -> {
                    suggestions.forEach { suggestion ->
                        footerRow.addView(
                            actionButton(
                                label = suggestion.label,
                                contentDescription = AppLanguage.tr(
                                    "建议：${suggestion.label}",
                                    "Suggestion: ${suggestion.label}"
                                ),
                                onClick = {
                                    onSuggestion?.invoke(suggestion)
                                }
                            )
                        )
                    }
                }
            }
        }

        private fun actionButton(
            label: String,
            contentDescription: String,
            onClick: () -> Unit
        ): LinearLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            minimumWidth = dp(78)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            this.contentDescription = contentDescription
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT
            ).apply {
                marginEnd = dp(7)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(accentColor, 70)
                ),
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(13).toFloat()
                    setColor(ColorUtils.setAlphaComponent(accentColor, 42))
                },
                null
            )
            addView(
                TextView(context).apply {
                    text = label
                    maxWidth = dp(154)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                }
            )
            setOnClickListener { onClick() }
        }

        private fun speakerButton(onClick: () -> Unit): View =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = AppLanguage.tr(
                    "朗读当前读屏结果",
                    "Speak the screen-reader result"
                )
                background = RippleDrawable(
                    ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(accentColor, 70)
                    ),
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ColorUtils.setAlphaComponent(accentColor, 42))
                    },
                    null
                )
                addView(
                    SpeakerGlyph(context),
                    LayoutParams(dp(17), dp(17))
                )
                setOnClickListener { onClick() }
            }

        private inner class SpeakerGlyph(context: Context) : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = dp(1.6f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val width = width.toFloat()
                val height = height.toFloat()
                val path = android.graphics.Path().apply {
                    moveTo(width * 0.08f, height * 0.40f)
                    lineTo(width * 0.30f, height * 0.40f)
                    lineTo(width * 0.54f, height * 0.20f)
                    lineTo(width * 0.54f, height * 0.80f)
                    lineTo(width * 0.30f, height * 0.60f)
                    lineTo(width * 0.08f, height * 0.60f)
                    close()
                }
                canvas.drawPath(path, fill)
                canvas.drawArc(
                    width * 0.48f,
                    height * 0.30f,
                    width * 0.78f,
                    height * 0.70f,
                    -55f,
                    110f,
                    false,
                    paint
                )
                canvas.drawArc(
                    width * 0.42f,
                    height * 0.17f,
                    width * 0.95f,
                    height * 0.83f,
                    -55f,
                    110f,
                    false,
                    paint
                )
            }
        }

        private inner class ReadingIndicatorView(context: Context) : View(context) {
            private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(accentColor, 48)
                style = Paint.Style.STROKE
                strokeWidth = dp(2f)
                strokeCap = Paint.Cap.ROUND
            }
            private val movingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.STROKE
                strokeWidth = dp(2.5f)
                strokeCap = Paint.Cap.ROUND
            }
            private var animator: ValueAnimator? = null
            private var running = false
            private var phase = 0f

            fun show() {
                if (visibility == View.VISIBLE && running) return
                visibility = View.VISIBLE
                running = true
                animator?.cancel()
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1_250L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    addUpdateListener {
                        phase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }

            fun hide() {
                running = false
                animator?.cancel()
                animator = null
                visibility = View.GONE
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val centerY = height / 2f
                val inset = dp(2f)
                canvas.drawLine(inset, centerY, width - inset, centerY, trackPaint)
                val span = (width * 0.18f).coerceAtLeast(dp(24f))
                val start = -span + phase * (width + span * 2f)
                val left = start.coerceIn(inset, (width - inset).toFloat())
                val right = (start + span)
                    .coerceIn(inset, (width - inset).toFloat())
                if (right > left) {
                    canvas.drawLine(left, centerY, right, centerY, movingPaint)
                }
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                if (visibility == View.VISIBLE) show()
            }

            override fun onDetachedFromWindow() {
                animator?.cancel()
                animator = null
                running = false
                super.onDetachedFromWindow()
            }
        }

        private fun dp(value: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                resources.displayMetrics
            ).toInt()

        private fun dp(value: Float): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                resources.displayMetrics
            )
    }

    private class ToolsPanelView(
        context: Context,
        private val accentColor: Int,
        onVoice: () -> Unit,
        onTasks: () -> Unit,
        onRead: () -> Unit,
        onClose: () -> Unit
    ) : LinearLayout(context) {

        private val density = resources.displayMetrics.density
        private val night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        private val primaryText = if (night) Color.WHITE else 0xFF1D1B20.toInt()
        private val secondaryText = if (night) 0xFFCAC4D0.toInt() else 0xFF625B71.toInt()
        private val tasksAction: ToolActionView

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
            elevation = dp(10).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(
                    ColorUtils.setAlphaComponent(
                        if (night) 0xFF202025.toInt() else 0xFFFFFFFF.toInt(),
                        if (night) 238 else 244
                    )
                )
                setStroke(dp(1), ColorUtils.setAlphaComponent(accentColor, 58))
            }

            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    orientation = HORIZONTAL
                    addView(
                        TextView(context).apply {
                            text = "×"
                            contentDescription = AppLanguage.tr("关闭工具", "Close tools")
                            gravity = Gravity.CENTER
                            setTextColor(secondaryText)
                            textSize = 20f
                            isClickable = true
                            setOnClickListener { onClose() }
                            layoutParams = LayoutParams(dp(28), dp(24))
                        }
                    )
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(24))
            )

            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        ToolActionView(
                            kind = ToolIcon.VOICE,
                            label = AppLanguage.tr("语音", "Voice"),
                            onClick = onVoice
                        ),
                        LayoutParams(0, dp(56), 1f).apply {
                            marginEnd = dp(4)
                        }
                    )
                    tasksAction = ToolActionView(
                        kind = ToolIcon.TASKS,
                        label = AppLanguage.tr("任务", "Tasks"),
                        onClick = onTasks
                    ).also {
                        addView(
                            it,
                            LayoutParams(0, dp(56), 1f).apply {
                                marginStart = dp(4)
                            }
                        )
                    }
                    addView(
                        ToolActionView(
                            kind = ToolIcon.SCREEN,
                            label = AppLanguage.tr("读屏", "Read"),
                            onClick = onRead
                        ),
                        LayoutParams(0, dp(56), 1f).apply {
                            marginStart = dp(8)
                        }
                    )
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(64)).apply {
                    topMargin = dp(2)
                }
            )
        }

        fun updateTaskCount(count: Int) {
            tasksAction.badgeCount = count
        }

        private inner class ToolActionView(
            private val kind: ToolIcon,
            private val label: String,
            onClick: () -> Unit
        ) : View(context) {
            var badgeCount: Int = 0
                set(value) {
                    field = value.coerceAtLeast(0)
                    contentDescription = when (kind) {
                        ToolIcon.VOICE ->
                            AppLanguage.tr("打开语音助手", "Open voice assistant")
                        ToolIcon.TASKS -> AppLanguage.tr(
                            "查看任务，当前执行中 ${field.coerceAtMost(99)} 个",
                            "View tasks, ${field.coerceAtMost(99)} active"
                        )
                        ToolIcon.SCREEN -> AppLanguage.tr(
                            "读取并理解当前屏幕",
                            "Read and understand this screen"
                        )
                    }
                    invalidate()
                }

            private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.FILL
            }
            private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = dp(10).toFloat()
                typeface = Typeface.DEFAULT_BOLD
            }
            private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = primaryText
                textAlign = Paint.Align.CENTER
                textSize = dp(13).toFloat()
                typeface = Typeface.DEFAULT_BOLD
            }

            init {
                isClickable = true
                isFocusable = true
                background = RippleDrawable(
                    ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(accentColor, 48)
                    ),
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(18).toFloat()
                        setColor(
                            ColorUtils.setAlphaComponent(
                                accentColor,
                                if (night) 52 else 26
                            )
                        )
                    },
                    null
                )
                setOnClickListener { onClick() }
                badgeCount = 0
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val centerX = width / 2f
                val centerY = height * 0.42f
                val iconSize = min(width, height) * 0.30f
                when (kind) {
                    ToolIcon.VOICE -> drawMicrophone(
                        canvas,
                        centerX,
                        centerY,
                        iconSize
                    )
                    ToolIcon.TASKS -> {
                        drawTaskList(
                            canvas,
                            centerX - iconSize * 0.12f,
                            centerY,
                            iconSize
                        )
                        drawBadge(canvas, centerX, centerY, iconSize)
                    }
                    ToolIcon.SCREEN -> drawScreenReader(
                        canvas,
                        centerX,
                        centerY,
                        iconSize
                    )
                }
                val baseline = height * 0.82f
                canvas.drawText(label, centerX, baseline, labelPaint)
            }

            private fun drawMicrophone(
                canvas: Canvas,
                cx: Float,
                cy: Float,
                size: Float
            ) {
                val bodyWidth = size * 0.34f
                canvas.drawRoundRect(
                    RectF(
                        cx - bodyWidth / 2f,
                        cy - size * 0.48f,
                        cx + bodyWidth / 2f,
                        cy + size * 0.10f
                    ),
                    bodyWidth / 2f,
                    bodyWidth / 2f,
                    iconPaint
                )
                canvas.drawArc(
                    RectF(
                        cx - size * 0.30f,
                        cy - size * 0.14f,
                        cx + size * 0.30f,
                        cy + size * 0.38f
                    ),
                    0f,
                    180f,
                    false,
                    iconPaint
                )
                canvas.drawLine(
                    cx,
                    cy + size * 0.38f,
                    cx,
                    cy + size * 0.55f,
                    iconPaint
                )
                canvas.drawLine(
                    cx - size * 0.16f,
                    cy + size * 0.55f,
                    cx + size * 0.16f,
                    cy + size * 0.55f,
                    iconPaint
                )
            }

            private fun drawTaskList(
                canvas: Canvas,
                cx: Float,
                cy: Float,
                size: Float
            ) {
                val left = cx - size * 0.38f
                val lineStart = cx - size * 0.08f
                val lineEnd = cx + size * 0.38f
                listOf(-0.32f, 0f, 0.32f).forEachIndexed { index, offset ->
                    val y = cy + size * offset
                    canvas.drawCircle(
                        left,
                        y,
                        size * 0.075f,
                        iconPaint
                    )
                    canvas.drawLine(
                        lineStart,
                        y,
                        lineEnd - index * size * 0.06f,
                        y,
                        iconPaint
                    )
                }
            }

            private fun drawScreenReader(
                canvas: Canvas,
                cx: Float,
                cy: Float,
                size: Float
            ) {
                val left = cx - size * 0.38f
                val right = cx + size * 0.38f
                val top = cy - size * 0.40f
                val bottom = cy + size * 0.40f
                val corner = size * 0.18f
                canvas.drawLine(left, top + corner, left, top, iconPaint)
                canvas.drawLine(left, top, left + corner, top, iconPaint)
                canvas.drawLine(right - corner, top, right, top, iconPaint)
                canvas.drawLine(right, top, right, top + corner, iconPaint)
                canvas.drawLine(right, bottom - corner, right, bottom, iconPaint)
                canvas.drawLine(right, bottom, right - corner, bottom, iconPaint)
                canvas.drawLine(left, bottom, left + corner, bottom, iconPaint)
                canvas.drawLine(left, bottom - corner, left, bottom, iconPaint)
                listOf(-0.15f, 0.08f, 0.27f).forEachIndexed { index, offset ->
                    canvas.drawLine(
                        left + size * 0.18f,
                        cy + size * offset,
                        right - size * (0.24f + index * 0.05f),
                        cy + size * offset,
                        iconPaint
                    )
                }
            }

            private fun drawBadge(
                canvas: Canvas,
                cx: Float,
                cy: Float,
                size: Float
            ) {
                val label = if (badgeCount > 99) "99+" else badgeCount.toString()
                badgeTextPaint.textSize = if (label.length >= 2) {
                    dp(9).toFloat()
                } else {
                    dp(10).toFloat()
                }
                val minWidth = dp(18).toFloat()
                val badgeWidth = maxOf(
                    minWidth,
                    badgeTextPaint.measureText(label) + dp(8)
                )
                val badgeHeight = dp(18).toFloat()
                val badgeLeft = cx + size * 0.18f
                val badgeTop = cy - size * 0.58f
                canvas.drawRoundRect(
                    RectF(
                        badgeLeft,
                        badgeTop,
                        badgeLeft + badgeWidth,
                        badgeTop + badgeHeight
                    ),
                    badgeHeight / 2f,
                    badgeHeight / 2f,
                    badgePaint
                )
                val baseline = badgeTop +
                    badgeHeight / 2f -
                    (badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2f
                canvas.drawText(
                    label,
                    badgeLeft + badgeWidth / 2f,
                    baseline,
                    badgeTextPaint
                )
            }
        }

        private enum class ToolIcon { VOICE, TASKS, SCREEN }

        private fun dp(value: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                resources.displayMetrics
            ).toInt()
    }

    private class DotView(context: Context) : View(context) {

        var dotColor: Int = FloatingDotSettings.DEFAULT_HOST_COLOR
            set(value) {
                field = value
                invalidate()
            }

        var onTap: (() -> Unit)? = null
        var onDrag: ((Float, Float) -> Unit)? = null
        var onDragEnd: (() -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var dragging = false

        init {
            isClickable = true
            contentDescription = AppLanguage.tr("AI 工具", "AI tools")
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    if (!dragging &&
                        (abs(event.rawX - downRawX) > touchSlop ||
                            abs(event.rawY - downRawY) > touchSlop)
                    ) {
                        dragging = true
                    }
                    if (dragging) onDrag?.invoke(dx, dy)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) {
                        onDragEnd?.invoke()
                    } else {
                        performClick()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) onDragEnd?.invoke()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            onTap?.invoke()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val side = min(width, height).toFloat() - 6f * density
            val corner = 13f * density
            val bounds = RectF(
                cx - side / 2f,
                cy - side / 2f,
                cx + side / 2f,
                cy + side / 2f
            )

            glowPaint.color = ColorUtils.setAlphaComponent(dotColor, 60)
            canvas.drawRoundRect(
                RectF(
                    bounds.left - 3f * density,
                    bounds.top - 3f * density,
                    bounds.right + 3f * density,
                    bounds.bottom + 3f * density
                ),
                corner + 3f * density,
                corner + 3f * density,
                glowPaint
            )

            fillPaint.shader = RadialGradient(
                cx - side * 0.24f,
                cy - side * 0.24f,
                side * 0.95f,
                intArrayOf(
                    ColorUtils.blendARGB(dotColor, Color.WHITE, 0.30f),
                    dotColor,
                    ColorUtils.blendARGB(dotColor, Color.BLACK, 0.18f)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(bounds, corner, corner, fillPaint)
            fillPaint.shader = null

            iconPaint.strokeWidth = side * 0.055f
            val offset = side * 0.17f
            val itemSide = side * 0.19f
            listOf(
                cx - offset to cy - offset,
                cx + offset to cy - offset,
                cx - offset to cy + offset,
                cx + offset to cy + offset
            ).forEach { (x, y) ->
                canvas.drawRoundRect(
                    RectF(
                        x - itemSide / 2f,
                        y - itemSide / 2f,
                        x + itemSide / 2f,
                        y + itemSide / 2f
                    ),
                    itemSide * 0.22f,
                    itemSide * 0.22f,
                    iconPaint
                )
            }
        }
    }
}
