package com.coke.nestspace.container

import android.annotation.SuppressLint


import android.app.Activity
import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.app.Presentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import com.coke.nestspace.container.activity.TargetLifecycle
import com.coke.nestspace.container.activity.TargetPlaybackUiBridge
import com.coke.nestspace.container.activity.TargetStateStore
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Host Activity for one target Activity. Each in-app navigation creates another
 * StageAActivity on top; Android's own back stack then provides the target
 * app's back behavior. All hosts for one app launch share a ContainerSession
 * (Application, ClassLoader, Resources) via ContainerSessions.
 *
 * Keeping a real host token per target Activity is deliberate: when the target
 * calls finish(), only its host closes, and the previous host resumes. This is
 * what makes splash -> home and startActivityForResult behave normally.
 */
open class StageAActivity : Activity() {

    companion object {
        const val ACTION_FOREGROUND = "com.coke.nestspace.container.FOREGROUND"
        const val EXTRA_TOKEN = "container_token"
        const val EXTRA_SESSION_ID = "container_session_id"
        const val EXTRA_LAUNCH_PACKAGE = "container_launch_package"
        const val EXTRA_APP_LABEL = "container_app_label"
        const val EXTRA_LAUNCH_INSTANCE = "container_launch_instance"
        const val EXTRA_ACTIVITY_CLASS = "container_activity_class"
        const val EXTRA_TARGET_INTENT = "container_target_intent"
        const val EXTRA_HEADLESS = "container_headless"
        const val EXTRA_CONTAINER_ENTRY = "container_entry"
        const val EXTRA_MIRROR_DISPLAY_ID = "container_mirror_display_id"
        const val EXTRA_PARENT_SLOT = "container_parent_slot"
        const val EXTRA_TARGET_PROCESS = "container_target_process"
        internal const val TAG = "StageAActivity"
        internal const val KEY_HOST_STATE_ID = "nestspace.host_state_id"
        internal const val KEY_CONTAINER_ENTRY = "nestspace.container_entry"
        internal const val MIRROR_FAST_POLL_MS = 50L
        internal const val MIRROR_IDLE_POLL_MS = 250L
        internal const val MIRROR_FAST_POLLS = 40
        internal const val MIRROR_DISPLAY_REFRESH_MS = 1_000L

        @Volatile
        internal var active: StageAActivity? = null

        internal val hosts = CopyOnWriteArrayList<WeakReference<StageAActivity>>()

        /**
         * Last frame of the previous target page. New mirror Presentations
         * show it behind the incoming target while that page is still
         * transparent/loading, so an Activity switch cannot expose the black
         * virtual-display background.
         */
        @Volatile
        internal var mirrorSnapshot: Bitmap? = null

        /**
         * The target Activity currently hosted, for in-process control tools.
         * Null when no container Activity has been shown in this process yet.
         */
        fun activeTargetActivity(): Activity? = active?.current

        fun activeTargetPackage(): String? = active?.currentPackageName

        fun activeHost(): StageAActivity? = active

        fun finishIfShowing(packageName: String) {
            val activity = active ?: return
            activity.runOnUiThread {
                if (activity.currentPackageName == packageName) activity.returnToDesktop()
            }
        }
    }

    internal var root: FrameLayout? = null
    internal var environmentTopOverlay: View? = null
    internal var session: ContainerSession? = null
    internal var sessionRetained = false
    internal var current: Activity? = null
    internal var currentPackageName: String? = null
    internal var pendingTargetState: Bundle? = null
    internal var targetStateKey: String? = null
    internal var hostStateId = 0
    internal var targetStarted = false
    internal var targetPostCreated = false
    internal var targetRestored = false
    internal var targetAttached = false
    internal var targetResumed = false
    internal var targetStopped = false
    internal var returningToDesktop = false
    internal var hostResumed = false
    internal var headless = false
    internal var headlessBackDone = false
    internal var pipActive = false
    internal var mirrorDisplayId = -1
    internal var mirrorRequested = false
    internal var mirrorMode = false
    internal var mirrorBackDone = false
    internal var mirrorPresentation: Presentation? = null
    internal var mirrorRoot: FrameLayout? = null
    internal var mirrorParentSlot = -1
    internal var targetStartScheduled = false
    internal var predictiveBackCallback: Any? = null
    internal var containerEntry = false
    internal var backCallbackRunning = false
    internal var backLaunchBlocked = false
    internal var playbackUiBridge: TargetPlaybackUiBridge? = null
    internal var targetRequestParameterHook: AutoCloseable? = null
    internal var navigationHandoffState = NavigationHandoffState.NONE
    internal var navigationHandoffOpenedInternalChild = false
    internal var creatingTargetActivity = false
    internal val stagedNavigationLaunches = ArrayDeque<StagedNavigationLaunch>()

