package com.coke.nestspace.features.assistant

import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.MainActivity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.coke.nestspace.container.identity.HostIdentity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

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

    internal const val TAG = "FloatingDot"
    const val ACTION_MODE = "com.coke.nestspace.FLOATING_DOT_MODE"
    const val EXTRA_CONTAINER = "container"

    internal var windowManager: WindowManager? = null
    internal var dotView: DotView? = null
    internal var params: WindowManager.LayoutParams? = null
    internal var toolsView: ToolsPanelView? = null
    internal var toolsTaskJob: Job? = null
    internal var readerView: ReaderPanelView? = null
    internal var readerTaskJob: Job? = null
    internal var readerSpeechJob: Job? = null
    internal var readerTaskId: String? = null
    internal var spokenReaderTaskId: String? = null
    internal var containerMode = false
    internal var dockedEdge = DockEdge.RIGHT
    internal var edgeRevealed = false
    internal var autoDockRunnable: Runnable? = null
    /** Reasons currently showing a container-owned host surface (desktop, spaces). */
    private val containerSurfaces =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal var suppressed = false
    internal var assistantPageVisible = false
    private val overlayReasons = mutableSetOf<String>()
    internal var appContext: Context? = null
    internal val handler = Handler(Looper.getMainLooper())
    internal val toolsScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var pendingHostMode: Runnable? = null
    internal var modeReceiver: BroadcastReceiver? = null
    internal var configurationCallbacks: ComponentCallbacks? = null
    internal var lastWindowHeight = 0

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

    internal fun isHidden(): Boolean =
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

    internal fun unregisterModeReceiver(app: Context) {
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

    internal fun unregisterConfigurationCallbacks(app: Context) {
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

    internal fun openAssistant(app: Context) {
        val intent = Intent(app, AssistantVoiceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "open assistant failed", it) }
    }

    internal fun openTasks(app: Context) {
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

    internal fun openScreenReader(app: Context) {
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

    internal enum class DockEdge { LEFT, RIGHT }

    private const val HOST_MODE_DELAY_MS = 350L
    internal const val EDGE_AUTO_DOCK_DELAY_MS = 10_000L

    /** Manifest receiver: also starts the main process if it was killed. */
    class ModeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onModeBroadcast(context.applicationContext, intent)
        }
    }



}
