package com.coke.nestspace.container

import com.coke.nestspace.container.identity.SystemServiceIdentity
import android.app.Activity
import android.os.Build
import android.util.Log
import com.coke.nestspace.container.activity.ContainerBackPolicy

private const val TAG = "StageAActivity"

    internal fun StageAActivity.handleBack() {
        val target = current
        if (target == null) {
            callSuperOnBackPressed()
            return
        }
        if (target.isFinishing) {
            if (mirrorBackStack.isNotEmpty()) popMirrorTarget() else finish()
            return
        }
        val callbacksBefore = hasEnabledBackCallbacks(target)
        val declaringBefore = runCatching {
            target.javaClass.getMethod("onBackPressed").declaringClass.name
        }.getOrNull()
        Log.d(
            TAG,
            "back state hostRoot=$isTaskRoot callbacks=$callbacksBefore " +
                "entry=$containerEntry declaring=$declaringBefore " +
                "target=${target.javaClass.name}"
        )
        if (ContainerBackPolicy.shouldReturnRootToDesktop(
                isTaskRoot,
                callbacksBefore
            )
        ) {
            Log.d(TAG, "back on container root with no target callback; returning desktop")
            returnToDesktop()
            return
        }
        val finishHookArmed = armRootFinishInterceptor()
        backLaunchBlocked = false
        backCallbackRunning = true
        Log.d(TAG, "back target=${target.javaClass.name} finishing=${target.isFinishing}")
        var failed = false
        @Suppress("DEPRECATION")
        withTargetLoader {
            runCatching { target.onBackPressed() }
                .onFailure {
                    failed = true
                    Log.w(TAG, "target onBackPressed failed", it)
                }
        }
        backCallbackRunning = false
        if (backLaunchBlocked) {
            if (finishHookArmed) SystemServiceIdentity.clearRootFinishInterceptor()
            Log.d(TAG, "back triggered a new container activity; returning desktop")
            returnToDesktop()
            return
        }
        if (failed) {
            if (finishHookArmed) SystemServiceIdentity.clearRootFinishInterceptor()
            finish()
            return
        }
        if (target.isFinishing) {
            if (mirrorBackStack.isNotEmpty()) popMirrorTarget() else finish()
            return
        }
        root?.postDelayed({
            if (isFinishing || isDestroyed || current !== target || target.isFinishing) {
                return@postDelayed
            }
            val callbacks = hasEnabledBackCallbacks(target)
            val declaring = runCatching {
                target.javaClass.getMethod("onBackPressed").declaringClass
            }.getOrNull()
            Log.d(
                TAG,
                "back check callbacks=$callbacks declaring=${declaring?.name} " +
                    "targetFinishing=${target.isFinishing}"
            )
            if (callbacks) return@postDelayed
            val baseHandlesBack = declaring == Activity::class.java ||
                declaring?.name?.startsWith("androidx.activity.ComponentActivity") == true ||
                declaring?.name?.startsWith("androidx.appcompat.app.AppCompatActivity") == true
            if (baseHandlesBack) {
                Log.d(TAG, "back unhandled by ${target.javaClass.name}; closing")
                if (mirrorBackStack.isNotEmpty()) {
                    popMirrorTarget()
                } else {
                    finish()
                }
            }
        }, 250)
        if (finishHookArmed) {
            root?.postDelayed({
                SystemServiceIdentity.clearRootFinishInterceptor()
            }, 800)
        }
    }

    internal fun StageAActivity.armRootFinishInterceptor(): Boolean {
        if (mirrorMode || headless) return false
        SystemServiceIdentity.armRootExitHook(
            interceptFinish = isTaskRoot,
            interceptTaskExit = true
        ) { returnToDesktop() }
        return true
    }

    internal fun StageAActivity.registerPredictiveBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val callback = android.window.OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                callback
            )
            predictiveBackCallback = callback
        }.onFailure { Log.w(TAG, "predictive back register failed", it) }
    }

    internal fun StageAActivity.unregisterPredictiveBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = predictiveBackCallback as? android.window.OnBackInvokedCallback ?: return
        predictiveBackCallback = null
        runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    internal fun StageAActivity.returnToDesktopIfRootTargetFinished() {
        if (returningToDesktop ||
            mirrorMode ||
            headless ||
            isChangingConfigurations ||
            !isTaskRoot
        ) {
            return
        }
        if (!isFinishing && current?.isFinishing != true) return
        when (navigationHandoffState) {
            StageAActivity.NavigationHandoffState.WAITING_FOR_CHILD -> {
                Log.d(
                    TAG,
                    "keeping host alive while target navigation is pending"
                )
            }
            StageAActivity.NavigationHandoffState.CHILD_LAUNCHED -> {
                // The child launch has already been submitted to WindowManager.
                // Finish this host without opening the desktop over the child.
                Log.d(TAG, "closing parent host after child navigation")
                finish()
            }
            StageAActivity.NavigationHandoffState.NONE -> openContainerDesktop()
        }
    }