    internal data class MirrorBackEntry(
        val target: Activity,
        val decor: View,
        val requestCode: Int
    )

    internal data class StagedNavigationLaunch(
        val className: String,
        val launch: () -> Boolean
    )

    internal enum class NavigationHandoffState {
        NONE,
        WAITING_FOR_CHILD,
        CHILD_LAUNCHED
    }

    /**
     * Mirror mode hosts target Activities itself instead of creating a real
     * StageAActivity for each one. Swapping decor views inside the existing
     * Presentation avoids a WindowManager task transition (and its black
     * frame) for every in-app navigation.
     */
    internal val mirrorBackStack = ArrayDeque<MirrorBackEntry>()

    internal var mirrorPollTicks = 0
    internal var mirrorNextDisplayRefreshAt = 0L

    internal val mirrorFinishPoller = object : Runnable {
        override fun run() {
            if (!mirrorMode || isFinishing || isDestroyed) return
            val target = current
            if (target != null && target.isFinishing && !isFinishing) {
                if (mirrorBackStack.isNotEmpty()) {
                    popMirrorTarget()
                } else {
                    finish()
                }
            }
            // The assistant closes the mirror window when a run ends and opens
            // a fresh one when a later run touches the same app. Follow the
            // new VirtualDisplay id even while this host is in the background,
            // otherwise the target keeps drawing into a dead display.
            val now = SystemClock.uptimeMillis()
            if (now >= mirrorNextDisplayRefreshAt) {
                refreshMirrorDisplay()
                mirrorNextDisplayRefreshAt = now + MIRROR_DISPLAY_REFRESH_MS
            }
            val delay = if (mirrorPollTicks++ < MIRROR_FAST_POLLS) {
                MIRROR_FAST_POLL_MS
            } else {
                MIRROR_IDLE_POLL_MS
            }
            root?.postDelayed(this, delay)
        }
    }

    /** The target Activity hosted by this host, for permission routing. */
    fun currentTargetActivity(): Activity? = current

    fun blockActivityLaunchFromBack(): Boolean {
        if (!backCallbackRunning) return false
        backLaunchBlocked = true
        return true
    }

    /**
     * Leave headless mode and move this host's existing task to the front.
     * Called inside the container process so no new Activity/Instance is
     * created and the target keeps its current page and session.
     */
    fun requestForeground(): Boolean {
        headless = false
        headlessBackDone = false
        session?.headless = false
        root?.alpha = 1f
        return runCatching {
            val manager = getSystemService(ActivityManager::class.java) ?: return false
            manager.moveTaskToFront(taskId, 0)
            true
        }.getOrElse { false }
    }

    /**
     * Capture the current page before a child target Activity replaces it.
     * The next mirror host paints this bitmap under its own target view, so
     * transparent loading frames do not expose the virtual display's black
     * background.
     */

    /**
     * Stacked targets keep the real host token through onCreate/Start/Resume
     * so apps that touch WindowManager during startup still work. Once they
     * are settled, give them a private token: finish() then cannot close the
     * shared host, and the finish poller pops the internal target stack.
     */

    /**
     * The synthetic target window does not receive the real decor insets, so
     * an Activity with a framework ActionBar can start its content at y=0
     * underneath the title bar. Inset the content view below the action bar
     * (idempotently) so the first rows are not hidden.
     */

