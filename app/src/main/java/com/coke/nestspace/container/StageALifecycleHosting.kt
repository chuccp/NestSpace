package com.coke.nestspace.container

import com.coke.nestspace.container.identity.SystemServiceIdentity
import android.app.Activity
import android.content.Intent
import android.util.Log
import com.coke.nestspace.container.activity.TargetLifecycle
import com.coke.nestspace.container.activity.TargetVisibilityPolicy
import com.coke.nestspace.container.activity.TargetStateStore
import com.coke.nestspace.container.api.RuntimeHostHooks

private const val TAG = "StageAActivity"

    internal fun StageAActivity.handleHostNewIntent(intent: Intent?) {
        callSuperOnNewIntent(intent)
        if (intent == null) return
        session?.loader?.let { intent.setExtrasClassLoader(it) }

        if (intent.action == StageAActivity.ACTION_FOREGROUND) {
            // Keep the original launch intent as the Activity intent; this
            // foreground hint has no session/activity extras and would break
            // recreation after an unhandled configuration change.
            headless = false
            headlessBackDone = false
            session?.headless = false
            root?.alpha = 1f
            promoteMirrorToHost()
            syncAiInfoCaptureGuard()
            return
        }
        setIntent(intent)

        val target = current
        val incomingSession = intent.getStringExtra(StageAActivity.EXTRA_SESSION_ID)
            ?: intent.getStringExtra(StageAActivity.EXTRA_LAUNCH_PACKAGE)?.let { pkg ->
                intent.getStringExtra(StageAActivity.EXTRA_LAUNCH_INSTANCE)?.let { "$pkg@$it" }
            }
        val sameSession = incomingSession == null || incomingSession == session?.id
        val incomingClass = intent.getStringExtra(StageAActivity.EXTRA_ACTIVITY_CLASS)
        if (target != null && sameSession && !target.isFinishing &&
            incomingClass != null && target.javaClass.name == incomingClass
        ) {
            headless = intent.getBooleanExtra(StageAActivity.EXTRA_HEADLESS, headless)
            session?.headless = headless
            syncAiInfoCaptureGuard()
            val targetIntent = targetIntentFrom(intent) ?: Intent(intent)
            withTargetLoader {
                runCatching {
                    target.setIntent(targetIntent)
                    session?.instrumentation?.callActivityOnNewIntent(target, targetIntent)
                }.onFailure { Log.w(TAG, "target onNewIntent failed", it) }
            }
            if (headless) {
                headlessBackDone = false
                root?.alpha = 0f
                scheduleHeadlessBack()
            } else {
                root?.alpha = 1f
                refreshEnvironmentLine(target)
            }
            return
        }

        headless = intent.getBooleanExtra(StageAActivity.EXTRA_HEADLESS, false)
        syncAiInfoCaptureGuard()
        closeCurrent()
        releaseSession()
        handleIntent(intent, null)
        session?.headless = headless
        if (headless) {
            headlessBackDone = false
            root?.alpha = 0f
            scheduleHeadlessBack()
        } else {
            root?.alpha = 1f
        }
    }

    internal fun StageAActivity.handleHostResume() {
        callSuperOnResume()
        ContainerProcessPool.markActiveSlot(slotIndexFor(javaClass))
        Log.d(TAG, "host onResume target=${current?.javaClass?.name} headless=$headless")
        StageAActivity.active = this
        hostResumed = true
        session?.navigator = this
        syncAiInfoCaptureGuard()
        RuntimeHostHooks.publishContainerMode?.invoke(this, true)
        scheduleHeadlessBack()
        if (mirrorMode && !mirrorBackDone) {
            mirrorBackDone = true
            // The mirror has its own window on the virtual display. Keep the
            // host task out of the way so the assistant remains visible.
            root?.postDelayed({
                if (mirrorMode && !isFinishing && !isDestroyed) {
                    @Suppress("DEPRECATION")
                    moveTaskToBack(true)
                }
            }, 50)
        }
        if (mirrorRequested) {
            root?.postDelayed({ refreshMirrorDisplay() }, 900)
            root?.postDelayed({ refreshMirrorDisplay() }, 2_500)
        }
        val target = current ?: return
        session?.context?.hostWindowManager = windowManager
        if (!targetResumed) {
            withTargetLoader {
                val performed = TargetLifecycle.perform(
                    target,
                    "performResume",
                    arrayOf<Any?>(false, "StageAActivity.onResume"),
                    emptyArray<Any?>()
                )
                if (!performed) {
                    runCatching { session?.instrumentation?.callActivityOnResume(target) }
                        .onFailure { Log.w(TAG, "target onResume failed", it) }
                    runCatching { TargetLifecycle.postResume(target) }
                        .onFailure { Log.w(TAG, "target onPostResume failed", it) }
                }
            }
            targetResumed = true
            dispatchTargetApplicationLifecycle("dispatchActivityResumed", target)
            attachTarget(target)
        }
        refreshEnvironmentLine(target)
    }

    internal fun StageAActivity.handleHostPause() {
        if (!headless && !mirrorMode) {
            SystemServiceIdentity.setAiInfoCaptureActive(false)
        }
        if (!returningToDesktop && current?.isFinishing != true) {
            SystemServiceIdentity.clearRootFinishInterceptor()
        }
        returnToDesktopIfRootTargetFinished()
        // In mirror mode the host Activity is paused by moveTaskToBack but the
        // target is still the app the assistant is controlling. Keep its slot
        // marked StageAActivity.active so the process pool does not evict and kill it while
        // it is being watched in the mirror window. The same applies while the
        // system keeps a PiP window alive.
        if (!TargetVisibilityPolicy.shouldKeepTargetActiveWhileHostHidden(
                pipActive,
                mirrorMode,
                headless
            )
        ) {
            ContainerProcessPool.clearActiveSlot(slotIndexFor(javaClass))
        }
        Log.d(TAG, "host onPause target=${current?.javaClass?.name}")
        RuntimeHostHooks.publishContainerMode?.invoke(this, false)
        hostResumed = false
        if (TargetVisibilityPolicy.shouldKeepTargetActiveWhileHostHidden(
                pipActive,
                mirrorMode,
                headless
            )
        ) {
            // Keep the target resumed while it is visible in PiP or the
            // mirror. The host window is paused by the system, but pausing the
            // synthetic target as well stops WebView/Compose drawing and
            // leaves the small window blank indefinitely.
            callSuperOnPause()
            return
        }
        if (targetResumed) {
            current?.let {
                withTargetLoader {
                    runCatching { session?.instrumentation?.callActivityOnPause(it) }
                        .onFailure { Log.w(TAG, "target onPause failed", it) }
                }
            }
            current?.let { dispatchTargetApplicationLifecycle("dispatchActivityPaused", it) }
            closePlaybackUiBridge()
            targetResumed = false
        }
        callSuperOnPause()
    }

    internal fun StageAActivity.handleHostStop() {
        SystemServiceIdentity.clearRootFinishInterceptor()
        returnToDesktopIfRootTargetFinished()
        Log.d(TAG, "host onStop target=${current?.javaClass?.name}")
        if (navigationHandoffState == StageAActivity.NavigationHandoffState.CHILD_LAUNCHED &&
            !isFinishing &&
            current?.isFinishing != true
        ) {
            navigationHandoffState = StageAActivity.NavigationHandoffState.NONE
        }
        // The headless window is no longer visible; restore drawing so
        // ui_observe still sees the target hierarchy.
        if (headless) root?.alpha = 1f
        if (TargetVisibilityPolicy.shouldKeepTargetActiveWhileHostHidden(
                pipActive,
                mirrorMode,
                headless
            )
        ) {
            // PiP or the mirror can deliver onStop when the task is
            // backgrounded, but the small window may still be alive. Keep the
            // target paused rather than destroying the page the assistant is
            // reading.
            callSuperOnStop()
            return
        }
        if (targetStarted) {
            current?.let {
                withTargetLoader {
                    if (!TargetLifecycle.perform(
                            it,
                            "performStop",
                            arrayOf<Any?>(false, "StageAActivity.onStop"),
                            arrayOf<Any?>(false),
                            emptyArray<Any?>()
                        )
                    ) {
                        runCatching { session?.instrumentation?.callActivityOnStop(it) }
                            .onFailure { Log.w(TAG, "target onStop failed", it) }
                    }
                }
            }
            current?.let { dispatchTargetApplicationLifecycle("dispatchActivityStopped", it) }
            targetStarted = false
            targetStopped = true
        }
        callSuperOnStop()
    }

    internal fun StageAActivity.handleHostDestroy() {
        Log.d(TAG, "host onDestroy target=${current?.javaClass?.name}")
        StageAActivity.hosts.removeAll { it.get() == null || it.get() === this }
        if (mirrorRequested && mirrorParentSlot >= 0) {
            HeadlessMirrorWindow.setActiveSlot(mirrorParentSlot)
        }
        RuntimeHostHooks.setOverlayHidden?.invoke(this, launchOverlayReason(), false)
        SystemServiceIdentity.clearRootFinishInterceptor()
        SystemServiceIdentity.setAiInfoCaptureActive(false)
        returnToDesktopIfRootTargetFinished()
        root?.removeCallbacks(mirrorFinishPoller)
        mirrorPresentation?.let { runCatching { it.dismiss() } }
        mirrorPresentation = null
        mirrorRoot = null
        unregisterPredictiveBack()
        if (isFinishing) targetStateKey?.let { TargetStateStore.remove(it) }
        targetRequestParameterHook?.close()
        targetRequestParameterHook = null
        closeCurrent()
        while (mirrorBackStack.isNotEmpty()) {
            val entry = mirrorBackStack.removeLast()
            val target = entry.target
            withTargetLoader {
                runCatching { session?.instrumentation?.callActivityOnDestroy(target) }
            }
            dispatchTargetApplicationLifecycle("dispatchActivityDestroyed", target)
            detachTarget(target)
            session?.detach(target)
        }
        if (session?.navigator === this) session?.navigator = null
        releaseSession()
        if (StageAActivity.active === this) StageAActivity.active = null
        callSuperOnDestroy()
    }
