package com.coke.nestspace.container.engine

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.coke.nestspace.container.ContainerCrashStore
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.ContainerProcessRouter
import com.coke.nestspace.container.HeadlessMirrorWindow
import com.coke.nestspace.container.StageAActivity
import com.coke.nestspace.container.TargetWebViewIdentityHook
import com.coke.nestspace.container.loader.NativeLibs
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.data.LaunchInfo

internal class InstalledAppLauncher(
    private val context: Context,
    private val apps: MutableMap<String, StoredApp>,
    private val store: SpaceStore,
    private val resolver: InstalledApkResolver,
    private val runtime: InstanceRuntimeCoordinator,
    private val onPublished: () -> Unit
) {
    suspend fun launch(
        app: StoredApp,
        instanceId: String,
        headless: Boolean = false,
        backgroundOnly: Boolean = false,
        requestedActivity: String? = null,
        requestedIntent: Intent? = null
    ): EngineResult<LaunchInfo> {
        Log.d(TAG, "launchLocked ${app.packageName} $instanceId")
        TargetWebViewIdentityHook.prefetch(context)
        if (!headless) {
            HeadlessMirrorWindow.hide()
        }
        val resolved = try {
            resolver.resolve(app)
        } catch (t: Throwable) {
            return EngineResult.Error(
                "APK_INVALID",
                AppLanguage.tr(
                    "APK 已损坏或丢失：${t.message ?: t.javaClass.simpleName}",
                    "The APK is corrupt or missing: ${t.message ?: t.javaClass.simpleName}"
                )
            )
        }
        val effectiveApp = if (resolved.index != app.apkIndex) {
            app.copy(apkIndex = resolved.index).also {
                apps[key(it)] = it
                runCatching { store.save(apps.values) }
                    .onFailure { error -> Log.w(TAG, "save APK index failed", error) }
            }
        } else {
            app
        }
        val instance = effectiveApp.instances.firstOrNull { it.id == instanceId }
            ?: return EngineResult.Error(
                "INSTANCE_NOT_FOUND",
                AppLanguage.tr(
                    "找不到实例 $instanceId",
                    "Instance $instanceId not found"
                )
            )

        val descriptor = resolved.descriptor
        Log.d(TAG, "launchLocked metadata ${descriptor.packageName}")
        if (descriptor.packageName != effectiveApp.packageName ||
            descriptor.launcherActivity.isNullOrBlank() ||
            descriptor.activityInfo == null
        ) {
            return EngineResult.Error(
                "APK_INVALID",
                AppLanguage.tr(
                    "APK 与安装记录不一致",
                    "APK does not match the install record"
                )
            )
        }
        val launchActivity = requestedActivity?.takeIf { it.isNotBlank() }
            ?: descriptor.launcherActivity
        if (requestedActivity != null &&
            descriptor.activities.none {
                it.name == requestedActivity || it.targetActivity == requestedActivity
            }
        ) {
            return EngineResult.Error(
                "ACTIVITY_NOT_FOUND",
                AppLanguage.tr(
                    "快捷方式指向的 Activity 不存在：$requestedActivity",
                    "The shortcut's Activity does not exist: $requestedActivity"
                )
            )
        }

        val nativeAbis = resolved.index.nativeAbis
        val preferredNativeAbi = NativeLibs.preferredHostAbi(context)
        if (nativeAbis.isNotEmpty() &&
            NativeLibs.supportedAbi(nativeAbis, preferredNativeAbi) == null
        ) {
            return EngineResult.Error(
                "ABI_MISMATCH",
                AppLanguage.tr(
                    "该应用只包含 ${nativeAbis.joinToString(", ")} 原生库，" +
                        "当前设备 ABI 为 ${Build.SUPPORTED_ABIS.joinToString(", ")}，" +
                        "无法运行。请换用支持该 ABI 的真机或模拟器。",
                    "This app only contains ${nativeAbis.joinToString(", ")} native " +
                        "libraries, while the device ABI is " +
                        "${Build.SUPPORTED_ABIS.joinToString(", ")}. Use a device or " +
                        "emulator that supports this ABI."
                )
            )
        }

        runtime.ensureInstanceDirs(effectiveApp.packageName, instance.id)
        val now = System.currentTimeMillis()
        val updated = effectiveApp.copy(
            instances = effectiveApp.instances.map {
                if (it.id == instance.id) it.copy(lastLaunchedAt = now) else it
            }
        )
        apps[key(updated)] = updated
        runCatching { store.save(apps.values) }
            .onFailure { Log.w(TAG, "save launch time failed", it) }

        val dataDir = runtime.dataDirFor(effectiveApp.packageName, instance.id)
        val launchActivityInfo = descriptor.activities.firstOrNull {
            it.name == launchActivity || it.targetActivity == launchActivity
        } ?: descriptor.activityInfo
        val targetProcess = ContainerProcessRouter.normalize(
            effectiveApp.packageName,
            launchActivityInfo.processName
        )
        val sessionId = ContainerProcessRouter.sessionId(
            effectiveApp.packageName,
            instance.id,
            targetProcess
        )
        val slot = try {
            ContainerProcessPool.acquire(
                context,
                ContainerProcessRouter.slotKey(
                    effectiveApp.packageName,
                    instance.id,
                    targetProcess
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "container slot allocation failed", t)
            return EngineResult.Error(
                "CONTAINER_SLOT_UNAVAILABLE",
                AppLanguage.tr(
                    "容器槽位暂时不可用，请关闭其他运行中的应用后重试。",
                    "No container slot is available. Close another running app and retry."
                )
            )
        }
        val hostClass = if (launchActivityInfo.launchMode ==
            android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE
        ) {
            ContainerProcessPool.isolatedActivityClass(slot)
        } else {
            ContainerProcessPool.activityClass(slot)
        }
        val targetIntent = requestedIntent ?: Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(effectiveApp.packageName, launchActivity)
        if (requestedIntent != null && requestedIntent.component == null) {
            targetIntent.setClassName(effectiveApp.packageName, launchActivity)
        }
        val intent = Intent(context, hostClass)
            .putExtra(StageAActivity.EXTRA_LAUNCH_PACKAGE, effectiveApp.packageName)
            .putExtra(StageAActivity.EXTRA_APP_LABEL, effectiveApp.label)
            .putExtra(StageAActivity.EXTRA_LAUNCH_INSTANCE, instance.id)
            .putExtra(StageAActivity.EXTRA_SESSION_ID, sessionId)
            .putExtra(StageAActivity.EXTRA_TARGET_PROCESS, targetProcess)
            .putExtra(StageAActivity.EXTRA_ACTIVITY_CLASS, launchActivity)
            .putExtra(StageAActivity.EXTRA_CONTAINER_ENTRY, true)
            .putExtra(StageAActivity.EXTRA_TARGET_INTENT, targetIntent)
            .putExtra(StageAActivity.EXTRA_HEADLESS, headless)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        var mirrorLaunch = false
        if (headless) {
            val previousMirrorSlot = HeadlessMirrorWindow.activeSlot()
            if (previousMirrorSlot >= 0 && previousMirrorSlot != slot) {
                val previousOwner =
                    ContainerProcessPool.ownerOfSlot(previousMirrorSlot)
                HeadlessMirrorWindow.hide()
                if (previousOwner == null) {
                    ContainerProcessPool.closeSlot(context, previousMirrorSlot)
                } else {
                    ContainerProcessPool.closeInstance(
                        context,
                        previousOwner.packageName,
                        previousOwner.instanceId
                    )
                }
                runtime.finishPreviousHostTasks(previousMirrorSlot)
            }
            runtime.finishPreviousHostTasks(slot)
            val mirrorId = runCatching {
                HeadlessMirrorWindow.awaitDisplay(
                    context,
                    showPanel = !backgroundOnly
                )
            }
                .onFailure { Log.w(TAG, "mirror display unavailable", it) }
                .getOrNull()
            if (mirrorId != null) {
                Log.i(TAG, "using mirror display $mirrorId")
                HeadlessMirrorWindow.setActiveSlot(slot)
                intent.putExtra(StageAActivity.EXTRA_MIRROR_DISPLAY_ID, mirrorId)
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
                mirrorLaunch = true
            }
        }
        val webEnvironmentReady = TargetWebViewIdentityHook.awaitPrefetch()
        Log.d(TAG, "web environment ready=$webEnvironmentReady")
        try {
            if (headless && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startActivity(
                    intent,
                    ActivityOptions.makeTaskLaunchBehind().toBundle()
                )
            } else {
                context.startActivity(intent)
            }
        } catch (t: Throwable) {
            runCatching { ContainerProcessPool.forget(context, sessionId) }
            return EngineResult.Error(
                "LAUNCH_FAILED",
                t.message ?: t.javaClass.simpleName
            )
        }
        Log.d(TAG, "headless=$headless mirror=$mirrorLaunch slot=$slot")
        ContainerProcessPool.markLaunched(context, sessionId)
        val crash = ContainerCrashStore.consume(
            context,
            effectiveApp.packageName,
            instance.id
        )
        if (crash != null) {
            Log.w(TAG, "launch after crash ${crash.reason}")
        }

        onPublished()
        return EngineResult.Ok(
            LaunchInfo(
                spaceId = effectiveApp.spaceId,
                packageName = effectiveApp.packageName,
                instanceId = instance.id,
                activity = launchActivity,
                dataDir = dataDir.absolutePath,
                targetProcess = targetProcess,
                slot = slot,
                restartedAfterCrash = crash != null,
                crashReason = crash?.reason
            )
        )
    }

    private fun key(app: StoredApp): String =
        key(app.spaceId, app.packageName)

    private fun key(spaceId: String, packageName: String): String =
        "$spaceId::$packageName"

    private companion object {
        const val TAG = "MiniContainerEngine"
    }
}