    /**
     * A 1px line at the very top of a visible container app. Its color follows
     * the target's status bar color so the app looks native while users can
     * still tell the virtual environment apart. Opt out in Appearance settings.
     */


    @Suppress("DEPRECATION")


    fun isMirrorMode(): Boolean = mirrorMode

    fun isHeadlessMode(): Boolean = headless

    fun isCreatingTargetActivity(): Boolean = creatingTargetActivity

    /**
     * A target may call finish() immediately after startActivity(), before an
     * asynchronous slot allocation has delivered the child host launch. Keep
     * this host alive until that launch has been submitted; otherwise the
     * target's finish closes the shared host token and the task returns to the
     * desktop between both pages.
     */
    fun beginTargetNavigationHandoff(
        parent: Activity?,
        className: String
    ): Boolean {
        if (navigationHandoffState != NavigationHandoffState.NONE) return true
        val activeParent = parent ?: current ?: if (creatingTargetActivity) this else return false
        if (isFinishing || isDestroyed) return false
        if (activeParent !== this) giveTargetPrivateToken(activeParent)
        navigationHandoffState = NavigationHandoffState.WAITING_FOR_CHILD
        Log.d(TAG, "navigation handoff waiting child=$className")
        return true
    }

    fun completeTargetNavigationHandoff(className: String) {
        if (navigationHandoffState == NavigationHandoffState.NONE) return
        navigationHandoffState = if (navigationHandoffOpenedInternalChild) {
            NavigationHandoffState.NONE
        } else {
            NavigationHandoffState.CHILD_LAUNCHED
        }
        navigationHandoffOpenedInternalChild = false
        Log.d(TAG, "navigation handoff submitted child=$className")
    }

    fun cancelTargetNavigationHandoff(className: String) {
        if (navigationHandoffState == NavigationHandoffState.NONE) return
        navigationHandoffState = NavigationHandoffState.NONE
        navigationHandoffOpenedInternalChild = false
        Log.w(TAG, "navigation handoff failed child=$className")
        returnToDesktopIfRootTargetFinished()
    }

    /**
     * Activity.startActivity is valid from onCreate, but here the synthetic
     * target is still inside the host's Activity launch callback. Starting a
     * second real host synchronously can leave its ActivityRecord RESUMED
     * before ActivityThread has a chance to run its client onCreate. Queue it
     * until the current page has fully settled and then run from the explicit
     * post-create checkpoint.
     */
    fun stageTargetNavigationLaunch(
        className: String,
        launch: () -> Boolean
    ): Boolean {
        if (isFinishing || isDestroyed) {
            cancelTargetNavigationHandoff(className)
            return false
        }
        stagedNavigationLaunches.addLast(
            StagedNavigationLaunch(className, launch)
        )
        Log.d(TAG, "staged navigation child=$className")
        return true
    }

    internal fun runStagedNavigationLaunches() {
        while (stagedNavigationLaunches.isNotEmpty()) {
            val next = stagedNavigationLaunches.removeFirst()
            if (isFinishing || isDestroyed) {
                cancelTargetNavigationHandoff(next.className)
                continue
            }
            Log.d(TAG, "running staged navigation child=${next.className}")
            if (next.launch()) {
                completeTargetNavigationHandoff(next.className)
            } else {
                cancelTargetNavigationHandoff(next.className)
            }
        }
    }

