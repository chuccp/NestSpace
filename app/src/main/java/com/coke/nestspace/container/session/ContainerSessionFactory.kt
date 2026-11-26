package com.coke.nestspace.container.session

import com.coke.nestspace.container.*
import com.coke.nestspace.BuildConfig
import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.container.identity.ProcessNameCompat
import com.coke.nestspace.container.identity.SystemServiceIdentity
import com.coke.nestspace.container.intent.ContainerIntentResolver
import com.coke.nestspace.container.loader.CompleteClassBridge
import com.coke.nestspace.container.loader.ContainerClassLoader
import com.coke.nestspace.container.loader.NativeLibs
import com.coke.nestspace.container.loader.PluginClassPathResolver
import com.coke.nestspace.container.loader.SharedLibraries
import com.coke.nestspace.container.loader.withContainerClassLoader
import com.coke.nestspace.container.application.ExternalApplicationBootstrap
import com.coke.nestspace.container.application.RuntimeNativeLibraries
import com.coke.nestspace.container.application.toRuntimeApplicationSpec
import com.coke.nestspace.runtime.plugin.RuntimeProfiles
import com.coke.nestspace.container.account.VirtualAccountManager
import com.coke.nestspace.container.runtime.NativeEnvironmentBridge
import com.coke.nestspace.container.runtime.AndroidStartupCoordinator
import com.coke.nestspace.container.runtime.AndroidStartupPhase
import com.coke.nestspace.container.runtime.RuntimeCapabilityProbe
import com.coke.nestspace.container.runtime.RuntimeProcessSentinel
import com.coke.nestspace.container.runtime.VirtualEnvironmentFactory
import com.coke.nestspace.container.runtime.VirtualEnvironmentRegistry
import com.coke.nestspace.container.runtime.VirtualIdentitySnapshot
import com.coke.nestspace.container.runtime.VirtualEnvironmentFacts
import com.coke.nestspace.container.runtime.VirtualEnvironmentValidator
import com.coke.nestspace.container.runtime.VirtualStorageProbe
import com.coke.nestspace.container.runtime.VirtualStorageLayout
import com.coke.nestspace.container.runtime.VirtualInstallLayoutFactory
import com.coke.nestspace.container.runtime.VirtualWebViewDataDirectory
import com.coke.nestspace.container.vpm.VirtualPackageRegistry
import com.coke.nestspace.container.session.HostProcessState
import com.coke.nestspace.container.session.TargetClassPathBinder
import com.coke.nestspace.container.session.TargetApplicationBootstrap
import com.coke.nestspace.container.session.TargetApplicationPublisher
import com.coke.nestspace.container.session.TargetResourcesFactory
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dalvik.system.PathClassLoader
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.webkit.WebView
import java.io.File

private fun installOptionalCoreFileBridge() {
    if (NativeEnvironmentBridge.readPropertyForDiagnostics(
            "debug.nestspace.core_file_bridge"
        ) != "1"
    ) {
        return
    }
    NativeEnvironmentBridge.installCoreFileBridge()
}

