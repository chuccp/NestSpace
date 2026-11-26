package com.coke.nestspace.container.engine

import com.coke.nestspace.container.ContainerCrashStore
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.ContainerProcessRouter
import com.coke.nestspace.container.HeadlessMirrorWindow
import com.coke.nestspace.core.i18n.AppLanguage
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.coke.nestspace.container.loader.ApkMetadataCache
import com.coke.nestspace.container.loader.AppShortcutLoader
import com.coke.nestspace.container.loader.NativeLibs
import com.coke.nestspace.container.ContainerContext
import com.coke.nestspace.container.shortcut.DynamicShortcutStore
import com.coke.nestspace.container.StageAActivity
import com.coke.nestspace.container.TargetWebViewIdentityHook
import com.coke.nestspace.container.vpm.ContainerPermissionStore
import com.coke.nestspace.container.control.ContainerUiKeepAlive
import com.coke.nestspace.core.files.safePathSegment
import com.coke.nestspace.data.AppInfo
import com.coke.nestspace.data.AppShortcut
import com.coke.nestspace.data.LaunchInfo
import com.coke.nestspace.data.VirtualApp
import com.coke.nestspace.data.VirtualInstance
import com.coke.nestspace.plugin.RuntimeLaunchMode
import com.coke.nestspace.plugin.RuntimeProxyContext
import com.coke.nestspace.plugin.RuntimeProxyPlugins
import com.coke.nestspace.container.engine.install.ApkInputStager
import com.coke.nestspace.container.engine.install.ApkInstallException
import com.coke.nestspace.container.engine.install.ApkInstallInspector
import com.coke.nestspace.container.engine.install.moveApkFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Persistent Stage B container engine.
 *
 * Layout under filesDir/vspace:
 *   apps/<package>/<install-id>/base.apk + split-N.apk
 *   data/<package>/<instance-id>/{files,cache,code_cache,databases,...}
 *   state.json
 *
 * Install validates package consistency, signature consistency, minSdk and
 * REQUIRE_SECURE_ENV, then stores APKs. Launch creates a token-backed launch
 * spec for StageAActivity/MiniContainer. Multi-open creates another instance
 * with an isolated data directory.
 */
class MiniContainerEngine(private val context: Context) : ContainerEngine {

    private val rootDir = File(context.filesDir, "vspace")
    private val appsDir = File(rootDir, "apps")
    private val dataRootDir = File(rootDir, "data")
    private val tempDir = File(rootDir, "tmp")
    private val store = SpaceStore(File(rootDir, "state.json"))
    private val lock = Mutex()
    private val apkInputStager = ApkInputStager(context, tempDir)
    private val installedApps = InstalledAppProjector(context, dataRootDir)
    private val installedApkResolver = InstalledApkResolver(context)

    private var loaded = false
    private val apps = linkedMapOf<String, StoredApp>()
    private val runtimeCoordinator = InstanceRuntimeCoordinator(context, dataRootDir)
    private val appLauncher = InstalledAppLauncher(
        context = context,
        apps = apps,
        store = store,
        resolver = installedApkResolver,
        runtime = runtimeCoordinator,
        onPublished = ::publish
    )

    private val _virtualApps = MutableStateFlow<List<VirtualApp>>(emptyList())
    override val virtualApps: StateFlow<List<VirtualApp>> = _virtualApps.asStateFlow()