    internal fun callSuperOnCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    internal fun callSuperOnNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
    }

    internal fun callSuperOnResume() {
        super.onResume()
    }

    internal fun callSuperOnPause() {
        super.onPause()
    }

    internal fun callSuperOnStop() {
        super.onStop()
    }

    internal fun callSuperOnDestroy() {
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingSuperCall")
    override fun onCreate(savedInstanceState: Bundle?) {
        ContainerSessions.withHostProcessIdentity {
            onCreateHost(savedInstanceState)
        }
    }

    internal fun slotIndexFor(activityClass: Class<*>): Int = when (activityClass) {
        StageASlot1Activity::class.java -> 0
        StageAIsolatedSlot1Activity::class.java -> 0
        StageASlot2Activity::class.java -> 1
        StageAIsolatedSlot2Activity::class.java -> 1
        StageASlot3Activity::class.java -> 2
        StageAIsolatedSlot3Activity::class.java -> 2
        StageASlot4Activity::class.java -> 3
        StageAIsolatedSlot4Activity::class.java -> 3
        StageASlot5Activity::class.java -> 4
        StageAIsolatedSlot5Activity::class.java -> 4
        StageASlot6Activity::class.java -> 5
        StageAIsolatedSlot6Activity::class.java -> 5
        StageASlot7Activity::class.java -> 6
        StageAIsolatedSlot7Activity::class.java -> 6
        StageASlot8Activity::class.java -> 7
        StageAIsolatedSlot8Activity::class.java -> 7
        StageASlot9Activity::class.java -> 8
        StageAIsolatedSlot9Activity::class.java -> 8
        StageASlot10Activity::class.java -> 9
        StageAIsolatedSlot10Activity::class.java -> 9
        StageASlot11Activity::class.java -> 10
        StageAIsolatedSlot11Activity::class.java -> 10
        StageASlot12Activity::class.java -> 11
        StageAIsolatedSlot12Activity::class.java -> 11
        StageASlot13Activity::class.java -> 12
        StageAIsolatedSlot13Activity::class.java -> 12
        StageASlot14Activity::class.java -> 13
        StageAIsolatedSlot14Activity::class.java -> 13
        StageASlot15Activity::class.java -> 14
        StageAIsolatedSlot15Activity::class.java -> 14
        StageASlot16Activity::class.java -> 15
        StageAIsolatedSlot16Activity::class.java -> 15
        else -> -1
    }


    /**
     * The mirror SurfaceView can replace its Surface while the panel settles
     * (or when the assistant window relayouts), which creates a new
     * VirtualDisplay. Follow the newest display id and move the target view
     * onto it; otherwise the host would keep drawing into a dead display and
     * the small window would stay blank.
     */


    internal fun launchLabel(): String? =
        intent.getStringExtra(EXTRA_APP_LABEL)?.trim()?.takeIf { it.isNotEmpty() }

    internal fun launchOverlayReason(): String = "container-launch-$hostStateId"



    override fun onNewIntent(intent: Intent?) {
            handleHostNewIntent(intent)
        }

    /**
     * Move the current target decor from the mirror Presentation back into the
     * normal host window. Without this handoff, the host task becomes visible
     * but the target keeps drawing into the dismissed virtual display, leaving
     * a black full-screen Activity.
     */

    /**
     * Delivers an in-package singleTop intent to the current target Activity
     * without creating another host. Returns false when another target screen
     * is on top, in which case the caller launches a new host normally.
     */
    fun deliverTargetIntent(className: String, targetIntent: Intent): Boolean {
        val activeSession = session ?: return false
        val info = activeSession.resolveActivity(className) ?: return false
        val concrete = info.targetActivity?.takeIf { it.isNotBlank() } ?: info.name
        val singleTop = info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP ||
            targetIntent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0
        val singleTask =
            info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK ||
                info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE
        val clearTop = targetIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0
        val liveHosts = hosts.mapNotNull { it.get() }
            .filter { !it.isFinishing && !it.isDestroyed }
        val matchIndex = if (singleTop && !singleTask && !clearTop) {
            liveHosts.indexOfLast { it.matchesTarget(concrete) }
                .takeIf { it == liveHosts.lastIndex }
        } else {
            liveHosts.indexOfLast { it.matchesTarget(concrete) }.takeIf { it >= 0 }
        } ?: return false
        val targetHost = liveHosts[matchIndex]
        val isolatedTarget = ContainerProcessPool.isIsolatedActivity(targetHost.javaClass)
        if ((singleTask || clearTop) && !isolatedTarget) {
            liveHosts.drop(matchIndex + 1).forEach { above ->
                Log.d(TAG, "clear above ${above.current?.javaClass?.name}")
                above.finish()
            }
        }
        if (clearTop && !singleTop && !singleTask) {
            targetHost.finish()
            return false
        }
        if (targetHost !== active || isolatedTarget) {
            runCatching {
                getSystemService(ActivityManager::class.java)
                    .moveTaskToFront(targetHost.taskId, 0)
            }.onFailure {
                Log.w(TAG, "move singleInstance task front failed", it)
            }
        }
        return targetHost.deliverNewIntentToCurrent(info, targetIntent)
    }

    internal fun matchesTarget(concreteClassName: String): Boolean {
        val target = current ?: return false
        if (target.isFinishing || session == null) return false
        return target.javaClass.name == concreteClassName
    }

    internal fun deliverNewIntentToCurrent(
        info: android.content.pm.ActivityInfo,
        targetIntent: Intent
    ): Boolean {
        val target = current ?: return false
        val activeSession = session ?: return false
        if (target.isFinishing || isFinishing || isDestroyed) return false
        val delivered = Intent(targetIntent).setClassName(
            activeSession.spec.packageName,
            info.name
        )
        delivered.setExtrasClassLoader(activeSession.loader)
        return runCatching {
            withTargetLoader {
                target.setIntent(delivered)
                activeSession.instrumentation.callActivityOnNewIntent(target, delivered)
            }
            if (headless) root?.alpha = 0f else root?.alpha = 1f
            refreshEnvironmentLine(target)
            Log.d(TAG, "target singleTop onNewIntent ${info.name}")
            true
        }.onFailure {
            Log.w(TAG, "target singleTop delivery failed: ${info.name}", it)
        }.getOrDefault(false)
    }

    override fun onRestart() {
        super.onRestart()
        val target = current ?: return
        if (targetStopped) {
            withTargetLoader {
                if (!TargetLifecycle.perform(target, "performRestart", arrayOf<Any?>(false))) {
                    runCatching { session?.instrumentation?.callActivityOnRestart(target) }
                        .onFailure { Log.w(TAG, "target onRestart failed", it) }
                }
            }
            targetStopped = false
        }
    }

    override fun onStart() {
        super.onStart()
        val target = current ?: return
        if (!targetStarted) {
            withTargetLoader {
                if (!TargetLifecycle.perform(
                        target,
                        "performStart",
                        arrayOf<Any?>("StageAActivity.onStart"),
                        emptyArray<Any?>()
                    )
                ) {
                    runCatching { session?.instrumentation?.callActivityOnStart(target) }
                        .onFailure { Log.w(TAG, "target onStart failed", it) }
                }
            }
            targetStarted = true
            dispatchTargetApplicationLifecycle("dispatchActivityStarted", target)
        }
        callTargetRestoreState(target)
        callTargetPostCreate(target)
    }

    @SuppressLint("MissingSuperCall")
    override fun onResume() {
        handleHostResume()
    }

    /**
     * A headless launch never shows a system window of its own. When a mirror
     * display was requested the target is rendered there; otherwise the host
     * task is moved to the background and the assistant keeps controlling the
     * target through ui_* tools. A PiP fallback used to live here but left a
     * second small window behind whenever the mirror later became available;
     * only that automatic preview path is gone. The slot activities still
     * declare PiP support for apps that request it themselves.
     */
    @Suppress("DEPRECATION")
    internal fun scheduleHeadlessBack() {
        if (pipActive) return
        if (!headless || headlessBackDone) return
        headlessBackDone = true
        Log.d(TAG, "headless launch, moving task to back")
        root?.postDelayed({
            if (!headless || isFinishing || isDestroyed) return@postDelayed
            overridePendingTransition(0, 0)
            val moved = moveTaskToBack(true)
            // Keep alpha at 0 until onStop. Restoring it here can still paint
            // one white frame while the task transition is in flight.
            if (!moved) root?.alpha = 1f
            Log.d(TAG, "moveTaskToBack(headless) = $moved")
        }, 300)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val target = current ?: return
        withTargetLoader {
            runCatching { target.onWindowFocusChanged(hasFocus) }
                .onFailure { Log.w(TAG, "target onWindowFocusChanged failed", it) }
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onPause() {
        handleHostPause()
    }

    @SuppressLint("MissingSuperCall")
    override fun onStop() {
        handleHostStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val target = current
        if (target != null) {
            val targetState = Bundle()
            withTargetLoader {
                runCatching {
                    session?.instrumentation?.callActivityOnSaveInstanceState(target, targetState)
                }.onFailure { Log.w(TAG, "target onSaveInstanceState failed", it) }
            }
            dispatchTargetApplicationLifecycle(
                "dispatchActivitySaveInstanceState",
                target,
                targetState
            )
            targetStateKey?.let { TargetStateStore.put(it, targetState) }
        }
        outState.putInt(KEY_HOST_STATE_ID, hostStateId)
        outState.putBoolean(KEY_CONTAINER_ENTRY, containerEntry)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        // The host window hierarchy is the target's view tree. Restoring it
        // unmarshals target-only Parcelables (for example AndroidX
        // DrawerLayout.SavedState) with the host class loader, which throws
        // BadParcelableException and kills the slot process. Target lifecycle
        // state is restored separately from TargetStateStore below, so a
        // failed host hierarchy restore only loses transient view state.
        runCatching { super.onRestoreInstanceState(Bundle()) }
            .onFailure { Log.w(TAG, "host state restore failed", it) }
        current?.let { callTargetRestoreState(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val target = current
        withTargetLoader {
            target?.let {
                runCatching {
                    it.resources.updateConfiguration(newConfig, it.resources.displayMetrics)
                    it.onConfigurationChanged(newConfig)
                }.onFailure { Log.w(TAG, "target onConfigurationChanged failed", it) }
            }
            runCatching { session?.application?.onConfigurationChanged(newConfig) }
                .onFailure { Log.w(TAG, "application onConfigurationChanged failed", it) }
        }
    }

    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: Configuration
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        val target = current ?: return
        withTargetLoader {
            runCatching { target.onMultiWindowModeChanged(isInMultiWindowMode, newConfig) }
                .onFailure { Log.w(TAG, "target onMultiWindowModeChanged failed", it) }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            pipActive = true
            root?.alpha = 1f
            // Re-measure the full-resolution target at the new PiP window
            // size; HeadlessScaleFrame applies the fitting scale.
            root?.requestLayout()
        }
        val target = current ?: return
        withTargetLoader {
            runCatching {
                target.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
            }.onFailure { Log.w(TAG, "target onPictureInPictureModeChanged failed", it) }
        }
    }

    override fun enterPictureInPictureMode() {
        if (headless || mirrorMode) {
            Log.d(TAG, "blocked PiP while AI assistant is reading the container")
            return
        }
        super.enterPictureInPictureMode()
    }

    override fun enterPictureInPictureMode(params: PictureInPictureParams): Boolean {
        if (headless || mirrorMode) {
            Log.d(TAG, "blocked parameterized PiP while AI assistant is reading the container")
            return false
        }
        return super.enterPictureInPictureMode(params)
    }

    override fun onTopResumedActivityChanged(isTopResumed: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        super.onTopResumedActivityChanged(isTopResumed)
        val target = current ?: return
        withTargetLoader {
            runCatching { target.onTopResumedActivityChanged(isTopResumed) }
                .onFailure { Log.w(TAG, "target onTopResumedActivityChanged failed", it) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        current?.let { attachTarget(it) }
    }

    override fun onDetachedFromWindow() {
        current?.let { detachTarget(it) }
        super.onDetachedFromWindow()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val target = current ?: return
        withTargetLoader {
            runCatching { session?.instrumentation?.callActivityOnUserLeaving(target) }
                .onFailure { Log.w(TAG, "target onUserLeaveHint failed", it) }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        val target = current ?: return
        withTargetLoader {
            runCatching { target.onUserInteraction() }
                .onFailure { Log.w(TAG, "target onUserInteraction failed", it) }
        }
    }

    override fun onActivityReenter(resultCode: Int, data: Intent?) {
        super.onActivityReenter(resultCode, data)
        val target = current ?: return
        withTargetLoader {
            runCatching { target.onActivityReenter(resultCode, data) }
                .onFailure { Log.w(TAG, "target onActivityReenter failed", it) }
        }
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        val target = current ?: return
        withTargetLoader {
            runCatching { target.onEnterAnimationComplete() }
                .onFailure { Log.w(TAG, "target onEnterAnimationComplete failed", it) }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        val target = current
        withTargetLoader {
            target?.let {
                runCatching { it.onLowMemory() }
                    .onFailure { Log.w(TAG, "target onLowMemory failed", it) }
            }
            runCatching { session?.application?.onLowMemory() }
                .onFailure { Log.w(TAG, "application onLowMemory failed", it) }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val target = current
        withTargetLoader {
            target?.let {
                runCatching { it.onTrimMemory(level) }
                    .onFailure { Log.w(TAG, "target onTrimMemory failed", it) }
            }
            runCatching { session?.application?.onTrimMemory(level) }
                .onFailure { Log.w(TAG, "application onTrimMemory failed", it) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val target = current ?: return
        // Result Parcelables were created by the target's class loader.
        // Without this, Bundle reads them through the host loader and throws
        // ClassNotFoundException while the target handles the result.
        session?.loader?.let { data?.setExtrasClassLoader(it) }
        withTargetLoader {
            runCatching {
                val method = Activity::class.java.getDeclaredMethod(
                    "onActivityResult",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Intent::class.java
                )
                method.isAccessible = true
                method.invoke(target, requestCode, resultCode, data)
            }.onFailure { Log.w(TAG, "forward onActivityResult failed", it) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Results of a virtualized target request are delivered to the target
        // Activity by ContainerPermissions; this host callback is only the
        // transport for the real system permission dialog.
        if (session?.context?.permissions?.onHostPermissionResult(
                requestCode,
                permissions,
                grantResults
            ) == true
        ) {
            return
        }
        val target = current ?: return
        Log.d(
            TAG,
            "forward permissions result code=$requestCode " +
                permissions.zip(grantResults.toTypedArray()).joinToString()
        )
        withTargetLoader {
            runCatching {
                target.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }.onFailure { Log.w(TAG, "forward permissions result failed", it) }
        }
    }

    @Suppress("DEPRECATION")
    internal fun callSuperOnBackPressed() {
        super.onBackPressed()
    }

    override fun onBackPressed() {
        handleBack()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                handleBack()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Slot activities opt out of OnBackInvoked in the manifest, so Android
     * delivers the back key to this host's onBackPressed even when the target
     * registers its own overlay callback. The
     * target's own OnBackPressedDispatcher callbacks still run through
     * [handleBack]; if a base Activity/ComponentActivity implementation
     * handles back but fails to finish the host, finish it here.
     */


    internal fun hasEnabledBackCallbacks(target: Activity): Boolean = runCatching {
        val dispatcher = target.javaClass
            .getMethod("getOnBackPressedDispatcher")
            .invoke(target) ?: return@runCatching false
        dispatcher.javaClass
            .getMethod("hasEnabledCallbacks")
            .invoke(dispatcher) as? Boolean ?: false
    }.getOrDefault(false)



    @SuppressLint("MissingSuperCall")
    override fun onDestroy() {
        handleHostDestroy()
    }



    /**
     * Android launches the declared Activity alias and lets the target
     * Activity inspect the original component. Apps use that component name
     * to enter their splash/privacy flow, so a normal launcher intent must not
     * be rewritten to the alias target too early.
     */

    /**
     * AndroidStartupCoordinator completes the target Application, providers
     * and compatibility initialization before the session is exposed here.
     * Dispatch the launcher on the next main-loop turn without a fixed settle
     * delay; asynchronous first-request data remains the request hook's job.
     */


    @Suppress("DEPRECATION")
    internal fun targetIntentFrom(intent: Intent): Intent? =
        intent.getParcelableExtra(EXTRA_TARGET_INTENT)














}
