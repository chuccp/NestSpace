package com.coke.nestspace.container

import com.coke.nestspace.container.identity.SystemServiceIdentity
import com.coke.nestspace.container.remote.ContainerRemoteServices
import android.app.Activity
import android.app.ActivityManager
import android.app.Presentation
import android.content.Intent
import android.hardware.display.DisplayManager
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.coke.nestspace.container.activity.TargetStateStore
import com.coke.nestspace.container.api.RuntimeHostHooks
import com.coke.nestspace.R
import java.lang.ref.WeakReference

private const val TAG = "StageAActivity"

    internal fun StageAActivity.giveTargetPrivateToken(target: Activity) {
        runCatching {
            Activity::class.java
                .getDeclaredField("mToken")
                .apply { isAccessible = true }
                .set(target, Binder())
        }.onFailure { Log.w(TAG, "private target token failed", it) }
    }

    internal fun StageAActivity.applyTargetActionBarInset(target: Activity) {
        val actionBar = target.actionBar ?: return
        if (!actionBar.isShowing) return
        val decor = target.window?.decorView ?: return
        val content = target.findViewById<View>(android.R.id.content) ?: return

        val decorLocation = IntArray(2)
        val contentLocation = IntArray(2)
        decor.getLocationOnScreen(decorLocation)
        content.getLocationOnScreen(contentLocation)
        val contentTop = (contentLocation[1] - decorLocation[1]).coerceAtLeast(0)

        // The action bar's layout height excludes the status bar even though
        // its container spans underneath it. Use the container's bottom so a
        // target such as SubprocessActivity does not hide its first line.
        var barBottom = 0
        val containerId = target.resources
            .getIdentifier("action_bar_container", "id", "android")
        val container = if (containerId != 0) {
            decor.findViewById<View>(containerId)
        } else {
            null
        }
        if (container != null && container.height > 0 && container.isShown) {
            val barLocation = IntArray(2)
            container.getLocationOnScreen(barLocation)
            barBottom = barLocation[1] + container.height - decorLocation[1]
        }
        if (barBottom <= 0) {
            var barHeight = actionBar.height
            if (barHeight <= 0) {
                val value = android.util.TypedValue()
                val found = target.theme.resolveAttribute(
                    android.R.attr.actionBarSize,
                    value,
                    true
                )
                if (found) {
                    barHeight = android.util.TypedValue.complexToDimensionPixelSize(
                        value.data,
                        target.resources.displayMetrics
                    )
                }
            }
            if (barHeight <= 0) return
            val statusBar = target.resources
                .getIdentifier("status_bar_height", "dimen", "android")
                .takeIf { it > 0 }
                ?.let { target.resources.getDimensionPixelSize(it) }
                ?: 0
            barBottom = statusBar + barHeight
        }

        val desiredTop = (barBottom - contentTop).coerceAtLeast(0)
        if (desiredTop <= 0) return
        if (content.paddingTop < desiredTop) {
            content.setPadding(
                content.paddingLeft,
                desiredTop,
                content.paddingRight,
                content.paddingBottom
            )
        }
    }

    internal fun StageAActivity.refreshEnvironmentLine(target: Activity?) {
        val frame = root ?: return
        if (mirrorMode || headless || target == null ||
            !ContainerIndicatorSettings.isEnabled(this)
        ) {
            environmentTopOverlay?.let { frame.removeView(it) }
            environmentTopOverlay = null
            return
        }
        val overlay = environmentTopOverlay?.takeIf { it.parent === frame } ?: View(this).also {
            it.isClickable = false
            it.isFocusable = false
            frame.addView(
                it,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    environmentGradientHeight(),
                    Gravity.TOP
                )
            )
            environmentTopOverlay = it
        }
        val topColor = environmentLineColor(target)
        overlay.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, Color.TRANSPARENT)
        )
        overlay.visibility = View.VISIBLE
    }

    internal fun StageAActivity.environmentGradientHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val bar = if (id > 0) {
            resources.getDimensionPixelSize(id)
        } else {
            (24 * resources.displayMetrics.density).toInt()
        }
        return bar.coerceAtLeast(1)
    }

    internal fun StageAActivity.environmentLineColor(target: Activity): Int {
        if (ContainerIndicatorSettings.colorMode(this) ==
            ContainerIndicatorSettings.ColorMode.FIXED
        ) {
            return ContainerIndicatorSettings.fixedColor(this)
        }
        val targetWindow = target.window ?: return 0x30000000
        val color = targetWindow.statusBarColor
        if (Color.alpha(color) != 0) return color
        val lightIcons = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appearance = targetWindow.insetsController?.systemBarsAppearance ?: 0
            (appearance and
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0
        } else {
            (targetWindow.decorView.systemUiVisibility and
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0
        }
        return if (lightIcons) 0x40FFFFFF else 0x30000000
    }

    internal fun StageAActivity.onCreateHost(savedInstanceState: Bundle?) {
        Log.d(
            TAG,
            "host onCreate instance=${hashCode()} headless=" +
                "${intent.getBooleanExtra(StageAActivity.EXTRA_HEADLESS, false)}"
        )
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        ContainerSessions.restoreHostFrameworkState(this, restoreTheme = true)
        headless = intent.getBooleanExtra(StageAActivity.EXTRA_HEADLESS, false)
        // Child hosts (target startActivity) may be built before the mirror id
        // is propagated through the session. Join the live mirror instead of
        // moving this host to the background with a stale, empty frame.
        val intentMirrorId = intent.getIntExtra(StageAActivity.EXTRA_MIRROR_DISPLAY_ID, -1)
        var requestedMirrorId = intentMirrorId
        if (headless) {
            // SurfaceView may recreate its Surface (and therefore the
            // VirtualDisplay) right after creation. The shared preference
            // always points at the newest one; the intent extra can be stale
            // by the time this host starts.
            val liveMirrorId = HeadlessMirrorWindow.storedDisplayId(this)
            if (liveMirrorId >= 0) requestedMirrorId = liveMirrorId
        }
        mirrorRequested = requestedMirrorId >= 0 || intentMirrorId >= 0
        if (mirrorRequested) {
            setTheme(R.style.Theme_Virtual_Container_Mirror)
        }
        // The host window hierarchy and its FragmentManager belong to the
        // container, not to the target Activity. Android's restore path
        // instantiates saved Fragments with the host ClassLoader before
        // onRestoreInstanceState, so target-only Fragment names would crash the
        // slot process. Target state is persisted separately in
        // TargetStateStore and restored after the Activity exists.
        callSuperOnCreate(null)
        ContainerSessions.restoreHostFrameworkState(this, restoreTheme = false)
        hostStateId = TargetStateStore.nextHostId()
        StageAActivity.active = this
        StageAActivity.hosts.removeAll { it.get() == null || it.get() === this }
        StageAActivity.hosts.add(WeakReference(this))
        ContainerProcessPool.markCurrentSlot(slotIndexFor(javaClass))
        ContainerProcessPool.markSlotProcess(this, slotIndexFor(javaClass))
        mirrorDisplayId = requestedMirrorId
        mirrorParentSlot = intent.getIntExtra(StageAActivity.EXTRA_PARENT_SLOT, -1)
        val displayManager = getSystemService(DisplayManager::class.java)
        if (mirrorDisplayId >= 0 && displayManager?.getDisplay(mirrorDisplayId) == null) {
            Log.w(TAG, "mirror display $mirrorDisplayId is gone")
            mirrorDisplayId = if (intentMirrorId >= 0 &&
                intentMirrorId != requestedMirrorId &&
                displayManager?.getDisplay(intentMirrorId) != null
            ) {
                intentMirrorId
            } else {
                -1
            }
        }
        val frame = HeadlessScaleFrame(this)
        frame.beforeMeasure = {
            TargetFragmentPagerHook.normalize(current?.window?.decorView)
        }
        root = frame
        if (mirrorDisplayId >= 0) {
            mirrorMode = setupMirrorPresentation(mirrorDisplayId)
        }
        if (mirrorMode) {
            HeadlessMirrorWindow.setActiveSlot(slotIndexFor(javaClass))
        }
        if (mirrorMode) {
            // The real target view lives on the full-resolution mirror
            // display. Keep this host window completely transparent so any
            // transient task transition can never replace the assistant page
            // with a full-screen container frame.
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            )
            window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
            frame.setBackgroundColor(Color.TRANSPARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setRecentsScreenshotEnabled(false)
            }
            setTaskDescription(
                ActivityManager.TaskDescription.Builder()
                    .setBackgroundColor(Color.TRANSPARENT)
                    .build()
            )
        } else if (headless) {
            RuntimeHostHooks.setOverlayHidden?.invoke(this, launchOverlayReason(), true)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            frame.realWidth = metrics.widthPixels
            frame.realHeight = metrics.heightPixels
            frame.scaleToRealResolution = true
            // A headless launch still has to become RESUMED before the target
            // can run. Keep the launch window invisible until the task is sent
            // to the back, otherwise the app flashes on screen for a frame or
            // two while the placeholder/target first draws.
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
            frame.setBackgroundColor(Color.rgb(247, 248, 250))
            frame.alpha = 0f
            // The host can be visible for a frame before it is sent to the
            // back. Draw a loading surface so that frame is not blank.
            val loading = LaunchLoadingView(this, launchLabel())
            frame.addView(
                loading,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            showLaunchPlaceholder(frame)
        }
        setContentView(frame)
        Log.d(TAG, "host content ready instance=${hashCode()}")
        syncAiInfoCaptureGuard()
        registerPredictiveBack()
        if (mirrorMode) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            mirrorPollTicks = 0
            mirrorNextDisplayRefreshAt = 0L
            frame.post(mirrorFinishPoller)
            // Move the host task behind the assistant immediately, before the
            // target starts and before any transition can show this window.
            frame.post {
                if (mirrorMode && !isFinishing && !isDestroyed) {
                    @Suppress("DEPRECATION")
                    moveTaskToBack(true)
                }
            }
        }
        scheduleTargetStart(savedInstanceState)
    }

    internal fun StageAActivity.scheduleTargetStart(savedState: Bundle?) {
        if (targetStartScheduled) return
        targetStartScheduled = true
        val frame = root ?: return
        val launchIntent = Intent(intent)
        Log.d(
            TAG,
            "schedule target start instance=${hashCode()} mirror=$mirrorMode"
        )
        ContainerRemoteServices.prepareForSlot(
            this,
            slotIndexFor(javaClass)
        ) {
            if (isFinishing || isDestroyed) return@prepareForSlot
            scheduleTargetStartAfterHelper(frame, launchIntent, savedState)
        }
    }

    internal fun StageAActivity.scheduleTargetStartAfterHelper(
        frame: FrameLayout,
        launchIntent: Intent,
        savedState: Bundle?
    ) {
        if (mirrorMode) {
            // The 1x1 mirror host is sent to the back and its own window is
            // never drawn, so onPreDraw would never fire. Child hosts created
            // by target navigation would stay empty and cover the live mirror
            // with a blank Presentation. Start the target on the next turn.
            frame.post {
                if (isFinishing || isDestroyed) return@post
                handleIntent(launchIntent, savedState)
                session?.headless = headless
            }
            return
        }
        frame.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    frame.viewTreeObserver.removeOnPreDrawListener(this)
                    Log.d(TAG, "host first draw instance=${hashCode()}")
                    frame.post {
                        if (isFinishing || isDestroyed) return@post
                        Log.d(TAG, "handle target after draw instance=${hashCode()}")
                        handleIntent(launchIntent, savedState)
                        session?.headless = headless
                    }
                    return true
                }
            }
        )
        frame.requestLayout()
    }

    internal fun StageAActivity.stopLaunchRendering(frame: FrameLayout) {
        for (index in 0 until frame.childCount) {
            (frame.getChildAt(index) as? LaunchLoadingView)
                ?.stopAndAwait()
        }
    }

    internal fun StageAActivity.showLaunchPlaceholder(frame: FrameLayout) {
        RuntimeHostHooks.setOverlayHidden?.invoke(this, launchOverlayReason(), true)
        // Do not let the loading window receive focus. Target startup can hold
        // the main thread for several seconds while opening many dex files; if
        // the window
        // is focusable Android reports an input-dispatch ANR before the target
        // ever gets a chance to draw. Focus is restored as soon as the target
        // view is attached in openActivity().
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        frame.setBackgroundColor(Color.WHITE)
        frame.addView(
            LaunchLoadingView(this, launchLabel()),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    internal fun StageAActivity.syncAiInfoCaptureGuard() {
        SystemServiceIdentity.setAiInfoCaptureActive(headless || mirrorMode)
    }

    internal fun StageAActivity.restoreLoadingWindowFocus() {
        window.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        window.decorView.requestFocus()
    }
