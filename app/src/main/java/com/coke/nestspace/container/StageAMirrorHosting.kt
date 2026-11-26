package com.coke.nestspace.container

import android.app.Activity
import android.app.Presentation
import android.content.Intent
import android.hardware.display.DisplayManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.coke.nestspace.container.activity.TargetLifecycle

private const val TAG = "StageAActivity"
private const val MIRROR_FAST_POLLS = 40
private const val MIRROR_DISPLAY_REFRESH_MS = 1_000L

    fun StageAActivity.captureMirrorSnapshotForTransition() {
        if (!mirrorMode || isFinishing || isDestroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) return
        val decor = current?.window?.decorView ?: return
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) return
        val bitmapWidth = (width / 2).coerceAtLeast(1)
        val bitmapHeight = (height / 2).coerceAtLeast(1)
        runCatching {
            val bitmap = Bitmap.createBitmap(
                bitmapWidth,
                bitmapHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.scale(
                bitmapWidth.toFloat() / width,
                bitmapHeight.toFloat() / height
            )
            decor.draw(canvas)
            StageAActivity.mirrorSnapshot = bitmap
        }.onFailure {
            Log.w(TAG, "mirror snapshot failed", it)
        }
    }

    internal fun StageAActivity.addMirrorSnapshotBackground(parent: FrameLayout) {
        val bitmap = StageAActivity.mirrorSnapshot ?: return
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        parent.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun StageAActivity.openMirrorChildTarget(
        className: String,
        targetIntent: Intent,
        requestCode: Int
    ): Boolean {
        if (isFinishing || isDestroyed) return false
        val activeSession = session ?: return false
        val parent = current ?: return false
        val presentationRoot = mirrorRoot ?: root ?: return false

        // The parent may call finish() as soon as startActivity returns
        // (LauncherActivity does). Give it a private token first so that
        // finish cannot tear down the shared host.
        giveTargetPrivateToken(parent)
        captureMirrorSnapshotForTransition()
        val parentDecor = parent.window.decorView
        val instrumentation = activeSession.instrumentation
        withTargetLoader {
            if (targetResumed) {
                runCatching { instrumentation.callActivityOnPause(parent) }
                    .onFailure { Log.w(TAG, "parent onPause failed", it) }
            }
            if (targetStarted) {
                if (!TargetLifecycle.perform(
                        parent,
                        "performStop",
                        arrayOf<Any?>(false, "child target open"),
                        arrayOf<Any?>(false),
                        emptyArray<Any?>()
                    )
                ) {
                    runCatching { instrumentation.callActivityOnStop(parent) }
                        .onFailure { Log.w(TAG, "parent onStop failed", it) }
                }
            }
        }
        if (targetResumed) {
            dispatchTargetApplicationLifecycle("dispatchActivityPaused", parent)
        }
        if (targetStarted) {
            dispatchTargetApplicationLifecycle("dispatchActivityStopped", parent)
        }
        targetResumed = false
        targetStarted = false
        targetStopped = true
        detachTarget(parent)

        val entry = StageAActivity.MirrorBackEntry(
            parent,
            parentDecor,
            requestCode
        )
        mirrorBackStack.addLast(entry)
        presentationRoot.removeAllViews()

        val child = try {
            activeSession.createActivity(
                this,
                className,
                targetIntent,
                null
            )
        } catch (t: Throwable) {
            Log.w(TAG, "mirror child create failed: $className", t)
            mirrorBackStack.removeLast()
            restoreMirrorParent(entry, Activity.RESULT_CANCELED, null)
            return false
        }

        current = child
        currentPackageName = activeSession.spec.packageName
        if (navigationHandoffState ==
            StageAActivity.NavigationHandoffState.WAITING_FOR_CHILD
        ) {
            navigationHandoffOpenedInternalChild = true
        }
        if (mirrorMode) {
            dispatchTargetApplicationLifecycle("dispatchActivityCreated", child)
        }
        targetStarted = false
        targetPostCreated = false
        targetRestored = false
        targetAttached = false
        targetResumed = false
        targetStopped = false
        addMirrorSnapshotBackground(presentationRoot)
        presentationRoot.addView(
            child.window.decorView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        title = "${activeSession.spec.label} · ${activeSession.spec.instanceId}"

        withTargetLoader {
            if (!TargetLifecycle.perform(
                    child,
                    "performStart",
                    arrayOf<Any?>("mirror child start"),
                    emptyArray<Any?>()
                )
            ) {
                runCatching { instrumentation.callActivityOnStart(child) }
                    .onFailure { Log.w(TAG, "child onStart failed", it) }
            }
        }
        targetStarted = true
        if (mirrorMode) {
            dispatchTargetApplicationLifecycle("dispatchActivityStarted", child)
        }
        callTargetRestoreState(child)
        callTargetPostCreate(child)
        withTargetLoader {
            val performed = TargetLifecycle.perform(
                child,
                "performResume",
                arrayOf<Any?>(false, "mirror child resume"),
                emptyArray<Any?>()
            )
            if (!performed) {
                runCatching { instrumentation.callActivityOnResume(child) }
                    .onFailure { Log.w(TAG, "child onResume failed", it) }
                runCatching { TargetLifecycle.postResume(child) }
                    .onFailure { Log.w(TAG, "child onPostResume failed", it) }
            }
        }
        targetResumed = true
        if (mirrorMode) {
            dispatchTargetApplicationLifecycle("dispatchActivityResumed", child)
        }
        attachTarget(child)
        applyTargetActionBarInset(child)
        giveTargetPrivateToken(child)
        Log.d(
            TAG,
            "child target opened ${child.javaClass.name} " +
                "depth=${mirrorBackStack.size} mirror=$mirrorMode"
        )
        return true
    }

    private fun StageAActivity.destroyMirrorEntry(
        entry: StageAActivity.MirrorBackEntry
    ) {
        val target = entry.target
        withTargetLoader {
            runCatching { session?.instrumentation?.callActivityOnDestroy(target) }
                .onFailure { Log.w(TAG, "mirror parent destroy failed", it) }
        }
        dispatchTargetApplicationLifecycle("dispatchActivityDestroyed", target)
        detachTarget(target)
        session?.detach(target)
    }

    private fun StageAActivity.restoreMirrorParent(
        entry: StageAActivity.MirrorBackEntry,
        resultCode: Int,
        resultData: Intent?
    ) {
        val parent = entry.target
        if (parent.isFinishing) {
            // The parent finished itself while its child was on top (the
            // LauncherActivity -> MainActivity pattern). Skip it and resume
            // the next live entry, or close the host.
            destroyMirrorEntry(entry)
            if (mirrorBackStack.isNotEmpty()) {
                restoreMirrorParent(
                    mirrorBackStack.removeLast(),
                    Activity.RESULT_CANCELED,
                    null
                )
            } else {
                finish()
            }
            return
        }
        current = parent
        currentPackageName = session?.spec?.packageName
        val frame = mirrorRoot ?: root ?: return
        frame.removeAllViews()
        addMirrorSnapshotBackground(frame)
        frame.addView(
            entry.decor,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        targetStarted = false
        targetPostCreated = true
        targetRestored = true
        targetAttached = false
        targetResumed = false
        targetStopped = false
        if (mirrorMode) {
            withTargetLoader {
                TargetLifecycle.perform(parent, "performRestart", emptyArray<Any?>())
                if (!TargetLifecycle.perform(
                        parent,
                        "performStart",
                        arrayOf<Any?>("mirror parent start"),
                        emptyArray<Any?>()
                    )
                ) {
                    runCatching { session?.instrumentation?.callActivityOnStart(parent) }
                        .onFailure { Log.w(TAG, "parent onStart failed", it) }
                }
            }
            dispatchTargetApplicationLifecycle("dispatchActivityStarted", parent)
        }
        targetStarted = true
        if (entry.requestCode >= 0) {
            session?.loader?.let { resultData?.setExtrasClassLoader(it) }
            withTargetLoader {
                runCatching {
                    val method = Activity::class.java.getDeclaredMethod(
                        "onActivityResult",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Intent::class.java
                    )
                    method.isAccessible = true
                    method.invoke(parent, entry.requestCode, resultCode, resultData)
                }.onFailure { Log.w(TAG, "parent onActivityResult failed", it) }
            }
        }
        if (mirrorMode) {
            withTargetLoader {
                val performed = TargetLifecycle.perform(
                    parent,
                    "performResume",
                    arrayOf<Any?>(false, "mirror parent resume"),
                    emptyArray<Any?>()
                )
                if (!performed) {
                    runCatching { session?.instrumentation?.callActivityOnResume(parent) }
                        .onFailure { Log.w(TAG, "parent onResume failed", it) }
                    runCatching { TargetLifecycle.postResume(parent) }
                        .onFailure { Log.w(TAG, "parent onPostResume failed", it) }
                }
            }
            dispatchTargetApplicationLifecycle("dispatchActivityResumed", parent)
        }
        targetResumed = true
        attachTarget(parent)
        title = session?.spec?.let { "${it.label} · ${it.instanceId}" }
        Log.d(TAG, "mirror parent restored ${parent.javaClass.name} depth=${mirrorBackStack.size}")
    }

    internal fun StageAActivity.popMirrorTarget(): Boolean {
        if (mirrorBackStack.isEmpty()) return false
        val child = current ?: return false
        val resultCode = runCatching {
            Activity::class.java
                .getDeclaredField("mResultCode")
                .apply { isAccessible = true }
                .getInt(child)
        }.getOrDefault(Activity.RESULT_CANCELED)
        val resultData = runCatching {
            Activity::class.java
                .getDeclaredField("mResultData")
                .apply { isAccessible = true }
                .get(child) as? Intent
        }.getOrNull()
        val entry = mirrorBackStack.removeLast()
        closeCurrent()
        restoreMirrorParent(entry, resultCode, resultData)
        return true
    }

    internal fun StageAActivity.setupMirrorPresentation(id: Int): Boolean {
        val manager = getSystemService(DisplayManager::class.java) ?: return false
        val display = manager.getDisplay(id) ?: return false
        return runCatching {
            val presentation = Presentation(this, display)
            // Target navigation creates a new host on top. Keep its
            // Presentation translucent until the new target draws, so the
            // previous page stays visible instead of a black frame.
            presentation.window?.let { window ->
                window.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                )
                window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                window.clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
                )
                window.decorView.setBackgroundColor(Color.TRANSPARENT)
            }
            val content = FrameLayout(presentation.context)
            addMirrorSnapshotBackground(content)
            presentation.setContentView(content)
            presentation.show()
            mirrorPresentation = presentation
            mirrorRoot = content
            true
        }.getOrElse {
            Log.w(TAG, "mirror presentation failed", it)
            mirrorPresentation = null
            mirrorRoot = null
            false
        }
    }

    internal fun StageAActivity.refreshMirrorDisplay() {
        if (!mirrorRequested || isFinishing || isDestroyed) return
        val currentId = HeadlessMirrorWindow.storedDisplayId(this)
        if (currentId < 0 || currentId == mirrorDisplayId) return
        if (getSystemService(DisplayManager::class.java)?.getDisplay(currentId) == null) return
        Log.i(TAG, "mirror display changed $mirrorDisplayId -> $currentId")
        val target = current
        val oldRoot = mirrorRoot
        oldRoot?.removeAllViews()
        mirrorPresentation?.dismiss()
        mirrorPresentation = null
        mirrorRoot = null
        mirrorDisplayId = currentId
        mirrorMode = setupMirrorPresentation(currentId)
        if (!mirrorMode) return
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.rgb(246, 247, 251))
        )
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        val newRoot = mirrorRoot ?: return
        if (target != null && !target.isFinishing) {
            val targetDecor = target.window.decorView
            // The previous mirror can disappear before setupMirrorPresentation
            // succeeds. In that window the target may still be attached to the
            // host's fallback root, not oldRoot. Detach from whichever parent
            // currently owns it before moving it to the new Presentation.
            (targetDecor.parent as? ViewGroup)?.removeView(targetDecor)
            newRoot.addView(
                targetDecor,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    internal fun StageAActivity.promoteMirrorToHost() {
        if (!mirrorMode || isFinishing || isDestroyed) return
        val target = current
        val targetDecor = target?.window?.decorView
        (targetDecor?.parent as? ViewGroup)?.removeView(targetDecor)
        mirrorPresentation?.let { runCatching { it.dismiss() } }
        mirrorPresentation = null
        mirrorRoot = null
        mirrorMode = false
        mirrorRequested = false
        mirrorBackDone = false
        mirrorDisplayId = -1
        session?.mirrorDisplayId = -1
        val frame = root ?: return
        frame.removeAllViews()
        frame.setBackgroundColor(Color.rgb(246, 247, 251))
        if (targetDecor != null && !target.isFinishing) {
            frame.addView(
                targetDecor,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.rgb(246, 247, 251))
        )
        restoreLoadingWindowFocus()
        syncAiInfoCaptureGuard()
        Log.i(TAG, "promoted mirror target to full-screen host")
    }
