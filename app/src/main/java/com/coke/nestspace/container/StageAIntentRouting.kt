package com.coke.nestspace.container

import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.container.identity.ProcessNameCompat
import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.container.identity.SystemServiceIdentity
import com.coke.nestspace.container.runtime.RuntimeBootGraphDiagnostics
import com.coke.nestspace.container.loader.withContainerClassLoader
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.coke.nestspace.container.activity.TargetApplicationLifecycle
import com.coke.nestspace.container.activity.TargetLifecycle
import com.coke.nestspace.container.activity.TargetPlaybackUiBridge
import com.coke.nestspace.container.activity.TargetVisibilityPolicy
import com.coke.nestspace.container.activity.TargetStateStore
import com.coke.nestspace.container.api.RuntimeHostHooks

private const val TAG = "StageAActivity"

    internal fun StageAActivity.handleIntent(intent: Intent, savedState: Bundle?) {
        val launchPackage = intent.getStringExtra(StageAActivity.EXTRA_LAUNCH_PACKAGE)
        val launchInstance = intent.getStringExtra(StageAActivity.EXTRA_LAUNCH_INSTANCE)
        Log.d(
            TAG,
            "handle host intent instance=${hashCode()} launch=$launchPackage " +
                "session=${intent.getStringExtra(StageAActivity.EXTRA_SESSION_ID)}"
        )
        containerEntry = savedState?.getBoolean(StageAActivity.KEY_CONTAINER_ENTRY)
            ?: intent.getBooleanExtra(StageAActivity.EXTRA_CONTAINER_ENTRY, false)
        if (!launchPackage.isNullOrBlank() && !launchInstance.isNullOrBlank()) {
            val targetProcess = ContainerProcessRouter.normalize(
                launchPackage,
                intent.getStringExtra(StageAActivity.EXTRA_TARGET_PROCESS)
            )
            val requestedSession = intent.getStringExtra(StageAActivity.EXTRA_SESSION_ID)
                ?.takeIf { it.isNotBlank() }
                ?: ContainerProcessRouter.sessionId(launchPackage, launchInstance, targetProcess)
            val existing = ContainerSessions.retain(requestedSession)
            if (existing != null) {
                session = existing
                sessionRetained = true
                existing.navigator = this
                val targetIntent = targetIntentFrom(intent)
                openActivity(
                    existing,
                    launchActivityClass(existing, intent, targetIntent),
                    targetIntent,
                    savedState
                )
                return
            }
            try {
                val spec = ContainerSpecFactory.build(
                    this,
                    launchPackage,
                    launchInstance,
                    requestedSession,
                    targetProcess,
                    intent.getStringExtra(StageAActivity.EXTRA_ACTIVITY_CLASS)
                )
                val created = ContainerSessions.acquire(this, spec)
                session = created
                sessionRetained = true
                created.navigator = this
                val targetIntent = targetIntentFrom(intent)
                openFirstActivity(
                    created,
                    launchActivityClass(created, intent, targetIntent),
                    targetIntent,
                    savedState
                )
            } catch (t: Throwable) {
                Log.e(TAG, "container launch failed", t)
                ContainerLaunchProgress.fail()
                analyzeBootFailure("container-launch", t)
                showError(
                    AppLanguage.tr(
                        "容器启动失败：${t.message ?: t.javaClass.name}\n\n" +
                            t.stackTrace.take(8).joinToString("\n"),
                        "Container failed to start: ${t.message ?: t.javaClass.name}\n\n" +
                            t.stackTrace.take(8).joinToString("\n")
                    )
                )
            }
            return
        }

        val sessionId = intent.getStringExtra(StageAActivity.EXTRA_SESSION_ID)
        if (!sessionId.isNullOrBlank()) {
            val existing = ContainerSessions.retain(sessionId)
            Log.d(
                TAG,
                "retained session instance=${hashCode()} id=$sessionId " +
                    "found=${existing != null}"
            )
            if (existing == null) {
                showError(
                    AppLanguage.tr(
                        "容器会话已失效，请回到 NestSpace 重新启动",
                        "The container session expired. Open it again from NestSpace."
                    )
                )
                return
            }
            session = existing
            sessionRetained = true
            existing.navigator = this
            val targetIntent = targetIntentFrom(intent)
            val className = launchActivityClass(existing, intent, targetIntent)
            openActivity(existing, className, targetIntent, savedState)
            return
        }

        val token = intent.getStringExtra(StageAActivity.EXTRA_TOKEN)
        if (token.isNullOrBlank()) {
            showError(AppLanguage.tr("容器会话参数缺失", "Container session parameters are missing"))
            return
        }
        val spec = ContainerLaunchRegistry.get(token)
        if (spec == null) {
            showError(
                    AppLanguage.tr(
                        "容器会话已失效，请回到 NestSpace 重新启动",
                        "The container session expired. Open it again from NestSpace."
                    )
                )
            return
        }

        try {
            ContainerLaunchRegistry.remove(token)
            val created = ContainerSessions.acquire(this, spec)
            session = created
            sessionRetained = true
            created.navigator = this
            val launcherIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(spec.packageName, spec.launcherActivity)
            openFirstActivity(created, spec.launcherActivity, launcherIntent, savedState)
        } catch (t: Throwable) {
            Log.e(TAG, "container first activity failed", t)
            ContainerLaunchProgress.fail()
            analyzeBootFailure("first-activity-launch", t)
            showError(
                AppLanguage.tr(
                    "容器启动失败：${t.message ?: t.javaClass.name}\n\n" +
                        t.stackTrace.take(8).joinToString("\n"),
                    "Container failed to start: ${t.message ?: t.javaClass.name}\n\n" +
                        t.stackTrace.take(8).joinToString("\n")
                )
            )
        }
    }

    internal fun StageAActivity.launchActivityClass(
        session: ContainerSession,
        hostIntent: Intent,
        targetIntent: Intent?
    ): String {
        val requested = hostIntent.getStringExtra(StageAActivity.EXTRA_ACTIVITY_CLASS)
            ?.takeIf { it.isNotBlank() }
        return requested
            ?: targetIntent?.component?.className?.takeIf { it.isNotBlank() }
            ?: session.spec.launcherActivity
    }

    internal fun StageAActivity.openFirstActivity(
        session: ContainerSession,
        className: String,
        targetIntent: Intent?,
        savedState: Bundle?
    ) {
        Log.d(
            TAG,
            "open first activity class=$className action=${targetIntent?.action} " +
                "component=${targetIntent?.component}"
        )
        val open = {
            if (!isFinishing && !isDestroyed) {
                openActivity(session, className, targetIntent, savedState)
            }
        }
        session.noteFirstActivityScheduled()
        targetRequestParameterHook?.close()
        targetRequestParameterHook = session.runtimeProfile
            ?.installFirstActivityHooks(
                session.applicationSpec,
                session.loader,
                session.context
            )
        val startupDelay = com.coke.nestspace.container.runtime
            .NativeEnvironmentBridge.readPropertyForDiagnostics(
                "debug.nestspace.first_activity_delay_ms"
            )?.toLongOrNull()?.coerceIn(0L, 3000L) ?: 0L
        if ((if (startupDelay > 0L) {
                root?.postDelayed(open, startupDelay)
            } else {
                root?.post(open)
            }) == true
        ) {
            Log.d(TAG, "first activity queued after startup coordinator")
        } else {
            open()
        }
    }

    internal fun StageAActivity.openActivity(
        session: ContainerSession,
        className: String?,
        targetIntent: Intent?,
        savedState: Bundle? = null
    ) {
        try {
            ContainerLaunchProgress.advance(
                LaunchProgressStage.FIRST_ACTIVITY_SCHEDULED
            )
            session.context.hostWindowManager = windowManager
            if (mirrorMode) session.mirrorDisplayId = mirrorDisplayId
            // The host Bundle is not target state; Android passes it back
            // after host recreation. Use the id saved in it to find exactly
            // this host instance's target state, and never consume another
            // instance's state on a fresh launch.
            val restoreHostId = savedState?.getInt(StageAActivity.KEY_HOST_STATE_ID, 0) ?: 0
            val effectiveState = if (restoreHostId > 0) {
                TargetStateStore.consume(
                    TargetStateStore.key(session, className, restoreHostId)
                )
            } else {
                null
            }
            effectiveState?.classLoader = session.loader
            targetIntent?.setExtrasClassLoader(session.loader)
            pendingTargetState = effectiveState
            targetStateKey = TargetStateStore.key(session, className, hostStateId)
            targetRestored = false
            targetAttached = false

            // A target may create its own SurfaceView in its constructor.
            // Keep the launch surface attached until the target Activity has
            // been created. Removing the last child first leaves the host
            // ViewRoot briefly empty while a queued insets transaction may
            // still reference its SurfaceControl.
            val frame = mirrorRoot ?: root ?: return
            val target = try {
                creatingTargetActivity = true
                // Mi Home starts its CTA page and immediately finishes the
                // root Activity from onCreate. At this point the synthetic
                // target still shares the host's Activity token, so letting
                // that finish through would destroy the newly staged CTA host.
                SystemServiceIdentity.armRootExitHook(
                    interceptFinish = true,
                    interceptTaskExit = false
                ) {}
                session.createActivity(
                    this,
                    className,
                    targetIntent,
                    effectiveState
                )
            } finally {
                creatingTargetActivity = false
                SystemServiceIdentity.clearRootFinishInterceptor()
            }
            if (navigationHandoffState == StageAActivity.NavigationHandoffState.WAITING_FOR_CHILD &&
                target.isFinishing
            ) {
                runCatching {
                    Activity::class.java
                        .getDeclaredField("mFinished")
                        .apply { isAccessible = true }
                        .setBoolean(target, false)
                }.onFailure {
                    Log.w(TAG, "clear target finished state failed", it)
                }
            }
            // Keep the independent loading renderer alive while the target
            // Activity is being constructed. Stopping it first freezes the
            // launch screen and makes a slow initialization look like a hang.
            stopLaunchRendering(frame)
            frame.removeAllViews()
            if (mirrorMode) addMirrorSnapshotBackground(frame)
            current = target
            currentPackageName = session.spec.packageName
            dispatchTargetApplicationLifecycle("dispatchActivityCreated", target)
            targetStarted = false
            targetPostCreated = false
            targetResumed = false
            targetStopped = false
            frame.addView(
                target.window.decorView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            RuntimeHostHooks.setOverlayHidden?.invoke(this, launchOverlayReason(), false)
            if (!mirrorMode) restoreLoadingWindowFocus()
            refreshEnvironmentLine(target)
            title = "${session.spec.label} · ${session.spec.instanceId}"

            // Mirror/headless hosts are intentionally moved behind the
            // assistant before the first target Activity is created. The
            // virtual Activity still represents the foreground app in that
            // display, so its lifecycle must not depend on hostResumed.
            if (TargetVisibilityPolicy.shouldRunForegroundLifecycle(
                    hostResumed,
                    mirrorMode,
                    headless
                )
            ) {
                withTargetLoader {
                    if (!TargetLifecycle.perform(
                            target,
                            "performStart",
                            arrayOf<Any?>("StageAActivity.openStart"),
                            emptyArray<Any?>()
                        )
                    ) {
                        runCatching { session.instrumentation.callActivityOnStart(target) }
                            .onFailure { Log.w(TAG, "target onStart (open) failed", it) }
                    }
                }
                targetStarted = true
                dispatchTargetApplicationLifecycle("dispatchActivityStarted", target)
                callTargetRestoreState(target)
                callTargetPostCreate(target)
                withTargetLoader {
                    val performed = TargetLifecycle.perform(
                        target,
                        "performResume",
                        arrayOf<Any?>(false, "StageAActivity.openResume"),
                        emptyArray<Any?>()
                    )
                    if (!performed) {
                        runCatching { session.instrumentation.callActivityOnResume(target) }
                            .onFailure { Log.w(TAG, "target onResume (open) failed", it) }
                        runCatching { TargetLifecycle.postResume(target) }
                            .onFailure { Log.w(TAG, "target onPostResume (open) failed", it) }
                    }
                }
                targetResumed = true
                dispatchTargetApplicationLifecycle("dispatchActivityResumed", target)
                attachTarget(target)
            }
            applyTargetActionBarInset(target)
            if (mirrorMode) giveTargetPrivateToken(target)
            session.noteFirstActivityOpened()
            runStagedNavigationLaunches()
        } catch (t: Throwable) {
            stagedNavigationLaunches.clear()
            Log.e(TAG, "container target activity failed", t)
            ContainerLaunchProgress.fail()
            analyzeBootFailure("target-activity-create", t)
            showError(
                AppLanguage.tr(
                    "容器启动失败：${t.message ?: t.javaClass.name}\n\n" +
                        t.stackTrace.take(8).joinToString("\n"),
                    "Container failed to start: ${t.message ?: t.javaClass.name}\n\n" +
                        t.stackTrace.take(8).joinToString("\n")
                )
            )
        }
    }

    internal fun StageAActivity.returnToDesktop() {
        if (returningToDesktop) return
        openContainerDesktop()
        finish()
    }

    internal fun StageAActivity.openContainerDesktop() {
        returningToDesktop = true
        RuntimeHostHooks.requestDesktop?.invoke()
        runCatching {
            applicationContext.startActivity(
                Intent()
                    .setClassName(
                        HostIdentity.packageName,
                        "com.coke.nestspace.MainActivity"
                    )
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        }
    }

    internal fun StageAActivity.closeCurrent() {
        val target = current
        current = null
        currentPackageName = null
        if (target != null) {
            val instrumentation = session?.instrumentation
            withTargetLoader {
                if (targetResumed) {
                    runCatching { instrumentation?.callActivityOnPause(target) }
                }
                if (targetStarted) {
                    if (!TargetLifecycle.perform(
                            target,
                            "performStop",
                            arrayOf<Any?>(false, "StageAActivity.close"),
                            arrayOf<Any?>(false),
                            emptyArray<Any?>()
                        )
                    ) {
                        runCatching { instrumentation?.callActivityOnStop(target) }
                    }
                }
                runCatching { instrumentation?.callActivityOnDestroy(target) }
            }
            dispatchTargetApplicationLifecycle("dispatchActivityDestroyed", target)
            detachTarget(target)
            session?.detach(target)
        }
        targetStarted = false
        targetPostCreated = false
        targetRestored = false
        targetAttached = false
        targetResumed = false
        targetStopped = false
        root?.removeAllViews()
    }

    internal fun StageAActivity.releaseSession() {
        val currentSession = session ?: return
        if (sessionRetained) {
            ContainerSessions.release(currentSession.id)
            sessionRetained = false
        }
        session = null
    }

    internal fun StageAActivity.withTargetLoader(block: () -> Unit) {
        val currentSession = session
        val loader = currentSession?.loader
        if (loader != null) {
            ProcessNameCompat.withProcessName(currentSession.spec.targetProcessName) {
                withContainerClassLoader(loader, block)
            }
        } else {
            block()
        }
    }

    internal fun StageAActivity.callTargetRestoreState(target: Activity) {
        if (targetRestored) return
        val state = pendingTargetState ?: return
        session?.loader?.let { state.classLoader = it }
        targetRestored = true
        withTargetLoader {
            TargetLifecycle.restore(target, session?.instrumentation, state)
        }
    }

    internal fun StageAActivity.callTargetPostCreate(target: Activity) {
        if (targetPostCreated) return
        targetPostCreated = true
        val state = pendingTargetState
        withTargetLoader {
            TargetLifecycle.postCreate(target, session?.instrumentation, state)
        }
        pendingTargetState = null
    }

    internal fun StageAActivity.dispatchTargetApplicationLifecycle(
        event: String,
        target: Activity,
        bundle: Bundle? = null
    ) {
        if (event == "dispatchActivityResumed") {
            closePlaybackUiBridge()
            playbackUiBridge = session?.let { activeSession ->
                TargetPlaybackUiBridge.start(
                    target,
                    activeSession.loader,
                    listOf(activeSession.spec.baseApkPath) +
                        activeSession.spec.splitApkPaths
                )
            }
        } else if (
            event == "dispatchActivityPaused" ||
            event == "dispatchActivityStopped" ||
            event == "dispatchActivityDestroyed"
        ) {
            closePlaybackUiBridge()
        }
        val activeSession = session ?: return
        val application = activeSession.application
        withTargetLoader {
            runCatching {
                val bootstrapApplication = activeSession.bootstrapApplication
                if (bootstrapApplication == null) {
                    TargetApplicationLifecycle.dispatch(
                        application,
                        event,
                        target,
                        bundle
                    )
                } else {
                    TargetApplicationLifecycle.dispatchExternal(
                        listOf(application, bootstrapApplication),
                        event,
                        target,
                        bundle
                    )
                }
            }.onFailure { Log.w(TAG, "$event failed", it) }
        }
    }

    internal fun StageAActivity.closePlaybackUiBridge() {
        playbackUiBridge?.close()
        playbackUiBridge = null
    }

    internal fun StageAActivity.attachTarget(target: Activity) {
        if (targetAttached) return
        targetAttached = true
        withTargetLoader { TargetLifecycle.attach(target) }
    }

    internal fun StageAActivity.detachTarget(target: Activity) {
        if (!targetAttached) return
        targetAttached = false
        withTargetLoader { TargetLifecycle.detach(target) }
    }

    internal fun StageAActivity.showError(message: String) {
        closeCurrent()
        if (!mirrorMode) restoreLoadingWindowFocus()
        val frame = root ?: return
        frame.removeAllViews()
        title = AppLanguage.tr("容器启动失败", "Container failed to start")
        val text = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(Color.RED)
            setPadding(48, 96, 48, 48)
            gravity = Gravity.TOP or Gravity.START
        }
        frame.addView(
            text,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    internal fun StageAActivity.analyzeBootFailure(stage: String, error: Throwable) {
        val current = session ?: return
        current.recordRuntimeFailure(stage, error)
        RuntimeBootGraphDiagnostics.analyzeAsync(
            packageName = current.spec.packageName,
            processName = current.spec.targetProcessName,
            dataRoot = current.spec.dataDir,
            classLoader = current.loader,
            apkPaths = listOf(current.spec.baseApkPath) + current.spec.splitApkPaths,
            stage = stage,
            failure = error
        )
    }