    override suspend fun install(
        spaceId: String,
        apkUris: List<String>
    ): EngineResult<VirtualApp> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            installLocked(spaceId, apkUris)
        }
    }

    override suspend fun matchesInstalledPayload(
        spaceId: String,
        apkUris: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            if (apkUris.isEmpty()) return@withLock false
            apkInputStager.withStaged(apkUris, "input") { staged ->
                val copied = staged.files
                val descriptors = staged.descriptors
                val packageName = descriptors
                    .map { it.packageName }
                    .distinct()
                    .singleOrNull()
                    ?: return@withStaged false
                val base = descriptors.singleOrNull {
                    it.hasLauncher && it.splitName == null
                } ?: return@withStaged false
                val existing = apps[key(spaceId, packageName)]
                    ?: return@withStaged false
                val pendingDigests = copied.map(ApkPayloadIdentity::sha256)
                existing.versionCode == base.versionCode &&
                    existing.versionName == base.versionName &&
                    (
                        existing.apkIndex?.matchesPayload(pendingDigests)
                            ?: ApkPayloadIdentity.same(
                                pending = copied,
                                installed = listOf(File(existing.baseApkPath)) +
                                    existing.splitApkPaths.map(::File)
                            )
                        )
            } ?: false
        }
    }

    override suspend fun matchesInstalledIdentity(
        spaceId: String,
        apkUris: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            if (apkUris.isEmpty()) return@withLock false
            apkInputStager.withStaged(apkUris, "identity") { staged ->
                val descriptors = staged.descriptors
                val packageName = descriptors
                    .map { it.packageName }
                    .distinct()
                    .singleOrNull()
                    ?: return@withStaged false
                val base = descriptors.singleOrNull {
                    it.hasLauncher && it.splitName == null
                } ?: return@withStaged false
                val existing = apps[key(spaceId, packageName)]
                    ?: return@withStaged false
                val signature = base.signatureSha256
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withStaged false
                existing.signatureSha256 == signature &&
                    descriptors.all {
                        it.signatureSha256.isNullOrBlank() ||
                            it.signatureSha256 == signature
                    }
            } ?: false
        }
    }

    override suspend fun listApps(spaceId: String): EngineResult<List<AppInfo>> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                ensureLoadedLocked()
                EngineResult.Ok(
                    apps.values
                        .filter { it.spaceId == spaceId }
                        .sortedBy { it.label.lowercase() }
                        .map {
                            AppInfo(
                                packageName = it.packageName,
                                label = it.label,
                                icon = null,
                                isSystem = false
                            )
                        }
                )
            }
        }

    override suspend fun listInstalled(spaceId: String): EngineResult<List<VirtualApp>> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                ensureLoadedLocked()
                EngineResult.Ok(installedApps.publicApps(spaceId, apps.values))
            }
        }

    override suspend fun launch(
        spaceId: String,
        packageName: String,
        instanceId: String?,
        headless: Boolean,
        backgroundOnly: Boolean,
        requestedActivity: String?
    ): EngineResult<LaunchInfo> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val app = apps[key(spaceId, packageName)]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr(
                        "空间里没有 $packageName，请先导入 APK",
                        "$packageName is not in the space; import its APK first"
                    )
                )
            val instance = if (instanceId.isNullOrBlank()) {
                app.defaultInstance()
            } else {
                app.instances.firstOrNull { it.id == instanceId }
                    ?: return@withLock EngineResult.Error(
                        "INSTANCE_NOT_FOUND",
                        AppLanguage.tr("找不到实例 $instanceId", "Instance $instanceId not found")
                    )
            }
            RuntimeProxyPlugins.resolve(
                RuntimeProxyContext(
                    app.packageName,
                    app.versionCode,
                    Build.VERSION.SDK_INT
                )
            )?.let { policy ->
                when (policy.mode) {
                    RuntimeLaunchMode.HOST_FALLBACK -> launchHostFallback(
                        app = app,
                        instanceId = instance.id,
                        fallbackReason = policy.reason,
                        pluginId = policy.pluginId,
                        policyId = policy.policyId
                    ).let { return@withLock it }
                    RuntimeLaunchMode.BLOCKED -> return@withLock EngineResult.Error(
                        "RUNTIME_BLOCKED_BY_PLUGIN",
                        policy.reason
                    )
                    RuntimeLaunchMode.CONTAINER -> Unit
                }
            }
            appLauncher.launch(
                app = app,
                instanceId = instance.id,
                headless = headless,
                backgroundOnly = backgroundOnly,
                requestedActivity = requestedActivity
            )
        }
    }

    private fun launchHostFallback(
        app: StoredApp,
        instanceId: String,
        fallbackReason: String,
        pluginId: String,
        policyId: String
    ): EngineResult<LaunchInfo> {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return EngineResult.Error(
                "HOST_APP_REQUIRED",
                AppLanguage.tr(
                    "该版本需要宿主兼容模式，但平板上没有安装同名官方应用。",
                    "This build requires host compatibility mode, but the official " +
                        "app is not installed on the device."
                )
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Log.i(
                TAG,
                "host fallback launch package=${app.packageName} reason=$fallbackReason"
            )
            EngineResult.Ok(
                LaunchInfo(
                    spaceId = app.spaceId,
                    packageName = app.packageName,
                    instanceId = instanceId,
                    activity = intent.component?.className.orEmpty(),
                    dataDir = "",
                    hostFallback = true,
                    fallbackReason = fallbackReason,
                    runtimePluginId = pluginId,
                    runtimePolicyId = policyId
                )
            )
        } catch (t: Throwable) {
            EngineResult.Error(
                "HOST_FALLBACK_FAILED",
                t.message ?: t.javaClass.simpleName
            )
        }
    }

    override suspend fun createInstance(
        spaceId: String,
        packageName: String
    ): EngineResult<VirtualInstance> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val app = apps[key(spaceId, packageName)]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr(
                        "空间里没有 $packageName，请先导入 APK",
                        "$packageName is not in the space; import its APK first"
                    )
                )
            val now = System.currentTimeMillis()
            val instance = StoredInstance(app.nextInstanceId(), now, 0L)
            val updated = app.copy(instances = app.instances + instance)
            apps[key(updated)] = updated
            runtimeCoordinator.ensureInstanceDirs(updated.packageName, instance.id)
            val sourcePrivacyMarker = File(
                runtimeCoordinator.dataDirFor(packageName, app.defaultInstance().id),
                "files/privacy_confirmed"
            )
            if (sourcePrivacyMarker.isFile) {
                File(
                    runtimeCoordinator.dataDirFor(packageName, instance.id),
                    "files/privacy_confirmed"
                ).createNewFile()
            }
            runCatching { store.save(apps.values) }
                .onFailure { Log.w(TAG, "save state failed", it) }
            publish()
            EngineResult.Ok(
                VirtualInstance(
                    id = instance.id,
                    createdAt = instance.createdAt,
                    lastLaunchedAt = instance.lastLaunchedAt,
                    dataDir = runtimeCoordinator.dataDirFor(
                        packageName,
                        instance.id
                    ).absolutePath,
                    environmentVersion = instance.environmentVersion,
                    lastProbeResult = instance.lastProbeResult
                )
            )
        }
    }

    override suspend fun deleteInstance(
        spaceId: String,
        packageName: String,
        instanceId: String
    ): EngineResult<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val key = key(spaceId, packageName)
            val app = apps[key]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr("空间里没有 $packageName", "$packageName is not in the space")
                )
            val instance = app.instances.firstOrNull { it.id == instanceId }
                ?: return@withLock EngineResult.Error(
                    "INSTANCE_NOT_FOUND",
                    AppLanguage.tr("找不到实例 $instanceId", "Instance $instanceId not found")
                )
            if (app.instances.size <= 1) {
                return@withLock EngineResult.Error(
                    "LAST_INSTANCE",
                    AppLanguage.tr(
                        "这是 $packageName 的最后一个实例；请从应用管理卸载应用。",
                        "This is the last $packageName instance; uninstall the app from App manager."
                    )
                )
            }

            val updated = app.copy(
                instances = app.instances.filterNot { it.id == instance.id }
            )
            apps[key] = updated
            try {
                store.save(apps.values)
            } catch (t: Throwable) {
                apps[key] = app
                return@withLock EngineResult.Error(
                    "DELETE_INSTANCE_FAILED",
                    AppLanguage.tr(
                        "保存状态失败：${t.message ?: t.javaClass.simpleName}",
                        "Failed to save state: ${t.message ?: t.javaClass.simpleName}"
                    )
                )
            }

            runtimeCoordinator.closeInstanceRuntime(packageName, instance.id)
            runtimeCoordinator.dataDirFor(packageName, instance.id).deleteRecursively()
            ContainerContext.externalRootFor(context, packageName, instance.id)
                ?.deleteRecursively()
            ContainerContext.clearInstancePrefs(context, packageName, instance.id)
            ContainerPermissionStore.clear(context, packageName, instance.id)
            DynamicShortcutStore.clear(
                File(context.filesDir, "vspace/shortcuts"),
                packageName,
                instance.id
            )
            ContainerCrashStore.clear(context, packageName, instance.id)
            publish()
            EngineResult.Ok(Unit)
        }
    }

    override suspend fun clearInstanceData(
        spaceId: String,
        packageName: String,
        instanceId: String
    ): EngineResult<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val app = apps[key(spaceId, packageName)]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr("空间里没有 $packageName", "$packageName is not in the space")
                )
            val instance = app.instances.firstOrNull { it.id == instanceId }
                ?: return@withLock EngineResult.Error(
                    "INSTANCE_NOT_FOUND",
                    AppLanguage.tr("找不到实例 $instanceId", "Instance $instanceId not found")
                )

            runtimeCoordinator.closeInstanceRuntime(packageName, instance.id)
            runtimeCoordinator.dataDirFor(packageName, instance.id).deleteRecursively()
            runtimeCoordinator.ensureInstanceDirs(packageName, instance.id)
            ContainerContext.externalRootFor(context, packageName, instance.id)
                ?.deleteRecursively()
            ContainerContext.clearInstancePrefs(context, packageName, instance.id)
            ContainerPermissionStore.clear(context, packageName, instance.id)
            DynamicShortcutStore.clear(
                File(context.filesDir, "vspace/shortcuts"),
                packageName,
                instance.id
            )
            ContainerCrashStore.clear(context, packageName, instance.id)
            publish()
            EngineResult.Ok(Unit)
        }
    }

    override suspend fun multiOpen(
        spaceId: String,
        packageName: String,
        headless: Boolean,
        backgroundOnly: Boolean
    ): EngineResult<LaunchInfo> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val app = apps[key(spaceId, packageName)]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr(
                        "空间里没有 $packageName，请先导入 APK",
                        "$packageName is not in the space; import its APK first"
                    )
                )
            val now = System.currentTimeMillis()
            val instance = StoredInstance(app.nextInstanceId(), now, 0L)
            val updated = app.copy(instances = app.instances + instance)
            apps[key(updated)] = updated
            runtimeCoordinator.ensureInstanceDirs(updated.packageName, instance.id)
            runCatching { store.save(apps.values) }
                .onFailure { Log.w(TAG, "save state failed", it) }
            publish()
            val result = appLauncher.launch(
                updated,
                instance.id,
                headless,
                backgroundOnly
            )
            if (result is EngineResult.Error) {
                // Do not leave an instance that never launched behind.
                apps[key(updated)] = app
                runCatching { store.save(apps.values) }
                    .onFailure { Log.w(TAG, "rollback instance failed", it) }
                runtimeCoordinator.dataDirFor(packageName, instance.id)
                    .deleteRecursively()
                ContainerContext.externalRootFor(context, packageName, instance.id)
                    ?.deleteRecursively()
                publish()
            }
            result
        }
    }

    override suspend fun shortcuts(
        spaceId: String,
        packageName: String
    ): List<AppShortcut> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val app = apps[key(spaceId, packageName)] ?: return@withLock emptyList()
            val static = AppShortcutLoader.load(
                context = context,
                apkPath = app.baseApkPath,
                packageName = app.packageName,
                launcherActivity = app.launcherActivity,
                cacheKey = "${app.baseApkPath}@${app.updatedAt}"
            )
            val storeRoot = File(context.filesDir, "vspace/shortcuts")
            val dynamic = DynamicShortcutStore.load(
                root = storeRoot,
                packageName = app.packageName,
                instanceId = app.defaultInstance().id
            ).asSequence()
                .filter { it.enabled }
                .mapNotNull { record ->
                    val intent = runCatching {
                        Intent.parseUri(record.intentUri, Intent.URI_INTENT_SCHEME)
                    }.getOrNull() ?: return@mapNotNull null
                    AppShortcut(
                        id = "dynamic:${record.id}",
                        label = record.label,
                        icon = null,
                        targetActivity = record.targetActivity
                            ?: intent.component?.className
                            ?: app.launcherActivity,
                        action = intent.action,
                        data = intent.dataString,
                        mimeType = intent.type,
                        categories = intent.categories?.toList().orEmpty(),
                        extras = intent.extras ?: Bundle(),
                        intentUri = record.intentUri
                    )
                }
                .toList()
            (dynamic + static).distinctBy { it.id }.take(8)
        }
    }

    override suspend fun launchShortcut(
        spaceId: String,
        packageName: String,
        instanceId: String?,
        shortcut: AppShortcut,
        headless: Boolean,
        backgroundOnly: Boolean
    ): EngineResult<LaunchInfo> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val app = apps[key(spaceId, packageName)]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr(
                        "空间里没有 $packageName，请先导入 APK",
                        "$packageName is not in the space; import its APK first"
                    )
                )
            val instance = if (instanceId.isNullOrBlank()) {
                app.defaultInstance()
            } else {
                app.instances.firstOrNull { it.id == instanceId }
                    ?: return@withLock EngineResult.Error(
                        "INSTANCE_NOT_FOUND",
                        AppLanguage.tr("找不到实例 $instanceId", "Instance $instanceId not found")
                    )
            }
            appLauncher.launch(
                app = app,
                instanceId = instance.id,
                headless = headless,
                backgroundOnly = backgroundOnly,
                requestedActivity = shortcut.targetActivity,
                requestedIntent = shortcut.toContainerIntent(packageName)
            )
        }
    }

    override suspend fun stop(spaceId: String, packageName: String): EngineResult<Unit> =
        withContext(Dispatchers.IO) {
            // StageAActivity state is per slot process; this engine runs in the
            // main process. Close every assigned process for the package instead.
            val slots = ContainerProcessPool.runningSlots(context)
                .filter { it.packageName == packageName }
                .map { it.slot }
                .toSet()
            apps[key(spaceId, packageName)]?.instances?.forEach { instance ->
                ContainerProcessPool.slotFor(packageName, instance.id)?.let { slot ->
                    ContainerUiKeepAlive.unbind(context, slot)
                }
            }
            ContainerProcessPool.closePackage(context, packageName)
            // Killing a slot process does not remove its ActivityRecord/Task;
            // an orphaned headless host can later reappear as a second small
            // window. Finish the tasks explicitly as well.
            slots.forEach { runtimeCoordinator.finishPreviousHostTasks(it) }
            StageAActivity.finishIfShowing(packageName)
            EngineResult.Ok(Unit)
        }

    override suspend fun uninstall(
        spaceId: String,
        packageName: String
    ): EngineResult<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val key = key(spaceId, packageName)
            val app = apps[key]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr("空间里没有 $packageName", "$packageName is not in the space")
                )
            apps.remove(key)
            try {
                store.save(apps.values)
            } catch (t: Throwable) {
                apps[key] = app
                return@withLock EngineResult.Error(
                    "UNINSTALL_FAILED",
                    AppLanguage.tr(
                    "保存状态失败：${t.message ?: t.javaClass.simpleName}",
                    "Failed to save state: ${t.message ?: t.javaClass.simpleName}"
                )
                )
            }

            // Kill main and subprocess sessions before deleting APK/data so a
            // live target cannot keep running against removed files.
            app.instances.forEach { instance ->
                ContainerProcessPool.slotFor(app.packageName, instance.id)?.let { slot ->
                    ContainerUiKeepAlive.unbind(context, slot)
                }
                ContainerProcessPool.forgetPrefix(
                    context,
                    "${app.packageName}@${instance.id}"
                )
            }
            StageAActivity.finishIfShowing(packageName)
            val apkDir = File(app.baseApkPath).parentFile
            apkDir?.deleteRecursively()
            apkDir?.parentFile?.listFiles()
                ?.takeIf { it.isEmpty() }
                ?.let { apkDir.parentFile?.delete() }
            runtimeCoordinator.dataDirFor(packageName).deleteRecursively()
            ContainerContext.clearPackagePrefs(context, packageName)
            DynamicShortcutStore.clearPackage(
                File(context.filesDir, "vspace/shortcuts"),
                packageName
            )
            ContainerPermissionStore.clearPackage(context, packageName)
            app.instances.forEach { instance ->
                ContainerContext.externalRootFor(context, packageName, instance.id)
                    ?.deleteRecursively()
            }
            publish()
            EngineResult.Ok(Unit)
        }
    }

    private fun installLocked(spaceId: String, apkUris: List<String>): EngineResult<VirtualApp> {
        if (apkUris.isEmpty()) {
            return EngineResult.Error(
                "INVALID_ARGUMENT",
                AppLanguage.tr("没有选择 APK", "No APK selected")
            )
        }
        val workDir = File(tempDir, UUID.randomUUID().toString().replace("-", ""))
            .apply { mkdirs() }
        var targetDir: File? = null
        try {
            val staged = apkInputStager.stage(workDir, apkUris, "input")
            val copied = staged.files
            val existing = staged.descriptors
                .map { it.packageName }
                .distinct()
                .singleOrNull()
                ?.let { apps[key(spaceId, it)] }
            val install = ApkInstallInspector.validate(staged, existing)
            val packageName = install.packageName
            val base = install.base
            val splits = install.splits
            val signature = install.signatureSha256
            val pendingDigestsByPath = install.pendingDigestsByPath
            val pendingDigests = install.pendingDigests

            if (existing != null &&
                existing.versionCode == base.versionCode &&
                existing.versionName == base.versionName &&
                (
                    existing.apkIndex?.matchesPayload(pendingDigests)
                        ?: ApkPayloadIdentity.same(
                            pending = copied,
                            installed = listOf(File(existing.baseApkPath)) +
                                existing.splitApkPaths.map(::File)
                        )
                    )
            ) {
                Log.d(TAG, "same APK already installed: $packageName")
                return EngineResult.Ok(installedApps.toPublic(existing))
            }

            if (existing == null) {
                // State has no record for this package; stale APK/data from an
                // interrupted install must not leak into the new install.
                File(appsDir, safePathSegment(packageName)).deleteRecursively()
                runtimeCoordinator.dataDirFor(packageName).deleteRecursively()
            }

            val installId = "${System.currentTimeMillis()}-${
                UUID.randomUUID().toString().replace("-", "").take(8)
            }"
            val packageDir = File(appsDir, safePathSegment(packageName))
            val dir = File(packageDir, installId).apply { mkdirs() }
            targetDir = dir
            val finalBase = File(dir, "base.apk")
            moveApkFile(
                File(base.apkPath),
                finalBase,
                pendingDigestsByPath.getValue(base.apkPath)
            )
            val finalSplits = splits.mapIndexed { index, descriptor ->
                File(dir, "split-$index.apk").also {
                    moveApkFile(
                        File(descriptor.apkPath),
                        it,
                        pendingDigestsByPath.getValue(descriptor.apkPath)
                    )
                }
            }
            // Android 14+ refuses to open a writable dex file. Installed APKs
            // are immutable payloads, so mark them read-only before launch.
            (listOf(finalBase) + finalSplits).forEach {
                if (!it.setReadOnly()) {
                    throw ApkInstallException(
                        "INSTALL_FAILED",
                        AppLanguage.tr(
                        "无法将 APK 设为只读：${it.absolutePath}",
                        "Could not make the APK read-only: ${it.absolutePath}"
                    )
                    )
                }
            }

            base.applicationInfo.sourceDir = finalBase.absolutePath
            base.applicationInfo.publicSourceDir = finalBase.absolutePath
            base.packageInfo.applicationInfo = base.applicationInfo
            val installed = base.copy(apkPath = finalBase.absolutePath)
            if (installed.packageName != packageName) {
                throw ApkInstallException("INSTALL_FAILED", AppLanguage.tr("安装后包名不一致", "Package name changed after install"))
            }
            val installedSignature = installed.signatureSha256?.takeIf { it.isNotBlank() }
                ?: throw ApkInstallException("SIGNATURE_INVALID", AppLanguage.tr("安装后无法读取签名", "Could not read the signature after install"))
            if (installedSignature != signature) {
                throw ApkInstallException("SIGNATURE_MISMATCH", AppLanguage.tr("安装后签名校验失败", "Signature verification failed after install"))
            }
            val finalFiles = listOf(finalBase) + finalSplits
            val sourcePaths = listOf(base.apkPath) + splits.map { it.apkPath }
            val nativeAbis = staged.descriptors.flatMap { it.nativeAbis }.toSet()
            val apkIndex = ApkInstallIndex(
                schemaVersion = APK_INDEX_SCHEMA_VERSION,
                files = finalFiles.zip(sourcePaths).map { (file, source) ->
                    ApkFileFingerprint(
                        path = file.absolutePath,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        sha256 = pendingDigestsByPath.getValue(source)
                    )
                },
                nativeAbis = nativeAbis,
                descriptorCachePath = ApkMetadataCache.descriptorPath(
                    finalBase.absolutePath
                )
            )
            ApkMetadataCache.write(installed, apkIndex)

            val now = System.currentTimeMillis()
            val instances = existing?.instances?.takeIf { it.isNotEmpty() }
                ?: listOf(StoredInstance("instance-1", now, 0L))
            instances.forEach {
                runtimeCoordinator.ensureInstanceDirs(packageName, it.id)
            }

            val record = StoredApp(
                spaceId = spaceId,
                packageName = packageName,
                label = installed.label,
                versionName = installed.versionName,
                versionCode = installed.versionCode,
                launcherActivity = installed.launcherActivity
                    ?: throw ApkInstallException("NO_LAUNCHER_ACTIVITY", AppLanguage.tr("找不到入口 Activity", "Launcher activity not found")),
                baseApkPath = finalBase.absolutePath,
                splitApkPaths = finalSplits.map { it.absolutePath },
                signatureSha256 = installedSignature,
                hasNativeLibs = nativeAbis.isNotEmpty(),
                installedAt = existing?.installedAt ?: now,
                updatedAt = now,
                instances = instances,
                apkIndex = apkIndex
            )
            apps[key(record)] = record
            try {
                store.save(apps.values)
            } catch (t: Throwable) {
                apps.remove(key(record))
                throw ApkInstallException(
                    "INSTALL_FAILED",
                    AppLanguage.tr(
                    "保存安装状态失败：${t.message ?: t.javaClass.simpleName}",
                    "Failed to save install state: ${t.message ?: t.javaClass.simpleName}"
                )
                )
            }

            // A replaced APK must not be served by the old container process:
            // its ClassLoader and native libraries still point at the deleted
            // files. Killing the slot also lets the next launch pick up new
            // code and resources.
            instances.forEach { instance ->
                ContainerProcessPool.forgetPrefix(context, "$packageName@${instance.id}")
            }

            if (existing != null) {
                val oldDir = File(existing.baseApkPath).parentFile
                if (oldDir != null && oldDir.absolutePath != dir.absolutePath) {
                    oldDir.deleteRecursively()
                    oldDir.parentFile?.listFiles()
                        ?.takeIf { it.isEmpty() }
                        ?.let { oldDir.parentFile?.delete() }
                }
            }
            publish()
            return EngineResult.Ok(installedApps.toPublic(record))
        } catch (t: ApkInstallException) {
            targetDir?.deleteRecursively()
            return EngineResult.Error(t.code, t.message ?: t.code)
        } catch (t: Throwable) {
            targetDir?.deleteRecursively()
            Log.w(TAG, "install failed", t)
            return EngineResult.Error(
                "INSTALL_FAILED",
                t.message ?: t.javaClass.simpleName
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        rootDir.mkdirs()
        var changed = false
        store.load().forEach { stored ->
            val baseOk = File(stored.baseApkPath).isFile
            val splitsOk = stored.splitApkPaths.all { File(it).isFile }
            if (!baseOk || !splitsOk) {
                changed = true
                return@forEach
            }
            val app = if (stored.instances.isEmpty()) {
                changed = true
                stored.copy(
                    instances = listOf(
                        StoredInstance("instance-1", System.currentTimeMillis(), 0L)
                    )
                )
            } else {
                stored
            }
            // Migrate records created before the Android 14 read-only rule.
            File(app.baseApkPath).setReadOnly()
            app.splitApkPaths.forEach { File(it).setReadOnly() }
            apps[key(app)] = app
            app.instances.forEach {
                runtimeCoordinator.ensureInstanceDirs(app.packageName, it.id)
            }
        }
        if (changed) {
            runCatching { store.save(apps.values) }
                .onFailure { Log.w(TAG, "normalize state failed", it) }
        }
        loaded = true
        publish()
    }

    private fun publish() {
        _virtualApps.value = installedApps.allPublicApps(apps.values)
    }

    private fun key(app: StoredApp): String = key(app.spaceId, app.packageName)

    private fun key(spaceId: String, packageName: String): String =
        "$spaceId::$packageName"

    companion object {
        private const val TAG = "MiniContainerEngine"
    }
}
