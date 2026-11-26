package com.coke.nestspace.container.session

import com.coke.nestspace.container.*
import com.coke.nestspace.container.*
import com.coke.nestspace.container.api.RuntimeHostHooks
import com.coke.nestspace.container.api.webUrlFromViewIntent
import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.container.remote.ContainerProcessBrokerClient
import com.coke.nestspace.container.captureMirrorSnapshotForTransition
import com.coke.nestspace.container.openMirrorChildTarget
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Log
import java.lang.reflect.Field

private const val TAG = "ContainerSession"

    fun ContainerSession.startTargetActivity(
        host: StageAActivity,
        intent: Intent,
        requestCode: Int,
        sourceTarget: Activity? = null
    ): Boolean {
        if (host.blockActivityLaunchFromBack()) {
            Log.d(TAG, "blocked activity launch triggered by back: ${intent.component}")
            return true
        }
        val component = intent.component
        if (component != null && component.packageName != spec.packageName) return false
        val targetPackage = intent.`package`
        if (targetPackage != null && targetPackage != spec.packageName) return false

        val info = if (component != null) {
            val explicit = resolveActivity(component.className)
            if (explicit == null) {
                Log.w(TAG, "explicit target activity not declared: ${component.className}")
                return true
            }
            explicit
        } else {
            intents.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.info
                ?: if (isSelfLauncher(intent)) resolveActivity(null) else null
        } ?: run {
            Log.w(
                TAG,
                "unresolved target activity: component=${intent.component} " +
                    "action=${intent.action} package=${intent.`package`}"
            )
            // A target-scoped Intent that the APK does not declare must not be
            // handed to the host: the host PM cannot launch the target package.
            return targetPackage == spec.packageName
        }
        Log.d(
            TAG,
            "target activity intent class=${info.name} action=${intent.action} " +
                "flags=0x${intent.flags.toString(16)} " +
                "extras=${intent.extras?.keySet().orEmpty()}"
        )

        // Build the component from the host ActivityRecord instead of
        // Intent(host, ...): the container replaces the process package
        // identity, so host.getPackageName() may resolve to the target app.
        val hostPackage = host.componentName?.packageName
            ?: host.applicationInfo?.packageName
            ?: HostIdentity.packageName
        val targetProcess = ContainerProcessRouter.normalize(spec.packageName, info.processName)
        val reuseExisting = requestCode < 0 &&
            (info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP ||
                info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK ||
                info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0 ||
                intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        if (targetProcess == spec.targetProcessName &&
            reuseExisting &&
            host.deliverTargetIntent(info.name, intent)
        ) {
            return true
        }
        // Mirror mode keeps one real host Activity and swaps target
        // Activities in the same process inside its Presentation. This avoids
        // creating a WindowManager task for every in-app navigation.
        if (mirrorDisplayId >= 0 &&
            host.isMirrorMode() &&
            targetProcess == spec.targetProcessName
        ) {
            val targetIntent = Intent(intent).setClassName(
                spec.packageName,
                info.name
            )
            if (host.openMirrorChildTarget(info.name, targetIntent, requestCode)) {
                return true
            }
        }
        val currentSlot = ContainerProcessPool.currentSlot()
        if (targetProcess == spec.targetProcessName) {
            if (!host.beginTargetNavigationHandoff(sourceTarget, info.name)) {
                Log.w(TAG, "activity host unavailable for ${info.name}")
                return true
            }
            val launchHostClass = when {
                info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE &&
                    currentSlot in 0 until ContainerProcessPool.SLOT_COUNT ->
                    ContainerProcessPool.isolatedActivityClass(currentSlot)
                ContainerProcessPool.isIsolatedActivity(host.javaClass) &&
                    currentSlot in 0 until ContainerProcessPool.SLOT_COUNT ->
                    ContainerProcessPool.activityClass(currentSlot)
                else -> host.javaClass
            }
            val parentNotAttachedYet =
                (sourceTarget != null || host.isCreatingTargetActivity()) &&
                    host.currentTargetActivity() == null
            val launch = {
                val childIntent = Intent(intent).setClassName(
                    spec.packageName,
                    info.name
                )
                if (parentNotAttachedYet &&
                    targetProcess == spec.targetProcessName &&
                    host.isMirrorMode() &&
                    host.currentTargetActivity() != null &&
                    host.openMirrorChildTarget(
                        info.name,
                        childIntent,
                        requestCode
                    )
                ) {
                    true
                } else {
                    launchOnHost(
                        host,
                        hostPackage,
                        info,
                        launchHostClass,
                        targetProcess,
                        intent,
                        requestCode
                    )
                }
            }
            if (parentNotAttachedYet) {
                return host.stageTargetNavigationLaunch(info.name, launch)
            }
            val launched = launch()
            if (launched) {
                host.completeTargetNavigationHandoff(info.name)
            } else {
                host.cancelTargetNavigationHandoff(info.name)
            }
            return launched
        }

        // A subprocess activity needs a host slot. Allocation may require the
        // main-process broker, so resolve it off the main thread and launch
        // once the slot is known. The launch itself is asynchronous either way.
        if (!host.beginTargetNavigationHandoff(sourceTarget, info.name)) {
            Log.w(TAG, "activity host unavailable for ${info.name}")
            return true
        }
        ContainerProcessBrokerClient.acquireAsync(
            host,
            spec.packageName,
            spec.instanceId,
            targetProcess
        ) { slot ->
            if (slot == null) {
                Log.w(TAG, "no slot for activity ${info.name} process=$targetProcess")
                host.cancelTargetNavigationHandoff(info.name)
                return@acquireAsync
            }
            if (reuseExisting &&
                com.coke.nestspace.container.remote.ContainerRemoteProviderClient
                    .deliverActivityIntent(
                        this@startTargetActivity,
                        targetProcess,
                        info.name,
                        intent
                    )
            ) {
                host.completeTargetNavigationHandoff(info.name)
                return@acquireAsync
            }
            val launched = launchOnHost(
                host,
                hostPackage,
                info,
                if (info.launchMode ==
                    android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE
                ) {
                    ContainerProcessPool.isolatedActivityClass(slot)
                } else {
                    ContainerProcessPool.activityClass(slot)
                },
                targetProcess,
                intent,
                requestCode
            )
            if (launched) {
                host.completeTargetNavigationHandoff(info.name)
            } else {
                host.cancelTargetNavigationHandoff(info.name)
            }
        }
        return true
    }

    private fun ContainerSession.launchOnHost(
        host: StageAActivity,
        hostPackage: String,
        info: android.content.pm.ActivityInfo,
        hostClass: Class<out StageAActivity>,
        targetProcess: String,
        intent: Intent,
        requestCode: Int,
        forceSessionBootstrap: Boolean = false
    ): Boolean {
        val targetSessionId = ContainerProcessRouter.sessionId(
            spec.packageName,
            spec.instanceId,
            targetProcess
        )
        Log.d(
            TAG,
            "startTargetActivity hostPackage=$hostPackage target=${info.name} " +
                "process=$targetProcess host=${hostClass.name}"
        )
        val containerIntent = Intent()
            .setClassName(hostPackage, hostClass.name)
            .apply {
                putExtra(StageAActivity.EXTRA_SESSION_ID, targetSessionId)
                putExtra(StageAActivity.EXTRA_TARGET_PROCESS, targetProcess)
                putExtra(StageAActivity.EXTRA_APP_LABEL, spec.label)
                if (targetProcess != spec.targetProcessName || forceSessionBootstrap) {
                    // The target slot process may not have created this
                    // subprocess session yet; let it build one from the
                    // launch descriptor instead of reporting "session lost".
                    putExtra(StageAActivity.EXTRA_LAUNCH_PACKAGE, spec.packageName)
                    putExtra(StageAActivity.EXTRA_LAUNCH_INSTANCE, spec.instanceId)
                }
                putExtra(StageAActivity.EXTRA_ACTIVITY_CLASS, info.name)
                putExtra(StageAActivity.EXTRA_CONTAINER_ENTRY, false)
                putExtra(
                    StageAActivity.EXTRA_PARENT_SLOT,
                    ContainerProcessPool.currentSlot()
                )
                putExtra(
                    StageAActivity.EXTRA_TARGET_INTENT,
                    Intent(intent).setClassName(spec.packageName, info.name)
                )
                putExtra(StageAActivity.EXTRA_HEADLESS, headless)
                if (info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                    ContainerProcessPool.isIsolatedActivity(host.javaClass)
                ) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (mirrorDisplayId >= 0) {
                    putExtra(StageAActivity.EXTRA_MIRROR_DISPLAY_ID, mirrorDisplayId)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
            }
        return try {
            if (mirrorDisplayId >= 0) host.captureMirrorSnapshotForTransition()
            withHostIdentity(host) {
                val launchIntent = Intent(containerIntent).setClassName(
                    HostIdentity.packageName,
                    hostClass.name
                )
                Log.d(
                    TAG,
                    "host launch component=${launchIntent.component} " +
                        "flags=0x${launchIntent.flags.toString(16)}"
                )
                // Starting an Activity from a background host can move the
                // StageA task forward for a frame. Launch behind for mirror
                // sessions; the host window itself is desktop-colored so any
                // residual task transition is not black.
                val options = if (mirrorDisplayId >= 0) {
                    android.app.ActivityOptions.makeTaskLaunchBehind().toBundle()
                } else {
                    null
                }
                if (requestCode >= 0) {
                    host.startActivityForResult(launchIntent, requestCode, options)
                } else if (options != null) {
                    host.startActivity(launchIntent, options)
                } else {
                    host.startActivity(launchIntent)
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "start target activity failed: ${info.name}", t)
            false
        }
    }

    private fun <T> ContainerSession.withHostIdentity(host: StageAActivity, block: () -> T): T {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        val boundField = threadClass.getDeclaredField("mBoundApplication")
            .apply { isAccessible = true }
        val bound = boundField.get(thread)
        val appInfoField = bound?.javaClass?.getDeclaredField("appInfo")
            ?.apply { isAccessible = true }
        val previousAppInfo = appInfoField?.get(bound)
        val hostPackage = HostIdentity.packageName
        val hostAppInfo = runCatching {
            context.packageManager.getApplicationInfo(hostPackage, 0)
        }.getOrNull() ?: host.applicationInfo
        val base = host.baseContext
        val savedPackages = mutableListOf<Triple<java.lang.reflect.Field, Any, Any?>>()
        runCatching {
            base.javaClass.declaredFields.forEach { field ->
                if (field.type == String::class.java &&
                    field.name.contains("ackage", ignoreCase = true)
                ) {
                    field.isAccessible = true
                    savedPackages += Triple(field, base, field.get(base))
                    field.set(base, hostPackage)
                }
            }
        }
        try {
            appInfoField?.set(bound, hostAppInfo)
            Log.d(
                TAG,
                "withHostIdentity host=$hostPackage " +
                    "appInfo=${hostAppInfo?.packageName} " +
                    "base=${host.baseContext.packageName}"
            )
            return block()
        } finally {
            appInfoField?.set(bound, previousAppInfo)
            savedPackages.forEach { (field, target, value) ->
                runCatching { field.set(target, value) }
            }
        }
    }

    fun ContainerSession.startExternalActivity(host: StageAActivity, intent: Intent, requestCode: Int): Boolean {
        val webUrl = webUrlFromViewIntent(intent)
        if (webUrl != null) {
            return runCatching {
                RuntimeHostHooks.openBrowser?.invoke(host, webUrl)
                true
            }.getOrElse {
                Log.w(TAG, "open web intent inside NestSpace failed", it)
                false
            }
        }
        return try {
            withHostIdentity(host) {
                if (requestCode >= 0) {
                    host.startActivityForResult(Intent(intent), requestCode)
                } else {
                    host.startActivity(Intent(intent))
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "start external activity failed: ${intent.component}", t)
            false
        }
    }