internal object ContainerSessionFactory {
        fun create(host: Context, spec: ContainerLaunchSpec): ContainerSession {
            ContainerLaunchProgress.begin()
            var processSentinel: RuntimeProcessSentinel.Session? = null
            val startupCoordinator = AndroidStartupCoordinator(
                observer = { phase ->
                    Log.d(
                        TAG,
                        "startup phase=${phase.phase} outcome=${phase.outcome} " +
                            "durationMs=${phase.durationMillis} " +
                            "detail=${phase.detail.orEmpty()} " +
                            "failure=${phase.failure.orEmpty()}"
                    )
                    ContainerLaunchProgress.phaseCompleted(phase.phase)
                    processSentinel?.markPhase(
                        "${phase.phase}:${phase.outcome}"
                    )
                },
                phaseStartedObserver = { phase ->
                    ContainerLaunchProgress.phaseStarted(phase)
                    processSentinel?.markPhase(phase.name)
                }
            )
            if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.dexfile_trace"
                ) == "1"
            ) {
                NativeEnvironmentBridge.installDexFileTrace()
            }
            val classLoaderStartedAt = SystemClock.elapsedRealtime()
            HiddenApiCompat.ensure()
            TargetApplicationPublisher.applyTargetRuntimeIdentity(spec)
            val hostProcessState = HostProcessState.capture(
                hostResources = HostIdentity.resources
                    ?: runCatching { host.resources }.getOrNull(),
                hostApplication = HostIdentity.application
                    ?: host.applicationContext as? Application,
                hostApplicationInfo = HostIdentity.application?.applicationInfo
            )
            TargetCrashGuard.install()
            ProcessNameCompat.setPersistent(spec.targetProcessName)
            Log.d(TAG, "create session ${spec.packageName}/${spec.instanceId}")
            val dataRoot = File(spec.dataDir).apply { mkdirs() }
            processSentinel = RuntimeProcessSentinel.begin(
                dataRoot = dataRoot.absolutePath,
                packageName = spec.packageName,
                processName = spec.targetProcessName
            )
            File(dataRoot, "code_cache").apply { mkdirs() }
            val apkPaths = listOf(spec.baseApkPath) + spec.splitApkPaths
            val runtimeNativeLibraries = RuntimeNativeLibraries.prepare(
                apkPaths,
                dataRoot
            )
            val sharedLibraryPaths = SharedLibraries.classPathEntries(
                host.applicationContext,
                apkPaths
            )
            val baseClassPath = (apkPaths + sharedLibraryPaths)
                .joinToString(File.pathSeparator)
            val pluginMount = if (spec.targetProcessName != spec.packageName) {
                PluginClassPathResolver.resolveForComponent(
                    dataRoot = dataRoot,
                    className = spec.launcherActivity,
                    supportedAbis = android.os.Build.SUPPORTED_ABIS.toList()
                )
            } else {
                PluginClassPathResolver.Mount.EMPTY
            }
            val externalApplicationName = spec.applicationInfo.className
            val externalApplication =
                ExternalApplicationBootstrap.isExternalApplication(spec)
            val useDynamicExternalLoader =
                NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.external_dynamic_loader"
                ) == "1"
            // Keep the platform loader as parent and the target APK first so
            // hardened startup code sees the same class path shape as a
            // directly installed app.
            val selectedClassPath = if (externalApplication) {
                apkPaths
            } else {
                listOf(baseClassPath)
            }
            val classPath = (selectedClassPath + pluginMount.apkPaths)
                .filter { it.isNotBlank() }
                .joinToString(File.pathSeparator)
            val preferredNativeAbi = NativeLibs.preferredHostAbi(host)
            val declaredNativeAbis = spec.nativeAbis
            val detectedNativeAbis = NativeLibs.abisOf(apkPaths)
            val nativeAbis =
                if (NativeLibs.supportedAbi(
                        declaredNativeAbis,
                        preferredNativeAbi
                    ) != null
                ) {
                    declaredNativeAbis
                } else {
                    detectedNativeAbis
                }
            val nativeAbi = NativeLibs.supportedAbi(nativeAbis, preferredNativeAbi)
            Log.d(
                TAG,
                "native abi declared=${declaredNativeAbis.sorted()} " +
                    "detected=${detectedNativeAbis.sorted()} " +
                    "selected=$nativeAbi"
            )
            val nativeCacheRoot = File(
                host.applicationContext.filesDir,
                "vspace/native-cache"
            )
            val legacyNativeLibs = File(dataRoot, "native-libs")
            val installedNativeLibs = spec.installedNativeLibraryDir
                ?.let(::File)
                ?.takeIf { dir ->
                    dir.isDirectory &&
                        dir.listFiles()?.any {
                            it.isFile && it.name.endsWith(".so") && it.canRead()
                        } == true
                }
            val sharedNativeLibs = NativeLibs.extractIfNeeded(
                apkPaths,
                nativeAbis,
                nativeCacheRoot,
                emptySet(),
                preferredNativeAbi
            )
            val nativeLibs = installedNativeLibs
                ?: sharedNativeLibs
                ?: legacyNativeLibs.takeIf { dir ->
                dir.listFiles()?.any { it.isFile && it.name.endsWith(".so") } == true
            }
            if (sharedNativeLibs != null) {
                // The current session has not loaded a library yet, so the old
                // per-instance copy can be reclaimed safely.
                runCatching { legacyNativeLibs.deleteRecursively() }
            }
            Log.d(TAG, "native libs: ${nativeLibs?.absolutePath} abi=$nativeAbi")
            val installLayout = VirtualInstallLayoutFactory.create(
                spec,
                actualNativeLibraryDir = nativeLibs?.absolutePath,
                nativeAbi = nativeAbi
            ).let { layout ->
                layout.copy(
                    virtualNativeLibraryDir =
                        spec.installedNativeLibraryDir
                            ?: layout.virtualNativeLibraryDir
                )
            }
            val runtimeNativeLibs = nativeLibs
            val exposedNativeLibraryDir =
                installLayout.virtualNativeLibraryDir ?: runtimeNativeLibs?.absolutePath
            val activityThread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread")
                .invoke(null)
            val appInfo = spec.applicationInfo
            appInfo.dataDir = "/data/user/0/${spec.packageName}"
            appInfo.sourceDir = spec.baseApkPath
            appInfo.publicSourceDir = spec.baseApkPath
            appInfo.nativeLibraryDir = exposedNativeLibraryDir
            appInfo.uid = spec.targetUid.takeIf { it > 0 }
                ?: android.os.Process.myUid()
            TargetApplicationPublisher.publishApplicationMetadata(
                activityThread,
                appInfo,
                spec.packageName
            )

            val installedNativeSearchDirectories =
                listOfNotNull(runtimeNativeLibs?.absolutePath) +
                    if (externalApplication) {
                        listOfNotNull(
                            nativeAbi?.let {
                                "${spec.baseApkPath}!/lib/$it"
                            }
                        )
                    } else {
                        runtimeNativeLibraries.directories
                            .map(File::getAbsolutePath)
                    }
            val nativeSearchPath = (
                installedNativeSearchDirectories +
                    pluginMount.nativeLibraryDirs
                )
                .distinct()
                .joinToString(File.pathSeparator)
                .ifBlank { null }
            // JNA resolves libraries through java.library.path and
            // jna.library.path instead of ClassLoader.findLibrary(). Publish
            // the same target-native search path so libraries loaded through
            // JNA follow normal Android native-library semantics too.
            nativeSearchPath?.let { path ->
                val existing = System.getProperty("jna.library.path").orEmpty()
                System.setProperty(
                    "jna.library.path",
                    listOf(path, existing)
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString(File.pathSeparator)
                )
            }
            var loader: ClassLoader = if (externalApplication) {
                val classLoaderParent =
                    TargetClassPathBinder.bootstrapClassLoader(
                        HostIdentity.application?.classLoader
                    )
                if (useDynamicExternalLoader) {
                    PathClassLoader(
                        classPath,
                        nativeSearchPath,
                        classLoaderParent
                    )
                } else {
                    TargetClassPathBinder.createExternalApplicationClassLoader(
                        classPath,
                        nativeSearchPath,
                        classLoaderParent,
                        appInfo.targetSdkVersion,
                        appInfo.dataDir,
                        sharedLibraryPaths
                    )
                }
            } else {
                ContainerClassLoader(
                    classPath,
                    nativeSearchPath,
                    host.classLoader
                )
            }
            Log.d(
                TAG,
                "initial class loader=${loader.javaClass.name} " +
                    "external=$externalApplication"
            )
            ContainerLaunchProgress.advance(LaunchProgressStage.LOADING_CODE)
            appInfo.dataDir = "/data/user/0/${spec.packageName}"
            appInfo.sourceDir = installLayout.virtualBaseApkPath
            appInfo.publicSourceDir = installLayout.virtualBaseApkPath
            appInfo.uid = spec.targetUid.takeIf { it > 0 }
                ?: android.os.Process.myUid()
            // Slot activities opt out of predictive back in the host manifest.
            // If the target ApplicationInfo keeps enableOnBackInvokedCallback
            // enabled, ViewRootImpl's compat callback re-injects back key
            // events into itself until the stack overflows.
            runCatching {
                appInfo.javaClass
                    .getMethod(
                        "setEnableOnBackInvokedCallback",
                        Boolean::class.javaPrimitiveType
                    )
                    .invoke(appInfo, false)
            }.onFailure { first ->
                runCatching {
                    val field = appInfo.javaClass.getField("privateFlagsExt")
                    val flag = appInfo.javaClass
                        .getField("PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK")
                        .getInt(null)
                    field.setInt(appInfo, field.getInt(appInfo) and flag.inv())
                }.onFailure {
                    Log.w(TAG, "disable target OnBackInvoked failed", first)
                }
            }
            if (nativeAbi != null) {
                // ApplicationPackageManager normally fills this in from the
                // installed APK. Dynamic native loaders
                // read it through reflection and expect a non-null value.
                runCatching {
                    val field = appInfo.javaClass.getField("primaryCpuAbi")
                    field.isAccessible = true
                    field.set(appInfo, nativeAbi)
                }
            }
            if (runtimeNativeLibs != null) {
                appInfo.nativeLibraryDir = exposedNativeLibraryDir
            }
            runCatching {
                appInfo.javaClass
                    .getField("splitSourceDirs")
                    .set(
                        appInfo,
                        spec.splitApkPaths
                            .takeIf { it.isNotEmpty() }
                            ?.toTypedArray()
                    )
            }
            // Some packers expose their decrypted runtime DEX through the
            // AppComponentFactory hook that Android normally calls while
            // creating LoadedApk. Manually attached virtual applications skip
            // that framework path, so run the hook before attaching the target.
            NativeEnvironmentBridge.installPathMapping(
                instanceId = spec.instanceId,
                packageName = spec.packageName,
                hostPackageName = HostIdentity.packageName,
                dataRoot = dataRoot.absolutePath,
                externalDataRoot = VirtualStorageLayout
                    .externalInstanceRoot(spec.packageName, spec.instanceId)
                    .absolutePath,
                processName = spec.targetProcessName,
                hostBaseApkPath = HostIdentity.baseApkPath,
                hostNativeLibraryDir = HostIdentity.nativeLibraryDir,
                nativeLibraryDir = runtimeNativeLibs?.absolutePath,
                virtualNativeLibraryDir = installLayout.virtualNativeLibraryDir,
                runtimeLibraryDirs = runtimeNativeLibraries.directories
                    .map(File::getAbsolutePath),
                runtimeLibraryPaths = runtimeNativeLibraries.files
                    .map(File::getAbsolutePath),
                actualBaseApkPath = spec.baseApkPath,
                virtualBaseApkPath = installLayout.virtualBaseApkPath,
                actualSplitApkPaths = spec.splitApkPaths,
                virtualSplitApkPaths = installLayout.virtualSplitApkPaths
            )
            installOptionalCoreFileBridge()
            loader = withContainerClassLoader(loader) {
                ContainerCompat.installRuntimeClassLoader(
                    spec,
                    loader,
                    appInfo,
                    runtimeNativeLibs?.absolutePath
                )
            }
            // Packer AppComponentFactory implementations can mutate the
            // shared ActivityThread ApplicationInfo while installing their
            // runtime loader. Republish the imported APK snapshot afterwards;
            // otherwise AppCompat reopens a system-installed /data/app path
            // that does not exist in this process.
            appInfo.sourceDir = installLayout.virtualBaseApkPath
            appInfo.publicSourceDir = installLayout.virtualBaseApkPath
            TargetApplicationPublisher.publishApplicationMetadata(
                activityThread,
                appInfo,
                spec.packageName
            )
            NativeLibs.addNativeSearchPaths(
                loader,
                installedNativeSearchDirectories +
                    pluginMount.nativeLibraryDirs
            )
            if (!externalApplication) {
                TargetClassPathBinder.exposeVirtualClassPath(loader, installLayout)
            }
            // Some native packers replace the VM hidden-API policy while
            // installing their runtime loader. Re-apply the exemption before
            // the Activity attachment path uses framework-private fields.
            HiddenApiCompat.ensure(force = true)
            Log.d(TAG, "session class loader: ${loader.javaClass.name}")
            val installedResources = runCatching {
                val installedInfo = host.packageManager.getApplicationInfo(
                    spec.packageName,
                    0
                )
                if (installedInfo.sourceDir == spec.baseApkPath) {
                    host.packageManager.getResourcesForApplication(
                        spec.packageName
                    )
                } else {
                    null
                }
            }.getOrNull()
            val resources = installedResources?.also {
                Log.d(TAG, "using matching package resources for ${spec.packageName}")
            } ?: TargetResourcesFactory.create(
                host,
                apkPaths + pluginMount.apkPaths,
                loader
            )
            ContainerLaunchProgress.advance(LaunchProgressStage.LOADING_RESOURCES)
            Log.d(TAG, "resources ready classLoader=${loader.javaClass.simpleName}")
            startupCoordinator.mark(
                AndroidStartupPhase.CLASS_LOADER,
                SystemClock.elapsedRealtime() - classLoaderStartedAt,
                detail = "loader=${loader.javaClass.simpleName} nativeLibs=${nativeLibs != null}"
            )

            val context = ContainerContext(
                base = host.applicationContext,
                targetPackage = spec.packageName,
                instanceId = spec.instanceId,
                dataRoot = dataRoot,
                targetClassLoader = loader,
                targetResources = resources,
                targetAppInfo = appInfo,
                targetRequestedPermissions = spec.packageInfo.requestedPermissions,
                baseApkPath = installLayout.virtualBaseApkPath,
                resourceApkPath = spec.baseApkPath,
                splitApkPaths = spec.splitApkPaths
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val suffix = VirtualWebViewDataDirectory.suffix(
                    spec.packageName,
                    spec.instanceId,
                    spec.targetProcessName
                )
                runCatching { WebView.setDataDirectorySuffix(suffix) }
                    .onSuccess {
                        Log.d(
                            TAG,
                            "WebView data directory suffix configured process=${spec.targetProcessName}"
                        )
                    }
                    .onFailure {
                        Log.w(TAG, "WebView data directory suffix failed", it)
                    }
            }
            // Application.attachBaseContext runs before Application.onCreate and
            // may query package metadata. Publish the virtual PackageManager
            // before attaching so that code sees the target APK's version,
            // permissions and components rather than the host package's.
            ContainerLaunchProgress.advance(LaunchProgressStage.PREPARING_IDENTITY)
            val intents = ContainerIntentResolver(
                packageName = spec.packageName,
                launcherActivity = spec.launcherActivity,
                activities = spec.activities,
                services = spec.services,
                receivers = spec.receivers,
                providers = spec.providers,
                activityFilters = spec.activityFilters,
                serviceFilters = spec.serviceFilters,
                receiverFilters = spec.receiverFilters,
                contentResolver = {
                    context.targetContentResolver ?: host.applicationContext.contentResolver
                }
            )
            val virtualPackages = VirtualPackageRegistry(
                context = host.applicationContext,
                currentPackage = spec.packageName,
                currentInstanceId = spec.instanceId,
                spaceId = spec.spaceId,
                contentResolver = {
                    context.targetContentResolver ?: host.applicationContext.contentResolver
                }
            )
            context.virtualRegistry = virtualPackages
            context.permissions.attachRegistry(virtualPackages)
            // Install the identity proxies before attaching the target
            // Application: the PackageManager handed to it must already be an
            // ApplicationPackageManager with mPM pointing at the container's
            // IPackageManager (some security SDKs read that field from JNI).
            if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.disable_system_service_identity"
                ) != "1"
            ) {
                SystemServiceIdentity.install(
                    spec = spec,
                    hostPackage = HostIdentity.packageName,
                    contexts = listOf(host.applicationContext, context),
                    targetResources = resources,
                    registry = virtualPackages,
                    permissions = context.permissions
                )
            }
            val virtualPackageManager = ContainerPackageManager(
                host.applicationContext,
                spec,
                resources,
                intents,
                virtualPackages,
                context.permissions
            )
            // The IPackageManager proxy routes component/intent queries
            // through the virtual PackageManager wrapper.
            SystemServiceIdentity.attachPackageManager(virtualPackageManager)
            val serviceContexts = listOf(host.applicationContext, context)
            val accountBasicVerified = VirtualAccountManager.install(
                contexts = serviceContexts,
                targetPackage = spec.packageName,
                instanceId = spec.instanceId
            )

            val storageProbe = VirtualStorageProbe.probe(
                context,
                dataRoot,
                spec.packageName,
                spec.instanceId
            )
            val capabilityReport = RuntimeCapabilityProbe.probe(
                availableSubprocessSlots = ContainerProcessPool.slotLimit(host.applicationContext),
                storage = storageProbe,
                accountBasicVerified = accountBasicVerified,
                pluginClassPathMounted = pluginMount.mounted
            )
            val virtualEnvironment = VirtualEnvironmentFactory.create(
                spec = spec,
                nativeLibraryDir = runtimeNativeLibs?.absolutePath,
                runtimeCapabilities = capabilityReport,
                installLayout = installLayout,
                packageGids = spec.packageGids
            )
            context.virtualEnvironment = virtualEnvironment
            VirtualEnvironmentRegistry.install(virtualEnvironment)
            appInfo.uid = virtualEnvironment.observedUid
            if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.disable_native_environment"
                ) != "1"
            ) {
                NativeEnvironmentBridge.install(
                    virtualEnvironment,
                    VirtualEnvironmentRegistry.identitySnapshot(),
                    HostIdentity.packageName,
                    HostIdentity.baseApkPath,
                    HostIdentity.nativeLibraryDir,
                    runtimeNativeLibraries.directories.map(File::getAbsolutePath),
                    runtimeNativeLibraries.files.map(File::getAbsolutePath)
                )
                installOptionalCoreFileBridge()
            }
            // Install the synchronized identity notification filter before
            // the target Application creates its worker threads. Installing
            // it after attachBaseContext can fail TSYNC once target threads
            // have installed different seccomp filters of their own.
            if (!externalApplication &&
                NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.disable_native_environment"
                ) != "1" &&
                NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.syscall_gateway"
                ) == "1"
            ) {
                NativeEnvironmentBridge.installSyscallGateway()
            }
            if (com.coke.nestspace.BuildConfig.DEBUG) {
                runCatching {
                    File(dataRoot, "native-maps.txt").writeText(
                        NativeEnvironmentBridge.readVirtualProcForDiagnostics(
                            "/proc/self/maps"
                        ).orEmpty()
                    )
                    File(dataRoot, "native-status.txt").writeText(
                        NativeEnvironmentBridge.readVirtualProcForDiagnostics(
                            "/proc/self/status"
                        ).orEmpty()
                    )
                }
            }
            // Packer checks run during Application class loading. Install the
            // process guard before attachBaseContext, not only before the
            // first target Activity is created.
            NativeEnvironmentBridge.setPackerGuard(
                NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.runtime_table_guard"
                ) == "1"
            )
            NativeEnvironmentBridge.installCrashTrace(spec.dataDir)
            if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.dexfile_trace"
                ) == "1"
            ) {
                NativeEnvironmentBridge.installDexFileTrace()
            }
            NativeEnvironmentBridge.snapshot()?.let { native ->
                Log.d(
                    TAG,
                    "native environment configured hostUid=${native.configuredHostUid} " +
                        "observedUid=${native.configuredObservedUid} " +
                        "processUid=${native.processUid} pageSize=${native.pageSize}"
                )
            }
            Log.d(
                TAG,
                "virtual environment installed package=${virtualEnvironment.packageName} " +
                    "instance=${virtualEnvironment.instanceId} " +
                    "hostUid=${virtualEnvironment.hostUid} " +
                    "observedUid=${virtualEnvironment.observedUid} " +
                    "nativeLibs=${virtualEnvironment.nativeLibraryDir != null} " +
                    "capabilities=${virtualEnvironment.capabilities.joinToString(",")}"
            )
            capabilityReport.issues.forEach { Log.w(TAG, "runtime capability: $it") }

            // Hand target code a real ApplicationPackageManager: some security
            // SDKs read its mPM field from JNI and reject arbitrary
            // PackageManager implementations.
            val packageManager = host.applicationContext.packageManager
            context.targetPackageManager = packageManager
            // Application.attachBaseContext may read Settings.Secure before
            // providers are installed. Publish the virtual resolver first so
            // device SDKs see this instance's Android ID on their first read.
            context.targetContentResolver = VirtualContentResolver(
                host.applicationContext,
                host.applicationContext.contentResolver,
                spec.providers
                    .flatMap { provider ->
                        provider.authority
                            ?.split(';')
                            .orEmpty()
                            .filter(String::isNotBlank)
                    }
                    .toSet(),
                spec.packageName,
                spec.instanceId
            )
            VirtualSettingsCache.seedAndroidId(context)
            (context.targetContentResolver as? VirtualContentResolver)
                ?.virtualAndroidId
                ?.takeIf { it.isNotBlank() }
                ?.let { androidId ->
                    VirtualEnvironmentRegistry.publishIdentity(
                        virtualEnvironment,
                        VirtualIdentitySnapshot(
                            revision = 1,
                            androidId = androidId,
                            clientUdid = null,
                            deviceId = null,
                            installId = null,
                            source = "Settings.Secure overlay"
                        )
                    )
                    NativeEnvironmentBridge.publishIdentity(
                        VirtualEnvironmentRegistry.identitySnapshot()
                    )
                    Log.d(TAG, "virtual identity revision=1 androidId=present")
                }

            // Framework resource loaders open sourceDir natively and do not
            // consistently pass through the path hooks. Publish the actual
            // imported snapshot before Application.attachBaseContext; a
            // synthetic /data/app path makes WebView and AppCompat resource
            // initialization fail before the first Activity is created.
            appInfo.sourceDir = spec.baseApkPath
            appInfo.publicSourceDir = spec.baseApkPath
            appInfo.nativeLibraryDir = exposedNativeLibraryDir
            runCatching {
                appInfo.javaClass
                    .getField("splitSourceDirs")
                    .set(
                        appInfo,
                        installLayout.virtualSplitApkPaths
                            .takeIf { it.isNotEmpty() }
                            ?.toTypedArray()
                    )
            }
            // Native runtime loaders may run package checks in JNI_OnLoad
            // while the target Application class is being loaded. Publish the
            // virtual identity first, otherwise those checks see the host
            // package and abort with NameNotFoundException.
            TargetApplicationPublisher.publishVirtualIdentity(
                activityThread,
                context,
                loader
            )
            if (com.coke.nestspace.BuildConfig.DEBUG) {
                com.coke.nestspace.container.runtime.RuntimeEnvironmentProbe.log(
                    "after-virtual-identity",
                    context
                )
            }
            TargetWebViewIdentityHook.install(context)
            val runtimeSpec = spec.toRuntimeApplicationSpec()
            val runtimeProfile = RuntimeProfiles.resolve(
                runtimeSpec,
                loader
            )
            Log.d(
                TAG,
                "runtime profile resolved=${runtimeProfile?.id ?: "none"} " +
                    "package=${runtimeSpec.getPackageName()} " +
                    "version=${runtimeSpec.getVersionCode()}"
            )
            runtimeProfile?.beforeApplicationAttach(
                runtimeSpec,
                loader,
                context
            )
            ContainerLaunchProgress.advance(LaunchProgressStage.ATTACHING_APPLICATION)
            val applicationAttachStartedAt = SystemClock.elapsedRealtime()
            context.earlyReceiverRegistry = ContainerEarlyReceiverRegistry()
            val attachedApplication = ProcessNameCompat.withProcessName(spec.targetProcessName) {
                withContainerClassLoader(loader) {
                    TargetApplicationBootstrap.attach(
                        spec,
                        loader,
                        context,
                        host.applicationContext
                    )
                }
            }
            if (externalApplication &&
                NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.force_syscall_gateway"
                ) != "1"
            ) {
                // External bootstrap wrappers inspect seccomp and inline-hook
                // state before unpacking their code.
                Log.d(TAG, "syscall gateway skipped for external bootstrap")
            }
            val application = if (externalApplication) {
                ExternalApplicationBootstrap.lastRealApplication
                    ?: attachedApplication
            } else {
                attachedApplication
            }
            if (externalApplication) {
                CompleteClassBridge.prepare(loader)
                if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                        "debug.nestspace.complete_field_resolver"
                    ) == "1"
                ) {
                    NativeEnvironmentBridge.installCompleteStaticFieldResolver()
                }
                if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                        "debug.nestspace.dump_runtime_code"
                    ) == "1"
                ) {
                    NativeEnvironmentBridge.dumpRuntimeCode(spec.dataDir)
                }
            }
            if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.native_field_fallback"
                ) in setOf("1", "trace", "value") ||
                NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.runtime_field_hook"
                ) == "1"
            ) {
                NativeEnvironmentBridge.installLateJniCompatibility()
            }
            startupCoordinator.mark(
                AndroidStartupPhase.APPLICATION_ATTACH,
                SystemClock.elapsedRealtime() - applicationAttachStartedAt,
                detail = "application=${application.javaClass.simpleName}"
            )
            Log.d(TAG, "application attached: ${application.javaClass.name}")
            val deviceProtectedContext = context.createDeviceProtectedStorageContext()
            val environmentValidation = VirtualEnvironmentValidator.validate(
                virtualEnvironment,
                VirtualEnvironmentFacts(
                    contextPackageName = context.packageName,
                    applicationPackageName = application.packageName,
                    applicationUid = application.applicationInfo.uid,
                    processUid = android.os.Process.myUid(),
                    processPid = android.os.Process.myPid(),
                    contextDataDir = context.dataDir.absolutePath,
                    applicationDataDir = application.applicationInfo.dataDir,
                    contextIsDeviceProtected = deviceProtectedContext.isDeviceProtectedStorage,
                    deviceProtectedDataDir = deviceProtectedContext.dataDir.absolutePath,
                    nativeSnapshot = NativeEnvironmentBridge.snapshot()
                )
            )
            if (environmentValidation.isConsistent) {
                Log.d(TAG, "virtual environment validation passed")
            } else {
                environmentValidation.issues.forEach { issue ->
                    Log.w(TAG, "virtual environment ${issue.severity} ${issue.code}: ${issue.message}")
                }
            }

            return ProcessNameCompat.withProcessName(spec.targetProcessName) {
                withContainerClassLoader(loader) {
                    ContainerSession(
                        spec = spec,
                        loader = loader,
                        resources = resources,
                        context = context,
                        application = application,
                        bootstrapApplication = attachedApplication.takeIf {
                            it !== application
                        },
                        hostContext = host.applicationContext,
                        activityThread = activityThread,
                        hostProcessState = hostProcessState,
                        virtualEnvironment = virtualEnvironment,
                        runtimeProfile = runtimeProfile,
                        processSentinel = requireNotNull(processSentinel),
                        startupCoordinator = startupCoordinator,
                        environmentValidation = environmentValidation,
                        intents = intents,
                        packageManager = virtualPackageManager,
                        virtualPackages = virtualPackages
                    ).also {
                        it.patchHostContextPackageManager(host.applicationContext)
                        it.patchHostContextPackageManager(host)
                        it.initialize()
                    }
                }
            }
        }

    private const val TAG = "ContainerSession"
}
