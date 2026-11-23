package com.coke.nestspace.container

import com.coke.nestspace.BuildConfig
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.runtime.RuntimeApplicationSpec
import com.coke.nestspace.runtime.RuntimeHostHooks
import com.coke.nestspace.runtime.webUrlFromViewIntent
import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.container.identity.ContainerIdentity
import com.coke.nestspace.container.identity.ProcessNameCompat
import com.coke.nestspace.container.identity.SystemServiceIdentity
import com.coke.nestspace.container.intent.ContainerIntentResolver
import com.coke.nestspace.container.job.ContainerJobScheduler
import com.coke.nestspace.container.loader.ContainerClassLoader
import com.coke.nestspace.container.loader.ApkClassIndex
import com.coke.nestspace.container.loader.CompleteClassBridge
import com.coke.nestspace.container.loader.NativeLibs
import com.coke.nestspace.container.loader.PluginClassPathResolver
import com.coke.nestspace.container.loader.RuntimeDexPathClassLoader
import com.coke.nestspace.container.loader.SharedLibraries
import com.coke.nestspace.container.loader.withContainerClassLoader
import com.coke.nestspace.container.remote.ContainerProcessBrokerClient
import com.coke.nestspace.container.application.TargetApplicationFactory
import com.coke.nestspace.container.application.ExternalApplicationBootstrap
import com.coke.nestspace.container.application.RuntimeNativeLibraries
import com.coke.nestspace.container.application.toRuntimeApplicationSpec
import com.coke.nestspace.runtime.plugin.RuntimeProfile
import com.coke.nestspace.runtime.plugin.RuntimeProfiles
import com.coke.nestspace.container.account.VirtualAccountManager
import com.coke.nestspace.container.runtime.NativeEnvironmentBridge
import com.coke.nestspace.container.runtime.AndroidStartupCoordinator
import com.coke.nestspace.container.runtime.AndroidStartupPhase
import com.coke.nestspace.container.runtime.AndroidStartupReport
import com.coke.nestspace.container.runtime.RuntimeCapabilityProbe
import com.coke.nestspace.container.runtime.RuntimeBootGraphRecovery
import com.coke.nestspace.container.runtime.RuntimeDiagnostics
import com.coke.nestspace.container.runtime.RuntimeProcessSentinel
import com.coke.nestspace.container.runtime.VirtualEnvironment
import com.coke.nestspace.container.runtime.VirtualEnvironmentFactory
import com.coke.nestspace.container.runtime.VirtualEnvironmentRegistry
import com.coke.nestspace.container.runtime.VirtualIdentitySnapshot
import com.coke.nestspace.container.runtime.VirtualEnvironmentFacts
import com.coke.nestspace.container.runtime.VirtualEnvironmentValidator
import com.coke.nestspace.container.runtime.EnvironmentValidation
import com.coke.nestspace.container.runtime.VirtualStorageProbe
import com.coke.nestspace.container.runtime.VirtualInstallLayout
import com.coke.nestspace.container.runtime.VirtualInstallLayoutFactory
import com.coke.nestspace.container.runtime.VirtualWebViewDataDirectory
import com.coke.nestspace.container.vpm.VirtualPackageRegistry


import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import dalvik.system.DexClassLoader
import dalvik.system.PathClassLoader
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.webkit.WebView
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ActivityThread is process-global, but a slot process alternates between
 * NestSpace's host Activities and one virtual app. Keep the host fields so
 * they can be restored before the framework creates another host Activity.
 */
private class HostProcessState(
    private val bound: Any?,
    private val boundAppInfo: ApplicationInfo?,
    private val loadedApk: Any?,
    private val loadedApkAppInfo: ApplicationInfo?,
    private val loadedApkPackageName: String?,
    private val loadedApkDataDir: String?,
    private val hostResources: Resources?,
    private val loadedApkResources: Resources?,
    private val loadedApkResDir: String?,
    private val hostApplication: Application?,
    private val initialApplication: Application?
) {
    val description: String =
        "bound=${boundAppInfo?.packageName} loadedApk=$loadedApkPackageName " +
            "dataDir=$loadedApkDataDir initial=${initialApplication?.applicationInfo?.packageName}"

    fun attachHostFrameworkState(activity: Activity, restoreTheme: Boolean) {
        val resources = HostIdentity.resources ?: hostResources ?: loadedApkResources
        if (resources != null) {
            setField(activity, "mResources", resources)
            var context: Context? = activity.baseContext
            while (context != null) {
                setField(context, "mResources", resources)
                context = (context as? ContextWrapper)?.baseContext
            }
        }
        hostApplication?.let { setField(activity, "mApplication", it) }
        if (restoreTheme && resources != null) {
            val activityInfo = runCatching {
                var type: Class<*>? = activity.javaClass
                while (type != null) {
                    val field = runCatching {
                        type!!.getDeclaredField("mActivityInfo")
                    }.getOrNull()
                    if (field != null) {
                        field.isAccessible = true
                        return@runCatching field.get(activity) as? ActivityInfo
                    }
                    type = type.superclass
                }
                null
            }.getOrNull()
            val configuredTheme = activityInfo?.theme ?: 0
            val themeResource = configuredTheme.takeIf {
                it != 0 && runCatching { resources.getResourceName(it) }.isSuccess
            } ?: com.coke.nestspace.R.style.Theme_Virtual_Container
            activity.setTheme(themeResource)
            val activeResources = runCatching { activity.resources }.getOrNull()
            Log.d(
                "ContainerSession",
                "restored host Activity state theme=$themeResource " +
                    "package=${runCatching { resources.getResourcePackageName(themeResource) }
                        .getOrDefault("?")} " +
                    "hostSnapshot=${resources === HostIdentity.resources} " +
                    "activityPackage=${activeResources?.let {
                        runCatching { it.getResourcePackageName(themeResource) }
                            .getOrDefault("?")
                    } ?: "missing"}"
            )
        }
    }

    private fun setField(target: Any, name: String, value: Any) {
        runCatching {
            var type: Class<*>? = target.javaClass
            while (type != null) {
                val field = runCatching { type!!.getDeclaredField(name) }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    field.set(target, value)
                    return
                }
                type = type.superclass
            }
        }.onFailure {
            Log.w("ContainerSession", "restore host Activity field $name failed", it)
        }
    }

    fun restore() {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        val bindData = bound
        if (bindData != null) {
            boundAppInfo?.let { value ->
                runCatching {
                    bindData.javaClass.getDeclaredField("appInfo")
                        .apply { isAccessible = true }
                        .set(bindData, value)
                }
            }
        }
        val apk = loadedApk
        if (apk != null) {
            loadedApkAppInfo?.let { value ->
                runCatching {
                    apk.javaClass.getDeclaredField("mApplicationInfo")
                        .apply { isAccessible = true }
                        .set(apk, value)
                }
            }
            loadedApkPackageName?.let { value ->
                runCatching {
                    apk.javaClass.getDeclaredField("mPackageName")
                        .apply { isAccessible = true }
                        .set(apk, value)
                }
            }
            loadedApkDataDir?.let { value ->
                runCatching {
                    apk.javaClass.getDeclaredField("mDataDir")
                        .apply { isAccessible = true }
                        .set(apk, value)
                }
            }
            loadedApkResources?.let { value ->
                runCatching {
                    apk.javaClass.getDeclaredField("mResources")
                        .apply { isAccessible = true }
                        .set(apk, value)
                }
            }
            loadedApkResDir?.let { value ->
                runCatching {
                    apk.javaClass.getDeclaredField("mResDir")
                        .apply { isAccessible = true }
                        .set(apk, value)
                }
            }
        }
        initialApplication?.let { value ->
            runCatching {
                threadClass.getDeclaredField("mInitialApplication")
                    .apply { isAccessible = true }
                    .set(thread, value)
            }
        }
        seedHostSettingsCache()
    }

    /**
     * PhoneWindow reads Settings.Global in Activity.attach(), after the host
     * base Context has already been created. Prime the process-wide cache with
     * the frozen host Application resolver so those framework reads cannot
     * inherit the target AttributionSource used by the previous target app.
     */
    private fun seedHostSettingsCache() {
        val application = hostApplication ?: return
        runCatching {
            Settings.Global.getString(
                application.contentResolver,
                "render_shadows_in_compositor"
            )
            Settings.Global.getString(
                application.contentResolver,
                "force_resizable_activities"
            )
            Log.d("ContainerSession", "seeded host Settings.Global cache")
        }.onFailure {
            Log.w("ContainerSession", "seed host Settings.Global cache failed", it)
        }
    }

    companion object {
        fun capture(
            hostResources: Resources?,
            hostApplication: Application?,
            hostApplicationInfo: ApplicationInfo?
        ): HostProcessState {
            val hostPackage = HostIdentity.packageName
            val stableHostAppInfo = hostApplication?.applicationInfo
                ?.takeIf { it.packageName == hostPackage }
                ?: hostApplicationInfo?.takeIf { it.packageName == hostPackage }
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            val bound = runCatching {
                threadClass.getDeclaredField("mBoundApplication")
                    .apply { isAccessible = true }
                    .get(thread)
            }.getOrNull()
            val currentBoundAppInfo = runCatching {
                bound?.javaClass?.getDeclaredField("appInfo")
                    ?.apply { isAccessible = true }
                    ?.get(bound) as? ApplicationInfo
            }.getOrNull()
            val boundAppInfo = currentBoundAppInfo
                ?.takeIf { it.packageName == hostPackage }
                ?: stableHostAppInfo
            val loadedApk = runCatching {
                bound?.javaClass?.getDeclaredField("info")
                    ?.apply { isAccessible = true }
                    ?.get(bound)
            }.getOrNull()
            val currentLoadedApkAppInfo = runCatching {
                loadedApk?.javaClass?.getDeclaredField("mApplicationInfo")
                    ?.apply { isAccessible = true }
                    ?.get(loadedApk) as? ApplicationInfo
            }.getOrNull()
            val loadedApkAppInfo = currentLoadedApkAppInfo
                ?.takeIf { it.packageName == hostPackage }
                ?: stableHostAppInfo
            val currentLoadedApkPackageName = runCatching {
                loadedApk?.javaClass?.getDeclaredField("mPackageName")
                    ?.apply { isAccessible = true }
                    ?.get(loadedApk) as? String
            }.getOrNull()
            val loadedApkPackageName = currentLoadedApkPackageName
                ?.takeIf { it == hostPackage }
                ?: hostPackage
            val currentLoadedApkDataDir = runCatching {
                loadedApk?.javaClass?.getDeclaredField("mDataDir")
                    ?.apply { isAccessible = true }
                    ?.get(loadedApk) as? String
            }.getOrNull()
            val loadedApkDataDir = stableHostAppInfo?.dataDir
                ?: currentLoadedApkDataDir
            val loadedApkResources = runCatching {
                loadedApk?.javaClass?.getDeclaredField("mResources")
                    ?.apply { isAccessible = true }
                    ?.get(loadedApk) as? Resources
            }.getOrNull() ?: hostResources
            val loadedApkResDir = runCatching {
                loadedApk?.javaClass?.getDeclaredField("mResDir")
                    ?.apply { isAccessible = true }
                    ?.get(loadedApk) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: hostApplication?.applicationInfo?.sourceDir
            val initialApplication = runCatching {
                threadClass.getDeclaredField("mInitialApplication")
                    .apply { isAccessible = true }
                    .get(thread) as? Application
            }.getOrNull() ?: hostApplication
            Log.d(
                "ContainerSession",
                "captured host process state appInfo=${boundAppInfo?.packageName} " +
                    "loadedApk=$loadedApkPackageName dataDir=$loadedApkDataDir"
            )
            return HostProcessState(
                bound = bound,
                boundAppInfo = boundAppInfo,
                loadedApk = loadedApk,
                loadedApkAppInfo = loadedApkAppInfo,
                loadedApkPackageName = loadedApkPackageName,
                loadedApkDataDir = loadedApkDataDir,
                hostResources = hostResources,
                loadedApkResources = loadedApkResources,
                loadedApkResDir = loadedApkResDir,
                hostApplication = hostApplication,
                initialApplication = initialApplication
            )
        }
    }
}

/**
 * One live virtual-app session: target Application + ClassLoader + Resources.
 *
 * Activity hosting is one real StageAActivity per target Activity. This keeps
 * the host token real, so the target's `finish()` behaves naturally (it closes
 * only its own host and the previous host resumes). `startActivity` inside the
 * target package is intercepted by [ContainerInstrumentation] and turned into
 * another StageAActivity launch backed by this same session.
 */
class ContainerSession private constructor(
    val spec: ContainerLaunchSpec,
    internal val loader: ClassLoader,
    private val resources: Resources,
    val context: ContainerContext,
    val application: Application,
    private val bootstrapApplication: Application?,
    val hostContext: Context,
    private val activityThread: Any,
    private val hostProcessState: HostProcessState,
    val virtualEnvironment: VirtualEnvironment,
    internal val runtimeProfile: RuntimeProfile?,
    private val processSentinel: RuntimeProcessSentinel.Session,
    private val startupCoordinator: AndroidStartupCoordinator,
    private val environmentValidation: EnvironmentValidation,
    val intents: ContainerIntentResolver,
    val packageManager: android.content.pm.PackageManager,
    /** Package-level view of every app installed in this space. */
    val virtualPackages: VirtualPackageRegistry
) {
    val id: String get() = spec.sessionId
    internal val applicationSpec = spec.toRuntimeApplicationSpec()

    /** Run target Activities without keeping the host on screen. */
    @Volatile
    var headless: Boolean = false
    var mirrorDisplayId: Int = -1

    /**
     * Android resolves components through the LoadedApk's current class loader,
     * not the immutable loader captured when the process was attached. Target
     * frameworks can replace that loader during Application.onCreate.
     */
    internal val activeClassLoader: ClassLoader
        get() = context.currentClassLoader()

    val instrumentation: ContainerInstrumentation = ContainerInstrumentation { this }

    val broadcastManager: ContainerBroadcastManager by lazy {
        ContainerBroadcastManager(this)
    }

    private val serviceManager: ContainerServiceManager by lazy {
        ContainerServiceManager(this)
    }

    fun bindServiceDirect(intent: Intent): IBinder? = serviceManager.bindDirect(intent)

    fun unbindServiceDirect(intent: Intent): Boolean = serviceManager.unbindDirect(intent)

    fun startServiceDirect(intent: Intent, foreground: Boolean): android.content.ComponentName? =
        serviceManager.startDirect(intent, foreground)

    fun stopServiceDirect(intent: Intent): Boolean = serviceManager.stopDirect(intent)

    fun queryProviderDirect(
        uri: android.net.Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): ContainerProviderQuery? = providerManager.queryDirect(
        uri,
        projection,
        selection,
        selectionArgs,
        sortOrder
    )

    fun insertProviderDirect(uri: android.net.Uri, values: android.content.ContentValues?):
        android.net.Uri? = providerManager.insertDirect(uri, values)

    fun bulkInsertProviderDirect(
        uri: android.net.Uri,
        values: Array<out android.content.ContentValues>
    ): Int = providerManager.bulkInsertDirect(uri, values)

    fun updateProviderDirect(
        uri: android.net.Uri,
        values: android.content.ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = providerManager.updateDirect(uri, values, selection, selectionArgs)

    fun deleteProviderDirect(
        uri: android.net.Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = providerManager.deleteDirect(uri, selection, selectionArgs)

    fun getProviderTypeDirect(uri: android.net.Uri): String? =
        providerManager.getTypeDirect(uri)

    fun callProviderDirect(
        authority: String,
        method: String,
        arg: String?,
        extras: android.os.Bundle?
    ): android.os.Bundle? = providerManager.callDirect(authority, method, arg, extras)

    fun openProviderFileDirect(
        uri: android.net.Uri,
        mode: String
    ): android.os.ParcelFileDescriptor? =
        providerManager.openFileDirect(uri, mode, null)

    fun openProviderAssetFileDirect(
        uri: android.net.Uri,
        mode: String
    ): android.content.res.AssetFileDescriptor? =
        providerManager.openAssetFileDirect(uri, mode, null)

    fun openProviderTypedAssetFileDirect(
        uri: android.net.Uri,
        mimeTypeFilter: String,
        opts: android.os.Bundle?
    ): android.content.res.AssetFileDescriptor? =
        providerManager.openTypedAssetFileDirect(uri, mimeTypeFilter, opts, null)

    fun deliverRemoteContentChange(uris: Array<android.net.Uri>, flags: Int) {
        VirtualContentObserverRegistry.deliverRemoteChange(uris.toList(), flags)
    }

    fun deliverTopActivityIntent(className: String, intent: Intent): Boolean =
        navigator?.deliverTargetIntent(className, Intent(intent)) == true

    private val providerManager: ContainerProviderManager by lazy {
        ContainerProviderManager(this)
    }

    private val jobScheduler: ContainerJobScheduler by lazy {
        ContainerJobScheduler(this)
    }

    private val targetHosts = ConcurrentHashMap<Activity, StageAActivity>()
    private val onCreateFieldRetry = ThreadLocal<Boolean>()
    private val identityLock = Any()
    private var hostIdentityDepth = 0
    private var hostIdentitySuspended = false
    private var lastNativeHookCount = -1
    private var lastNativeScannedCount = -1
    private var lastNativeTargetModuleCount = -1
    private val nativeRefreshPending = AtomicBoolean(false)
    private val nativeHookExecutor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "nestspace-native-hook-pump").apply { isDaemon = true }
        }
    }
    private var fastNativeHookPump: ScheduledFuture<*>? = null
    private var slowNativeHookPump: ScheduledFuture<*>? = null
    private var publishedLoadedApk: Any? = null

    @Volatile
    var navigator: StageAActivity? = null

    val startupReport: AndroidStartupReport
        get() = startupCoordinator.report()

    init {
        context.virtualEnvironment = virtualEnvironment
        context.targetPackageManager = packageManager
        context.broadcastManager = broadcastManager
        context.jobScheduler = jobScheduler
        context.virtualRegistry = virtualPackages
        context.permissions.attachRegistry(virtualPackages)
        context.navigator = ContainerNavigator { intent, requestCode ->
            val host = navigator ?: return@ContainerNavigator false
            startTargetActivity(host, intent, requestCode)
        }
        context.targetApplication = application
        context.targetTheme = resources.newTheme()
    }

    /**
     * Mirrors Android's startup order: providers are created before
     * Application.onCreate, so libraries that initialize through a provider
     * see the target resolver. Services are created on demand.
     */
    fun initialize() {
        Log.d(TAG, "initialize session ${spec.packageName}")
        context.serviceBridge = serviceManager
        val compatAppLike = ContainerCompat.takeAppLike(application)
        if (compatAppLike != null) {
            startupCoordinator.runOptional(
                AndroidStartupPhase.EMBEDDED_APPLICATION_ATTACH
            ) {
                compatAppLike.javaClass
                    .getMethod("onBaseContextAttached", Context::class.java)
                    .invoke(compatAppLike, context)
            }?.let { Log.w(TAG, "embedded ApplicationLike attach failed", it) }
        } else {
            startupCoordinator.skip(
                AndroidStartupPhase.EMBEDDED_APPLICATION_ATTACH,
                "target does not expose an embedded ApplicationLike"
            )
        }
        TargetCrashGuard.noteSession(spec.packageName, spec.instanceId, hostContext)
        startupCoordinator.run(AndroidStartupPhase.PROCESS_APPLICATION) {
            publishAsProcessApplication()
        }
        // Keep the complete process identity on the target while its
        // Application and providers initialize, matching a normal Android
        // process. StageAActivity temporarily restores the host identity only
        // while the framework creates host components.
        startupCoordinator.run(AndroidStartupPhase.CONTENT_PROVIDERS) {
            providerManager.install()
        }
        startupCoordinator.run(AndroidStartupPhase.APPLICATION_CREATE) {
            (bootstrapApplication ?: application).onCreate()
        }
        publishTargetOpPackage(application)
        refreshNativeHooks()
        startupCoordinator.run(AndroidStartupPhase.APPLICATION_COMPATIBILITY) {
            runtimeProfile?.afterApplicationCreated(
                applicationSpec,
                loader,
                context,
                application
            )
        }
        startupCoordinator.run(AndroidStartupPhase.HOST_BRIDGES) {
            broadcastManager.startHostBridge()
        }
        if (compatAppLike != null) {
            startupCoordinator.runOptional(AndroidStartupPhase.EMBEDDED_APPLICATION_CREATE) {
                compatAppLike.javaClass.getMethod("onCreate").invoke(compatAppLike)
            }?.let { Log.w(TAG, "embedded ApplicationLike onCreate failed", it) }
        } else {
            startupCoordinator.skip(
                AndroidStartupPhase.EMBEDDED_APPLICATION_CREATE,
                "target does not expose an embedded ApplicationLike"
            )
        }
        refreshNativeHooks()
        startupCoordinator.run(AndroidStartupPhase.STARTUP_COMPATIBILITY) {
            runtimeProfile?.afterEmbeddedApplicationCreated(
                applicationSpec,
                loader,
                context,
                application
            )
        }
        if (ExternalApplicationBootstrap.lastRealApplication != null) {
            Log.d(TAG, "boot graph recovery skipped for external bootstrap")
        } else {
            runCatching {
                withContainerClassLoader(loader) {
                    RuntimeBootGraphRecovery.apply(
                        context = context,
                        packageName = spec.packageName,
                        dataRoot = virtualEnvironment.dataRoot,
                        classLoader = loader
                    )
                }
            }.onSuccess { result ->
                if (result != null) {
                    Log.d(
                        TAG,
                        "boot graph recovery applied=${result.applied} " +
                            "skipped=${result.skipped} errors=${result.errors}"
                    )
                }
            }.onFailure {
                Log.w(TAG, "boot graph recovery failed", it)
            }
        }
        // Target Application.onCreate often registers its own uncaught
        // exception handler (crash reporting) that terminates the host process.
        // Re-install the guard so target-thread failures stay contained.
        startupCoordinator.run(AndroidStartupPhase.CRASH_GUARD) {
            TargetCrashGuard.install(force = true)
        }
        if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                "debug.nestspace.clear_native_environment_before_activity"
            ) == "1"
        ) {
            NativeEnvironmentBridge.clear()
            Log.w(TAG, "cleared native environment before first activity")
        }
        startupCoordinator.mark(AndroidStartupPhase.READY, 0L)
        startNativeHookPump()
        Log.d(TAG, "session initialized ${spec.packageName}")
    }

    fun noteFirstActivityScheduled() {
        startupCoordinator.markOnce(AndroidStartupPhase.FIRST_ACTIVITY_SCHEDULED)
    }

    fun noteFirstActivityOpened() {
        startupCoordinator.markOnce(AndroidStartupPhase.FIRST_ACTIVITY_OPENED)
        runCatching {
            nativeHookExecutor.execute {
                refreshNativeHooksSafely(scanElfFallback = false)
                writeRuntimeDiagnostics()
            }
        }.onFailure {
            Log.w(TAG, "post-launch diagnostics scheduling failed", it)
        }
    }

    internal fun refreshNativeHooks(scanElfFallback: Boolean = false) {
        if (!NativeHookTrial.enabled) return
        if (ExternalApplicationBootstrap.lastRealApplication != null) {
            return
        }
        val snapshot = NativeEnvironmentBridge.refreshHooks(scanElfFallback) ?: return
        if (snapshot.hookCount == lastNativeHookCount &&
            snapshot.scannedModuleCount == lastNativeScannedCount &&
            snapshot.targetModuleCount == lastNativeTargetModuleCount
        ) {
            return
        }
        lastNativeHookCount = snapshot.hookCount
        lastNativeScannedCount = snapshot.scannedModuleCount
        lastNativeTargetModuleCount = snapshot.targetModuleCount
        Log.d(
            TAG,
            "native hooks patched=${snapshot.hookCount} " +
                "modules=${snapshot.hookedModuleCount} " +
                "scanned=${snapshot.scannedModuleCount} " +
                "targetModules=${snapshot.targetModuleCount} " +
                "errors=${snapshot.errorCount} " +
                "virtualProcReads=${snapshot.virtualProcReadCount} " +
                "propertyReads=${snapshot.propertyReadCount} " +
                "dlopenCalls=${snapshot.dlopenCallCount}"
        )
    }

    private object NativeHookTrial {
        const val enabled = true
    }

    /** Coalesces lifecycle-triggered refreshes onto the native worker. */
    internal fun scheduleNativeHookRefresh() {
        if (ExternalApplicationBootstrap.lastRealApplication != null) return
        if (!nativeRefreshPending.compareAndSet(false, true)) return
        runCatching {
            nativeHookExecutor.execute {
                try {
                    refreshNativeHooksSafely(scanElfFallback = false)
                } finally {
                    nativeRefreshPending.set(false)
                }
            }
        }.onFailure {
            nativeRefreshPending.set(false)
            Log.w(TAG, "native hook refresh scheduling failed", it)
        }
    }

    private fun startNativeHookPump() {
        if (ExternalApplicationBootstrap.lastRealApplication != null) return
        synchronized(nativeHookExecutor) {
            if (fastNativeHookPump != null || slowNativeHookPump != null) return
            fastNativeHookPump = nativeHookExecutor.scheduleAtFixedRate(
                { refreshNativeHooksSafely(scanElfFallback = false) },
                0L,
                250L,
                TimeUnit.MILLISECONDS
            )
            nativeHookExecutor.schedule(
                {
                    fastNativeHookPump?.cancel(false)
                    fastNativeHookPump = null
                    // The maps/ELF file fallback is intentionally one-shot
                    // after target startup. Running it on every 250 ms hook
                    // refresh reads large native libraries while they are
                    // still being loaded and can stall the whole target.
                    refreshNativeHooksSafely(scanElfFallback = true)
                    synchronized(nativeHookExecutor) {
                        if (slowNativeHookPump == null) {
                            slowNativeHookPump = nativeHookExecutor.scheduleAtFixedRate(
                                { refreshNativeHooksSafely(scanElfFallback = false) },
                                0L,
                                2L,
                                TimeUnit.SECONDS
                            )
                        }
                    }
                },
                NATIVE_ELF_FALLBACK_DELAY_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    private fun refreshNativeHooksSafely(scanElfFallback: Boolean) {
        runCatching { refreshNativeHooks(scanElfFallback) }
            .onFailure { Log.w(TAG, "native hook refresh failed", it) }
    }

    private fun writeRuntimeDiagnostics() {
        runCatching {
            val expectedPathProbe = File(virtualEnvironment.dataRoot, "files").absolutePath
            val pathMapProbe = NativeEnvironmentBridge.rewritePathForDiagnostics(
                "/data/user/0/${virtualEnvironment.packageName}/files"
            )?.takeIf { it == expectedPathProbe }
            val apkPathMapProbe = virtualEnvironment.virtualApkPaths.firstOrNull()
                ?.let(NativeEnvironmentBridge::rewritePathForDiagnostics)
                ?.takeIf { it == virtualEnvironment.apkPaths.firstOrNull() }
            val externalPathMapProbe = virtualEnvironment.externalDataRoot
                ?.let { externalRoot ->
                    val expected = File(externalRoot, "files").absolutePath
                    NativeEnvironmentBridge.rewritePathForDiagnostics(
                        "/storage/emulated/0/Android/data/" +
                            "${virtualEnvironment.packageName}/files"
                    )?.takeIf { it == expected }
                }
            val expectedUid = virtualEnvironment.observedUid.toString()
            val procViewProbe = NativeEnvironmentBridge.readVirtualProcForDiagnostics(
                "/proc/self/status"
            )?.takeIf { value ->
                value.lineSequence().any {
                    it.startsWith("Uid:") &&
                        it.removePrefix("Uid:").trim().split(Regex("\\s+"))
                            .all { field -> field == expectedUid }
                } && value.contains("Name:")
            }
            val propertyValueProbe = NativeEnvironmentBridge
                .readPropertyForDiagnostics("ro.build.version.sdk")
                ?.takeIf { it == android.os.Build.VERSION.SDK_INT.toString() }
            val propertyCallbackProbe = NativeEnvironmentBridge
                .readPropertyCallbackForDiagnostics("ro.build.version.sdk")
                ?.takeIf { it == android.os.Build.VERSION.SDK_INT.toString() }
            val snapshot = RuntimeDiagnostics.build(
                environment = virtualEnvironment,
                identity = VirtualEnvironmentRegistry.identitySnapshot(),
                validation = environmentValidation,
                startup = startupCoordinator.report(),
                native = NativeEnvironmentBridge.snapshot(),
                probe = NativeEnvironmentBridge.probe(),
                processUid = ContainerIdentity.hostUid(),
                processPid = android.os.Process.myPid(),
                headless = headless,
                pathMapProbe = pathMapProbe,
                apkPathMapProbe = apkPathMapProbe,
                externalPathMapProbe = externalPathMapProbe,
                procViewProbe = procViewProbe,
                propertyValueProbe = propertyValueProbe,
                propertyCallbackProbe = propertyCallbackProbe
            )
            val runtimeDir = File(virtualEnvironment.dataRoot, ".nestspace").apply { mkdirs() }
            val fileName = RuntimeDiagnostics.fileName(
                virtualEnvironment.packageName,
                virtualEnvironment.processName
            )
            val destination = File(runtimeDir, fileName)
            val temporary = File(runtimeDir, "$fileName.tmp")
            temporary.writeText(snapshot.toString(2))
            if (!temporary.renameTo(destination)) {
                destination.delete()
                check(temporary.renameTo(destination)) {
                    "cannot publish runtime diagnostics"
                }
            }
            Log.d(
                TAG,
                "runtime diagnostics written capabilities=" +
                    "${virtualEnvironment.capabilities.size} " +
                    "startupMs=${startupCoordinator.report().totalDurationMillis}"
            )
        }.onFailure {
            Log.w(TAG, "write runtime diagnostics failed", it)
        }
    }

    /**
     * publishVirtualIdentity() rewrites the host LoadedApk's package name so
     * native checks during Application class loading see the target. Keeping
     * it afterwards leaks the target name into every host ContextImpl:
     * getExternalFilesDir() then builds target paths that StorageManager
     * refuses to create for the host UID. ContainerContext carries the virtual
     * package identity for target-facing contexts, so restore the host name
     * as soon as the target Application has been attached.
     */
    private fun restoreHostLoadedApkPackageName() {
        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val bound = threadClass.getDeclaredField("mBoundApplication")
                .apply { isAccessible = true }
                .get(activityThread) ?: return
            val loadedApk = bound.javaClass.getDeclaredField("info")
                .apply { isAccessible = true }
                .get(bound) ?: return
            Class.forName("android.app.LoadedApk")
                .getDeclaredField("mPackageName")
                .apply { isAccessible = true }
                .set(loadedApk, HostIdentity.packageName)
            Log.d(TAG, "restored host LoadedApk package name")
        }.onFailure { Log.w(TAG, "restore host LoadedApk package name failed", it) }
    }

    /**
     * A slot process hosts exactly one target app for its whole lifetime, so
     * the process application can be the virtual one. Some target SDKs resolve
     * the process application through `ActivityThread.currentApplication()`
     * and pass its ClassLoader to their split-install machinery. While the
     * field still points at the host Application, those splits are injected
     * into the host PathClassLoader, which has no target base APK, so plugin
     * classes referencing patched base classes fail with
     * NoClassDefFoundError.
     */
    private fun publishAsProcessApplication() {
        val threadClass = Class.forName("android.app.ActivityThread")
        runCatching {
            val field = threadClass.getDeclaredField("mInitialApplication")
            field.isAccessible = true
            field.set(activityThread, application)
        }.onFailure {
            Log.w(TAG, "publish target application failed", it)
        }
        // Security SDKs fingerprint the running package through
        // ActivityThread.mBoundApplication.appInfo: sourceDir, dataDir, package
        // name and uid. That record still points at the host APK, so a target
        // app that verifies its own signature or paths sees NestSpace instead
        // of itself. Keep it aligned with the virtual ApplicationInfo.
        runCatching {
            val boundField = threadClass.getDeclaredField("mBoundApplication")
            boundField.isAccessible = true
            val bound = boundField.get(activityThread) ?: return@runCatching
            val appInfoField = bound.javaClass.getDeclaredField("appInfo")
            appInfoField.isAccessible = true
            appInfoField.set(bound, context.applicationInfo)
            val targetLoadedApk = bound.javaClass.getDeclaredField("info")
                .apply { isAccessible = true }
                .get(bound)
            if (targetLoadedApk != null) {
                context.bindPackageInfo(targetLoadedApk)
                publishedLoadedApk = targetLoadedApk
                if (ExternalApplicationBootstrap.lastRealApplication != null) {
                    applyTargetLoadedApkIdentity(targetLoadedApk, loader)
                    val packages = threadClass.getDeclaredField("mPackages")
                        .apply { isAccessible = true }
                        .get(activityThread)
                    if (packages is MutableMap<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        (packages as MutableMap<String, Any>)[spec.packageName] =
                            java.lang.ref.WeakReference(targetLoadedApk)
                    }
                    Log.d(
                        TAG,
                        "published target LoadedApk application=${application.javaClass.name}"
                    )
                }
            }
        }.onFailure {
            Log.w(TAG, "publish bound application info failed", it)
        }
    }

    private fun createTargetLoadedApk(
        activityThread: Any,
        targetLoader: ClassLoader
    ): Any {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val compatibilityClass = Class.forName(
            "android.content.res.CompatibilityInfo"
        )
        val compatibility = compatibilityClass
            .getField("DEFAULT_COMPATIBILITY_INFO")
            .get(null)
        val loadedApkClass = Class.forName("android.app.LoadedApk")
        val loadedApk = loadedApkClass
            .getDeclaredConstructor(
                activityThreadClass,
                ApplicationInfo::class.java,
                compatibilityClass,
                ClassLoader::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            .apply { isAccessible = true }
            .newInstance(
                activityThread,
                context.applicationInfo,
                compatibility,
                targetLoader,
                false,
                true,
                false
            )
        applyTargetLoadedApkIdentity(loadedApk, targetLoader)
        return loadedApk
    }

    private fun applyTargetLoadedApkIdentity(
        loadedApk: Any,
        targetLoader: ClassLoader
    ) {
        mapOf(
            "mApplication" to (
                ExternalApplicationBootstrap.lastRealApplication ?: application
            ),
            "mApplicationInfo" to context.applicationInfo,
            "mClassLoader" to targetLoader,
            "mDefaultClassLoader" to targetLoader,
            "mPackageName" to spec.packageName,
            "mAppDir" to spec.baseApkPath,
            "mResDir" to spec.baseApkPath,
            "mDataDir" to context.dataDir.absolutePath,
            "mResources" to resources
        ).forEach { (name, value) ->
            runCatching {
                findField(loadedApk.javaClass, name)
                    ?.set(loadedApk, value)
            }.onFailure {
                Log.w(TAG, "initialize target LoadedApk field failed: $name", it)
            }
        }
    }

    /**
     * Some target libraries (SoLoader) resolve PackageManager through a host
     * Context captured by framework/cross-process components. Point those
     * contexts at the virtual PM so target package lookups do not fall through
     * to the host package manager.
     */
    fun patchHostContextPackageManager(context: Context) {
        val implClass = Class.forName("android.app.ContextImpl")
        var current: Context? = context
        while (current != null) {
            if (implClass.isInstance(current)) {
                implClass.declaredFields.forEach { field ->
                    runCatching {
                        field.isAccessible = true
                        if (PackageManager::class.java.isAssignableFrom(field.type)) {
                            field.set(current, packageManager)
                        }
                    }
                }
            }
            current = (current as? ContextWrapper)?.baseContext
        }
    }

    fun destroy() {
        nativeRefreshPending.set(false)
        nativeHookExecutor.shutdownNow()
        fastNativeHookPump = null
        slowNativeHookPump = null
        runCatching { runtimeProfile?.close() }
            .onFailure { Log.w(TAG, "runtime profile cleanup failed", it) }
        NativeEnvironmentBridge.clear()
        VirtualEnvironmentRegistry.clear(virtualEnvironment)
        suspendProcessIdentity()
        broadcastManager.destroy()
        providerManager.destroy()
        serviceManager.destroyAll()
        jobScheduler.shutdown()
        processSentinel.finish("clean-shutdown")
        ProcessNameCompat.restorePersistent()
    }

    internal fun recordRuntimeFailure(stage: String, error: Throwable) {
        processSentinel.markFailure(stage, error)
    }

    /**
     * Host Activities must be created with NestSpace's ActivityInfo. Otherwise
     * the framework reads the virtual app's icon/resource IDs while building
     * the host decor and crashes on the second launch in the same slot.
     */
    fun <T> withHostProcessIdentity(block: () -> T): T {
        synchronized(identityLock) {
            if (hostIdentityDepth++ == 0) {
                suspendProcessIdentityLocked()
            }
        }
        return try {
            block()
        } finally {
            synchronized(identityLock) {
                hostIdentityDepth--
            }
        }
    }

    fun suspendProcessIdentity() {
        synchronized(identityLock) {
            suspendProcessIdentityLocked()
        }
    }

    fun activateProcessIdentity() {
        synchronized(identityLock) {
            activateProcessIdentityLocked()
        }
    }

    fun attachHostFrameworkState(activity: Activity, restoreTheme: Boolean) {
        hostProcessState.attachHostFrameworkState(activity, restoreTheme)
    }

    private fun suspendProcessIdentityLocked() {
        if (hostIdentitySuspended) return
        hostProcessState.restore()
        ProcessNameCompat.restorePersistent()
        hostIdentitySuspended = true
        Log.d(
            TAG,
            "restored host process identity ${hostProcessState.description}"
        )
    }

    private fun activateProcessIdentityLocked() {
        if (!hostIdentitySuspended) return
        ProcessNameCompat.setPersistent(spec.targetProcessName)
        // The target Application may have installed a framework-standard
        // ClassLoader wrapper during onCreate. Re-publishing process identity
        // must not overwrite that wrapper with the base APK loader.
        publishVirtualIdentity(activityThread, context, activeClassLoader)
        publishAsProcessApplication()
        hostIdentitySuspended = false
        Log.d(TAG, "activated target process identity")
    }

    fun createActivity(
        host: StageAActivity,
        className: String?,
        sourceIntent: Intent?,
        savedState: Bundle? = null
    ): Activity = ProcessNameCompat.withProcessName(spec.targetProcessName) {
        withContainerClassLoader(loader) {
            createActivityLocked(host, className, sourceIntent, savedState)
        }
    }

    private fun createActivityLocked(
        host: StageAActivity,
        className: String?,
        sourceIntent: Intent?,
        savedState: Bundle?,
        fallbackActivityClass: String? = null
    ): Activity {
        val info = resolveActivity(className)
            ?: throw IllegalArgumentException(
                AppLanguage.tr(
                    "找不到 Activity：${className ?: spec.launcherActivity}",
                    "Activity not found: ${className ?: spec.launcherActivity}"
                )
            )
        Log.d(
            TAG,
            "resolve activity requested=$className info=${info.name} " +
                "target=${info.targetActivity}"
        )
        if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                "debug.nestspace.crash_trace"
            ) == "1"
        ) {
            NativeEnvironmentBridge.installCrashTrace(spec.dataDir)
        }
        val preparePauseMs = NativeEnvironmentBridge
            .readPropertyForDiagnostics(
                "debug.nestspace.activity_prepare_pause_ms"
            )
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L
        if (preparePauseMs > 0L) {
            Log.w(
                TAG,
                "pausing before target Activity class load for ${preparePauseMs}ms"
            )
            logClassLoaderShape("session", loader)
            application.classLoader?.let {
                logClassLoaderShape("application", it)
            }
            Thread.sleep(preparePauseMs)
        }
        val activityClass = fallbackActivityClass
            ?: (info.targetActivity?.takeIf { it.isNotBlank() } ?: info.name)
        val targetClass = activeClassLoader.loadClass(activityClass)
            .asSubclass(Activity::class.java)
        runtimeProfile?.beforeTargetActivity(
            applicationSpec,
            loader,
            context,
            targetClass
        )
        Log.d(TAG, "activity class=${targetClass.name}")

        val theme = resources.newTheme().apply {
            applyStyle(resolveTheme(info), true)
        }
        val intent = Intent(sourceIntent ?: Intent())
            .setClassName(spec.packageName, info.name)
        launchActivityThroughFramework(
            host = host,
            info = info,
            intent = intent,
            savedState = savedState
        )?.let { target ->
            targetHosts[target] = host
            logJniSurface(target)
            Log.d(
                TAG,
                "target created through ActivityThread ${target.javaClass.name}"
            )
            return target
        }
        val target = targetClass.getDeclaredConstructor().newInstance()

        val attach = Activity::class.java.declaredMethods
            .firstOrNull { it.name == "attach" }
            ?: error(
                "Activity.attach not found: " +
                    "classLoader=${Activity::class.java.classLoader} " +
                    "methods=${Activity::class.java.declaredMethods.size}"
            )
        val activityBase = ContainerUiContext(
            context,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                host.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
            } else {
                android.view.Display.DEFAULT_DISPLAY
            }
        )
        val args = attach.parameterTypes.mapIndexed { index, _ ->
            when (index) {
                0 -> activityBase
                1 -> activityThread
                2 -> instrumentation
                3 -> hostToken(host)
                4 -> 0
                5 -> application
                6 -> intent
                7 -> info
                8 -> spec.label
                9 -> null
                10 -> info.name
                11 -> null
                12 -> resources.configuration
                13 -> null
                14 -> null
                15 -> null
                16 -> null
                else -> null
            }
        }

        attach.isAccessible = true
        try {
            attach.invoke(target, *args.toTypedArray())
        } catch (t: Throwable) {
            val parameterTypes = attach.parameterTypes.joinToString { it.name }
            val argumentTypes = args.joinToString { it?.javaClass?.name ?: "null" }
            Log.e(
                TAG,
                "Activity.attach failed target=${targetClass.name} " +
                    "manifest=${info.name} parameters=$parameterTypes arguments=$argumentTypes",
                t
            )
            throw t
        }
        applyTargetResources(target, resources, theme)
        installVirtualTaskRoot(target)
        targetHosts[target] = host
        logJniSurface(target)
        try {
            instrumentation.callActivityOnCreate(target, savedState)
        } catch (t: Throwable) {
            inspectMissingField(t, loader)
            val continueAfterFieldOperation =
                NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.continue_after_oncreate_field_error"
                ) == "1" &&
                    generateSequence(t) { it.cause }
                        .any { cause ->
                            cause.message?.contains(
                                "AGET_OBJECT",
                                ignoreCase = true
                            ) == true ||
                                cause.message?.contains(
                                    "DoFieldOperate",
                                    ignoreCase = true
                                ) == true
                        }
            if (continueAfterFieldOperation) {
                Log.w(
                    TAG,
                    "continuing target Activity after field operation failure",
                    t
                )
                return target
            }
            val retryAfterFieldFailure =
                onCreateFieldRetry.get() != true &&
                    generateSequence(t) { it.cause }
                        .any { cause ->
                            cause.message?.contains(
                                "DoFieldOperate",
                                ignoreCase = true
                            ) == true ||
                                cause.message?.contains(
                                    "AGET_OBJECT",
                                    ignoreCase = true
                            ) == true
                        }
            // A throwing target onCreate leaves the Activity attached to the
            // host token; without cleanup targetHosts would retain it for the
            // life of the container process.
            targetHosts.remove(target)
            runCatching { instrumentation.callActivityOnDestroy(target) }
                .onFailure { Log.w(TAG, "cleanup failed target destroy", it) }
            if (retryAfterFieldFailure) {
                Log.w(TAG, "retrying target Activity after field failure", t)
                onCreateFieldRetry.set(true)
                try {
                    Log.w(TAG, "retrying target Activity after field operation")
                    Thread.sleep(100L)
                    return createActivityLocked(
                        host,
                        className,
                        sourceIntent,
                        savedState,
                        fallbackActivityClass
                    )
                } finally {
                    onCreateFieldRetry.remove()
                }
            }
            if (fallbackActivityClass == null && info.targetActivity != null) {
                val concreteAliasClass = runCatching {
                    loader.loadClass(info.name)
                        .takeIf { Activity::class.java.isAssignableFrom(it) }
                }.getOrNull()
                if (concreteAliasClass != null &&
                    concreteAliasClass.name != info.targetActivity
                ) {
                    Log.w(
                        TAG,
                        "alias target ${info.targetActivity} failed; " +
                            "retrying concrete alias class ${concreteAliasClass.name}",
                        t
                    )
                    return createActivityLocked(
                        host,
                        info.name,
                        sourceIntent,
                        savedState,
                        concreteAliasClass.name
                    )
                }
            }
            throw t
        }
        val activityCalledField = runCatching {
            Activity::class.java
                .getDeclaredField("mCalled")
                .apply { isAccessible = true }
        }.getOrNull()
        val activityCalled = activityCalledField
            ?.let { runCatching { it.getBoolean(target) }.getOrNull() }
            ?: true
        if (!activityCalled) {
            Log.w(
                TAG,
                "target Activity returned without calling super.onCreate: " +
                    target.javaClass.name
            )
            val postCreatePauseMs = NativeEnvironmentBridge
                .readPropertyForDiagnostics(
                    "debug.nestspace.activity_post_create_pause_ms"
                )
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            if (postCreatePauseMs > 0L) {
                Log.w(
                    TAG,
                    "pausing after target Activity onCreate for ${postCreatePauseMs}ms"
                )
                Thread.sleep(postCreatePauseMs)
            }
            if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.activity_oncreate_recovery"
                ) == "1"
            ) {
                runCatching {
                    Activity::class.java
                        .getDeclaredMethod("onCreate", Bundle::class.java)
                        .apply { isAccessible = true }
                        .invoke(target, savedState)
                }.onFailure {
                    Log.w(TAG, "framework Activity onCreate fallback failed", it)
                }
                runCatching {
                    instrumentation.callActivityOnCreate(target, savedState)
                }.onFailure {
                    Log.w(TAG, "target Activity onCreate retry failed", it)
                }
                if (activityCalledField != null &&
                    !runCatching { activityCalledField.getBoolean(target) }
                        .getOrDefault(true)
                ) {
                    runCatching {
                        activityCalledField.setBoolean(target, true)
                    }
                }
            }
        }
        Log.d(
            TAG,
            "target created ${target.javaClass.name} " +
                "taskRoot=${runCatching { target.isTaskRoot() }.getOrDefault(false)} " +
                "finishing=${target.isFinishing}"
        )
        return target
    }

    private fun inspectMissingField(error: Throwable, loader: ClassLoader) {
        if (error !is NoSuchFieldError ||
            NativeEnvironmentBridge.readPropertyForDiagnostics(
                "debug.nestspace.inspect_missing_fields"
            ) != "1"
        ) {
            return
        }
        val message = error.message.orEmpty()
        val owner = Regex("""class "L([^;]+);"""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace('/', '.')
            ?: return
        val fieldName = Regex("""field "([^"]+)"""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?: return
        val type = Regex("""no "([^"]+)" field""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?: "?"
        val resolved = runCatching { loader.loadClass(owner) }.getOrNull()
        val fields = resolved
            ?.let { runCatching { it.declaredFields.toList() }.getOrNull() }
            .orEmpty()
        Log.e(
            TAG,
            "missing field inspection owner=$owner type=$type name=$fieldName " +
                "classLoader=${resolved?.classLoader?.javaClass?.name}@" +
                "${System.identityHashCode(resolved?.classLoader)} " +
                "fields=${fields.size} present=${fields.any { it.name == fieldName }} " +
                "matching=${fields.filter { it.name == fieldName }.joinToString { it.type.name }}",
            error
        )
    }

    private fun launchActivityThroughFramework(
        host: StageAActivity,
        info: ActivityInfo,
        intent: Intent,
        savedState: Bundle?
    ): Activity? {
        if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                "debug.nestspace.framework_activity_launch"
            ) != "1"
        ) {
            return null
        }
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val thread = activityThreadClass
                .getMethod("currentActivityThread")
                .invoke(null)
            val method = activityThreadClass.declaredMethods.firstOrNull {
                it.name == "startActivityNow" && it.parameterCount == 9
            } ?: error("ActivityThread.startActivityNow not found")
            method.isAccessible = true
            val launchInfo = ActivityInfo(info).apply {
                applicationInfo = context.applicationInfo
            }
            val compatibilityClass = Class.forName(
                "android.content.res.CompatibilityInfo"
            )
            val compatibility = compatibilityClass
                .getField("DEFAULT_COMPATIBILITY_INFO")
                .get(null)
            val loadedApkClass = Class.forName("android.app.LoadedApk")
            val loadedApkConstructor = loadedApkClass
                .getDeclaredConstructor(
                    activityThreadClass,
                    ApplicationInfo::class.java,
                    compatibilityClass,
                    ClassLoader::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                .apply { isAccessible = true }
            val targetLoadedApk = publishedLoadedApk
                ?: loadedApkConstructor.newInstance(
                    thread,
                    context.applicationInfo,
                    compatibility,
                    loader,
                    false,
                    true,
                    false
                ).also {
                    applyTargetLoadedApkIdentity(it, loader)
                    publishedLoadedApk = it
                }
            mapOf(
                "mApplication" to application,
                "mApplicationInfo" to context.applicationInfo,
                "mClassLoader" to context.currentClassLoader(),
                "mPackageName" to spec.packageName,
                "mAppDir" to spec.baseApkPath,
                "mResDir" to spec.baseApkPath,
                "mDataDir" to context.applicationInfo.dataDir,
                "mResources" to resources
            ).forEach { (name, value) ->
                findField(targetLoadedApk.javaClass, name)?.let { field ->
                    runCatching { field.set(targetLoadedApk, value) }
                        .onFailure {
                            Log.w(TAG, "initialize target LoadedApk field failed: $name", it)
                        }
                }
            }
            val packagesField = activityThreadClass
                .getDeclaredField("mPackages")
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val packages = packagesField.get(thread)
                as MutableMap<String, Any>
            packages[spec.packageName] = java.lang.ref.WeakReference(
                targetLoadedApk
            )
            val assistToken = activityTokenField(host, "mAssistToken")
            val shareableToken = activityTokenField(
                host,
                "mShareableActivityToken"
            )
            Log.d(
                TAG,
                "framework activity launch target=${info.name} " +
                    "uid=${launchInfo.applicationInfo.uid} " +
                    "loadedApk=${targetLoadedApk.javaClass.name} " +
                    "parameters=${method.parameterTypes.joinToString { it.name }}"
            )
            method.invoke(
                thread,
                null,
                null,
                intent,
                launchInfo,
                hostToken(host),
                savedState,
                null,
                assistToken,
                shareableToken
            ) as? Activity
        }.onFailure {
            Log.w(TAG, "framework activity launch failed: ${info.name}", it)
        }.getOrNull()
    }

    private fun activityTokenField(
        source: Activity,
        name: String
    ): IBinder? = runCatching {
        Activity::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(source) as? IBinder
    }.getOrNull()

    fun hostFor(target: Activity): StageAActivity? = targetHosts[target]

    fun detach(target: Activity) {
        targetHosts.remove(target)
    }

    fun resolveActivity(className: String?): ActivityInfo? =
        intents.findActivity(className?.takeIf { it.isNotBlank() } ?: spec.launcherActivity)

    /**
     * Called by target Activity.startActivity / Context.startActivity.
     * Returns false for intents outside the target package so the host can
     * launch them normally.
     */
    fun startTargetActivity(
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
            val launch = {
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
            if (sourceTarget != null && host.currentTargetActivity() == null) {
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
                        this@ContainerSession,
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

    private fun launchOnHost(
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

    private fun logJniSurface(activity: Activity) {
        if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                "debug.nestspace.jni_surface_log"
            ) != "1"
        ) {
            return
        }
        var type: Class<*>? = activity.javaClass
        var depth = 0
        while (type != null && depth < 8 && type != Activity::class.java) {
            val nativeMethods = runCatching {
                type?.declaredMethods
                    ?.filter { java.lang.reflect.Modifier.isNative(it.modifiers) }
                    ?.map {
                        it.name + "(" +
                            it.parameterTypes.joinToString(",") { parameter ->
                                parameter.name
                            } +
                            ")"
                    }
                    .orEmpty()
            }.getOrDefault(emptyList())
            Log.d(
                TAG,
                "jni surface class=${type?.name} loader=${type?.classLoader} " +
                    "nativeMethods=$nativeMethods"
            )
            type = type?.superclass
            depth += 1
        }
        Log.d(
            TAG,
            "jni surface targetLoader=${activity.javaClass.classLoader} " +
                "sessionLoader=$loader applicationLoader=${application.javaClass.classLoader}"
        )
    }

    fun startExternalActivity(host: StageAActivity, intent: Intent, requestCode: Int): Boolean {
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

    /**
     * AMS validates the caller package against the process UID. The container
     * process advertises the target package to the app/native layers, so a
     * real host Activity start must temporarily restore the host package
     * identity or AMS rejects it with "package does not belong to uid".
     */
    private fun <T> withHostIdentity(host: StageAActivity, block: () -> T): T {
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

    private fun isSelfLauncher(intent: Intent): Boolean =
        intent.action == Intent.ACTION_MAIN &&
            intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true &&
            intent.`package` == spec.packageName

    private fun resolveTheme(info: ActivityInfo): Int = when {
        info.theme != 0 -> info.theme
        spec.applicationTheme != 0 -> spec.applicationTheme
        else -> android.R.style.Theme_Material_Light
    }

    private fun applyTargetResources(
        activity: Activity,
        targetResources: Resources,
        theme: Resources.Theme
    ) {
        val resourcesField = ContextThemeWrapper::class.java
            .getDeclaredField("mResources")
            .apply { isAccessible = true }
        resourcesField.set(activity, targetResources)

        val themeField = ContextThemeWrapper::class.java
            .getDeclaredField("mTheme")
            .apply { isAccessible = true }
        themeField.set(activity, theme)
    }

    private fun logClassLoaderShape(label: String, candidate: ClassLoader) {
        runCatching {
            val base = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathList = base.getDeclaredField("pathList").apply {
                isAccessible = true
            }.get(candidate)
            val elementsField = Class.forName("dalvik.system.DexPathList")
                .getDeclaredField("dexElements")
                .apply { isAccessible = true }
            val elements = elementsField.get(pathList) as? Array<*>
            Log.d(
                TAG,
                "runtime dex shape $label loaderId=${System.identityHashCode(candidate)} " +
                    "loader=${candidate.javaClass.name} " +
                    "parent=${candidate.parent?.javaClass?.name} " +
                    "pathListId=${System.identityHashCode(pathList)} " +
                    "elements=${elements?.size ?: -1}"
            )
            elements?.forEachIndexed { index, value ->
                if (value == null) {
                    Log.d(TAG, "runtime dex element $label[$index]=null")
                    return@forEachIndexed
                }
                val elementClass = Class.forName(
                    "dalvik.system.DexPathList\$Element"
                )
                val dexFile = runCatching {
                    elementClass.getDeclaredField("dexFile").apply {
                        isAccessible = true
                    }.get(value)
                }.getOrNull()
                val path = runCatching {
                    elementClass.getDeclaredField("path").apply {
                        isAccessible = true
                    }.get(value)
                }.getOrNull()
                val dexFileClass = dexFile?.javaClass
                val fileName = dexFileClass?.declaredFields
                    ?.firstOrNull { it.name == "mFileName" }
                    ?.apply { isAccessible = true }
                    ?.let { runCatching { it.get(dexFile) }.getOrNull() }
                val cookie = dexFileClass?.declaredFields
                    ?.firstOrNull { it.name == "mCookie" }
                    ?.apply { isAccessible = true }
                    ?.let { runCatching { it.get(dexFile) }.getOrNull() }
                val internalCookie = dexFileClass?.declaredFields
                    ?.firstOrNull { it.name == "mInternalCookie" }
                    ?.apply { isAccessible = true }
                    ?.let { runCatching { it.get(dexFile) }.getOrNull() }
                Log.d(
                    TAG,
                    "runtime dex element $label[$index] " +
                        "elementId=${System.identityHashCode(value)} " +
                        "path=$path " +
                        "dexId=${dexFile?.let(System::identityHashCode)} " +
                        "fileName=$fileName " +
                        "cookieId=${cookie?.let(System::identityHashCode)} " +
                        "cookieSize=${(cookie as? LongArray)?.size ?: -1} " +
                        "cookie=${(cookie as? LongArray)?.joinToString(",")} " +
                        "internalCookieId=${internalCookie?.let(System::identityHashCode)} " +
                        "internalCookieSize=${(internalCookie as? LongArray)?.size ?: -1} " +
                        "internalCookie=${(internalCookie as? LongArray)?.joinToString(",")}"
                )
            }
        }.onFailure {
            Log.w(TAG, "runtime dex shape $label unavailable", it)
        }
    }

    /**
     * ActivityThread normally creates the application context with the
     * installed package as mOpPackageName. The container creates it with the
     * host package first so framework services remain valid while the wrapper
     * application initializes. Once the app has completed onCreate, expose the
     * target name to target/native code.
     */
    private fun publishTargetOpPackage(application: Application) {
        runCatching {
            var current: Context = application
            while (current is ContextWrapper) {
                current = current.baseContext
            }
            var type: Class<*>? = current.javaClass
            var field: Field? = null
            while (type != null && field == null) {
                field = runCatching {
                    type!!.getDeclaredField("mOpPackageName").apply {
                        isAccessible = true
                    }
                }.getOrNull()
                type = type.superclass
            }
            val targetField = field ?: return@runCatching
            targetField.set(current, spec.packageName)
            Log.d(
                TAG,
                "published target op package=${spec.packageName}"
            )
        }.onFailure {
            Log.w(TAG, "publish target op package failed", it)
        }
    }

    private fun hostToken(host: Activity): IBinder =
        Activity::class.java
            .getDeclaredField("mToken")
            .apply { isAccessible = true }
            .get(host) as IBinder

    /**
     * A target Activity is attached with the host's real activity token, so
     * framework `Activity.isTaskRoot()` asks about the host record. Launcher
     * entry points deliberately call finish() when they are not the task root
     * (the "don't open a second copy from a notification" path). Inside the
     * container every virtual Activity is the root of its own virtual task, so
     * present that view to the target.
     *
     * Activity.isTaskRoot() delegates to its private WindowControllerCallback;
     * replace only that callback and forward everything else to the original.
     */
    @SuppressLint("BlockedPrivateApi")
    private fun installVirtualTaskRoot(target: Activity) {
        runCatching {
            val field = Activity::class.java
                .getDeclaredField("mWindowControllerCallback")
                .apply { isAccessible = true }
            val original = field.get(target)
            val callbackClass = Class.forName(
                "android.view.Window\$WindowControllerCallback"
            )
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                if (method.name == "isTaskRoot") {
                    true
                } else if (method.declaringClass == Any::class.java) {
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(original)
                        "equals" -> proxyOrSelf(original, args)
                        else -> "VirtualWindowController"
                    }
                } else {
                    runCatching {
                        method.invoke(original, *(args ?: emptyArray()))
                    }.getOrNull()
                }
            }
            field.set(target, proxy)
        }.onFailure { Log.w(TAG, "virtual task-root compat failed", it) }
    }

    private fun proxyOrSelf(original: Any?, args: Array<out Any?>?): Boolean =
        args?.firstOrNull() === original

    companion object {
        private const val NATIVE_ELF_FALLBACK_DELAY_SECONDS = 20L
        private const val TAG = "ContainerSession"


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
            applyTargetRuntimeIdentity(spec)
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
                ExternalApplicationBootstrap.isExternalApplication(
                    externalApplicationName,
                    spec.packageName
                )
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
            val nativeAbis = spec.nativeAbis
            val preferredNativeAbi = NativeLibs.preferredHostAbi(host)
            val nativeAbi = NativeLibs.supportedAbi(nativeAbis, preferredNativeAbi)
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
            publishApplicationMetadata(
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
                if (useDynamicExternalLoader) {
                    RuntimeDexPathClassLoader(
                        classPath,
                        nativeSearchPath,
                        ClassLoader.getSystemClassLoader().parent,
                        dataRoot,
                        File(dataRoot, "code_cache")
                    )
                } else {
                    createExternalApplicationClassLoader(
                        classPath,
                        nativeSearchPath,
                        ClassLoader.getSystemClassLoader().parent,
                        appInfo.targetSdkVersion,
                        appInfo.dataDir,
                        sharedLibraryPaths
                    )
                }
            } else {
                RuntimeDexPathClassLoader(
                    classPath,
                    nativeSearchPath,
                    ClassLoader.getSystemClassLoader().parent,
                    dataRoot,
                    File(dataRoot, "code_cache")
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
            loader = ContainerCompat.installRuntimeClassLoader(spec, loader, appInfo)
            if (!externalApplication) {
                exposeVirtualClassPath(loader, installLayout)
            }
            // Some native packers replace the VM hidden-API policy while
            // installing their runtime loader. Re-apply the exemption before
            // the Activity attachment path uses framework-private fields.
            HiddenApiCompat.ensure(force = true)
            Log.d(TAG, "session class loader: ${loader.javaClass.name}")
            val resources = runCatching {
                host.packageManager.getResourcesForApplication(spec.packageName)
            }.onSuccess {
                Log.d(TAG, "using package resources for ${spec.packageName}")
            }.getOrElse {
                createResources(
                    host,
                    apkPaths + pluginMount.apkPaths,
                    loader
                )
            }
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
                    HostIdentity.application
                        ?.applicationInfo
                        ?.sourceDir
                        .orEmpty(),
                    HostIdentity.application
                        ?.applicationInfo
                        ?.nativeLibraryDir
                        .orEmpty(),
                    runtimeNativeLibraries.directories.map(File::getAbsolutePath),
                    runtimeNativeLibraries.files.map(File::getAbsolutePath)
                )
                NativeEnvironmentBridge.installCoreFileBridge()
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

            // Resources and the runtime class loader are already bound to the
            // imported files. From this point on, expose the stable installed-
            // app path to the target so packers cannot observe a half-virtual
            // package whose code path and PackageManager metadata disagree.
            appInfo.sourceDir = installLayout.virtualBaseApkPath
            appInfo.publicSourceDir = installLayout.virtualBaseApkPath
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
            publishVirtualIdentity(activityThread, context, loader)
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
            val attachedApplication = ProcessNameCompat.withProcessName(spec.targetProcessName) {
                withContainerClassLoader(loader) {
                    attachApplication(
                        spec,
                        loader,
                        context,
                        host.applicationContext,
                        runtimeNativeLibraries
                    )
                }
            }
            // Native storage and platform libraries initialize their own
            // thread pools and file locks in attachBaseContext. Installing a
            // syscall trap before that point can deadlock those initializers
            // on real devices, so enforce direct-syscall identity immediately
            // after the base Application attachment completes.
            if (!externalApplication ||
                NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.force_syscall_gateway"
                ) == "1"
            ) {
                NativeEnvironmentBridge.installSyscallGateway()
            } else {
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
                    processUid = ContainerIdentity.hostUid(),
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

        /**
         * Makes native startup checks see the target package before the target
         * Application class is loaded. Static initializers of hardened apps
         * may call PackageManager from JNI_OnLoad, which runs during class
         * loading and before attachBaseContext.
         */
        @SuppressLint("BlockedPrivateApi")
        private fun publishVirtualIdentity(
            activityThread: Any,
            context: Context,
            loader: ClassLoader
        ) {
            val threadClass = Class.forName("android.app.ActivityThread")
            runCatching {
                val boundField = threadClass.getDeclaredField("mBoundApplication")
                    .apply { isAccessible = true }
                val bound = boundField.get(activityThread) ?: return@runCatching
                val appInfoField = bound.javaClass.getDeclaredField("appInfo")
                    .apply { isAccessible = true }
                appInfoField.set(bound, context.applicationInfo)

                // Some security SDKs read the LoadedApk mirror instead of
                // AppBindData.appInfo.
                val loadedApk = runCatching {
                    bound.javaClass.getDeclaredField("info")
                        .apply { isAccessible = true }
                        .get(bound)
                }.getOrNull()
                if (loadedApk != null) {
                    val loadedApkClass = Class.forName("android.app.LoadedApk")
                    runCatching {
                        loadedApkClass.getDeclaredField("mApplicationInfo")
                            .apply { isAccessible = true }
                            .set(loadedApk, context.applicationInfo)
                    }
                    runCatching {
                        loadedApkClass.getDeclaredField("mPackageName")
                            .apply { isAccessible = true }
                            .set(loadedApk, context.packageName)
                    }
                    runCatching {
                        loadedApkClass.getDeclaredField("mClassLoader")
                            .apply { isAccessible = true }
                            .set(loadedApk, loader)
                    }
                    runCatching {
                        loadedApkClass.getDeclaredField("mDefaultClassLoader")
                            .apply { isAccessible = true }
                            .set(loadedApk, loader)
                    }
                    runCatching {
                        loadedApkClass.getDeclaredField("mDataDir")
                            .apply { isAccessible = true }
                            .set(loadedApk, context.applicationInfo.dataDir)
                    }
                    runCatching {
                        loadedApkClass.getDeclaredField("mAppDir")
                            .apply { isAccessible = true }
                            .set(loadedApk, context.applicationInfo.sourceDir)
                    }
                    runCatching {
                        loadedApkClass.getDeclaredField("mResDir")
                            .apply { isAccessible = true }
                            .set(loadedApk, context.packageResourcePath)
                    }
                }
            }.onFailure { Log.w(TAG, "prepublish appInfo failed", it) }

            runCatching {
                val temp = Application()
                val attachBase = ContextWrapper::class.java
                    .getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }
                attachBase.invoke(temp, context)
                val field = threadClass.getDeclaredField("mInitialApplication")
                    .apply { isAccessible = true }
                field.set(activityThread, temp)
                Log.d(TAG, "prepublished initial application (temp)")
            }.onFailure { Log.w(TAG, "prepublish initial application failed", it) }

            // Native packers also query ActivityThread.getSystemContext()
            // directly. Point its PackageManager/ApplicationInfo at the
            // container so package checks resolve to the target APK.
            runCatching {
                // getSystemContext is an instance method on current AOSP builds.
                val systemContext = threadClass.getMethod("getSystemContext")
                    .invoke(activityThread)
                val packageManager = context.packageManager
                var pmFields = 0
                var appInfoFields = 0
                systemContext.javaClass.declaredFields.forEach { field ->
                    field.isAccessible = true
                    when {
                        PackageManager::class.java.isAssignableFrom(field.type) -> {
                            field.set(systemContext, packageManager)
                            pmFields++
                        }
                        field.type == ApplicationInfo::class.java -> {
                            field.set(systemContext, context.applicationInfo)
                            appInfoFields++
                        }
                    }
                }
                Log.d(
                    TAG,
                    "prepublished system context: pmFields=$pmFields appInfoFields=$appInfoFields"
                )
            }.onFailure { Log.w(TAG, "prepublish system context failed", it) }
        }

        private fun publishApplicationMetadata(
            activityThread: Any,
            appInfo: ApplicationInfo,
            packageName: String
        ) {
            val threadClass = Class.forName("android.app.ActivityThread")
            runCatching {
                val bound = threadClass.getDeclaredField("mBoundApplication")
                    .apply { isAccessible = true }
                    .get(activityThread)
                    ?: return@runCatching
                bound.javaClass.getDeclaredField("appInfo")
                    .apply { isAccessible = true }
                    .set(bound, appInfo)
                val loadedApk = bound.javaClass.getDeclaredField("info")
                    .apply { isAccessible = true }
                    .get(bound)
                    ?: return@runCatching
                val loadedApkClass = Class.forName("android.app.LoadedApk")
                loadedApkClass.getDeclaredField("mApplicationInfo")
                    .apply { isAccessible = true }
                    .set(loadedApk, appInfo)
                loadedApkClass.getDeclaredField("mPackageName")
                    .apply { isAccessible = true }
                    .set(loadedApk, packageName)
                loadedApkClass.getDeclaredField("mDataDir")
                    .apply { isAccessible = true }
                    .set(loadedApk, appInfo.dataDir)
                Log.d(TAG, "published target application metadata before class loader")
            }.onFailure {
                Log.w(TAG, "publish target application metadata failed", it)
            }
        }

        /**
         * Direct installs initialize ART with the target package's target SDK.
         * The host process starts with NestSpace's own value, which also
         * changes linker namespaces and compatibility branches in hardened
         * runtimes. Each container slot hosts one target, so mirror the value
         * before creating its class loader.
         */
        private fun applyTargetRuntimeIdentity(spec: ContainerLaunchSpec) {
            val targetSdk = spec.applicationInfo.targetSdkVersion
            if (targetSdk <= 0) return
            runCatching {
                val type = Class.forName("dalvik.system.VMRuntime")
                val runtime = type.getMethod("getRuntime").invoke(null)
                val getter = type.getMethod("getTargetSdkVersion")
                val previous = getter.invoke(runtime) as Int
                if (previous != targetSdk) {
                    type.getMethod(
                        "setTargetSdkVersion",
                        Int::class.javaPrimitiveType
                    ).invoke(runtime, targetSdk)
                }
                Log.d(
                    TAG,
                    "target SDK version previous=$previous target=$targetSdk"
                )
                runCatching {
                    type.getMethod(
                        "setProcessPackageName",
                        String::class.java
                    ).invoke(runtime, spec.packageName)
                }.onFailure {
                    Log.w(TAG, "target runtime package name failed", it)
                }
                runCatching {
                    type.getMethod(
                        "setProcessDataDirectory",
                        String::class.java
                    ).invoke(runtime, "/data/user/0/${spec.packageName}")
                }.onFailure {
                    Log.w(TAG, "target runtime data directory failed", it)
                }
                Log.d(
                    TAG,
                    "target runtime identity package=${spec.packageName} " +
                        "dataDir=/data/user/0/${spec.packageName}"
                )
            }.onFailure {
                Log.w(TAG, "target SDK version apply failed target=$targetSdk", it)
            }
        }

        /**
         * Recreate the loader shape produced by LoadedApk for an installed app.
         * Shared libraries belong in their own loaders; putting their jars in
         * the application's dex path changes the ART class-loader context and
         * prevents image/OAT reuse.
         */
        private fun createExternalApplicationClassLoader(
            classPath: String,
            nativeSearchPath: String?,
            parent: ClassLoader?,
            targetSdkVersion: Int,
            permittedDataDir: String,
            sharedLibraryPaths: List<String>
        ): ClassLoader = runCatching {
            if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.standard_path_loader"
                ) == "1"
            ) {
                return@runCatching PathClassLoader(
                    classPath,
                    nativeSearchPath,
                    parent
                )
            }
            val applicationLoadersClass = Class.forName(
                "android.app.ApplicationLoaders"
            )
            val applicationLoaders = applicationLoadersClass
                .getMethod("getDefault")
                .invoke(null)
            val createSharedLibrary = applicationLoadersClass
                .getDeclaredMethod(
                    "getSharedLibraryClassLoaderWithSharedLibraries",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    ClassLoader::class.java,
                    String::class.java,
                    List::class.java,
                    List::class.java
                )
                .apply { isAccessible = true }
            val permittedPath = permittedDataDir
                .takeIf { it.isNotBlank() }
                .orEmpty()
            val sharedLibraryLoaders = sharedLibraryPaths.map { path ->
                createSharedLibrary.invoke(
                    applicationLoaders,
                    path,
                    targetSdkVersion,
                    false,
                    nativeSearchPath,
                    permittedPath,
                    null,
                    null,
                    null,
                    null
                ) as ClassLoader
            }
            val createApplicationLoader = applicationLoadersClass
                .getDeclaredMethod(
                    "getClassLoaderWithSharedLibraries",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    ClassLoader::class.java,
                    String::class.java,
                    List::class.java,
                    List::class.java,
                    List::class.java
                )
                .apply { isAccessible = true }
            createApplicationLoader.invoke(
                applicationLoaders,
                classPath,
                targetSdkVersion,
                false,
                nativeSearchPath,
                permittedPath,
                parent,
                null,
                sharedLibraryLoaders,
                null,
                null
            ) as ClassLoader
        }.onFailure {
            Log.w(TAG, "framework class-loader shape failed", it)
        }.getOrElse {
            PathClassLoader(classPath, nativeSearchPath, parent)
        }

        private fun createExternalShellContext(
            sourceContextImpl: Any,
            fallback: Context,
            resources: android.content.res.Resources,
            targetLoader: ClassLoader,
            targetPackageName: String,
            targetApplicationInfo: ApplicationInfo
        ): Context = runCatching {
            val packageInfo = findField(
                sourceContextImpl.javaClass,
                "mPackageInfo"
            )?.get(sourceContextImpl)
                ?: error("ContextImpl package info not found")
            val dataDir = targetApplicationInfo.dataDir
            mapOf(
                "mResources" to resources,
                "mClassLoader" to targetLoader,
                "mDefaultClassLoader" to targetLoader,
                "mApplicationInfo" to targetApplicationInfo,
                "mPackageName" to targetPackageName,
                "mAppDir" to targetApplicationInfo.sourceDir,
                "mResDir" to targetApplicationInfo.sourceDir,
                "mDataDir" to dataDir
            ).forEach { (name, value) ->
                findField(packageInfo.javaClass, name)?.set(packageInfo, value)
            }
            val contextImplClass = Class.forName("android.app.ContextImpl")
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass
                .getMethod("currentActivityThread")
                .invoke(null)
            val loadedApkClass = Class.forName("android.app.LoadedApk")
            val createAppContext = contextImplClass.declaredMethods
                .firstOrNull {
                    it.name == "createAppContext" &&
                        it.parameterCount == 3 &&
                        it.parameterTypes[0] == activityThreadClass &&
                        it.parameterTypes[1] == loadedApkClass
                }
                ?.apply { isAccessible = true }
            if (createAppContext != null) {
                createAppContext.invoke(
                    null,
                    activityThread,
                    packageInfo,
                    targetPackageName
                ) as Context
            } else {
                val createWithoutOpPackage = contextImplClass
                    .getDeclaredMethod(
                        "createAppContext",
                        activityThreadClass,
                        loadedApkClass
                    )
                    .apply { isAccessible = true }
                createWithoutOpPackage.invoke(
                    null,
                    activityThread,
                    packageInfo
                ) as Context
            }
        }.onFailure {
            Log.w(TAG, "create external shell ContextImpl failed", it)
        }.getOrDefault(fallback)

        private fun exposeVirtualClassPath(
            loader: ClassLoader,
            layout: VirtualInstallLayout
        ) {
            val replacements = layout.actualApkPaths
                .zip(layout.virtualApkPaths)
                .toMap()
            var current: ClassLoader? = loader
            var rewritten = 0
            while (current != null) {
                rewritten += rewriteDexElements(current, replacements)
                current = current.parent
            }
            Log.d(TAG, "virtual class path entries rewritten=$rewritten")
        }

        private fun rewriteDexElements(
            loader: ClassLoader,
            replacements: Map<String, String>
        ): Int {
            val pathList = findField(loader.javaClass, "pathList")?.let { field ->
                runCatching { field.get(loader) }.getOrNull()
            } ?: return 0
            val elements = findField(pathList.javaClass, "dexElements")?.let { field ->
                runCatching { field.get(pathList) as? Array<*> }.getOrNull()
            } ?: return 0
            var rewritten = 0
            elements.filterNotNull().forEach { element ->
                val pathField = findField(element.javaClass, "path") ?: return@forEach
                val value = runCatching { pathField.get(element) }.getOrNull()
                val actual = when (value) {
                    is String -> value
                    is File -> value.path
                    else -> return@forEach
                }
                val virtual = replacements[actual] ?: return@forEach
                val replacement: Any = if (value is File) File(virtual) else virtual
                runCatching {
                    pathField.set(element, replacement)
                    rewritten += 1
                }.onFailure {
                    Log.w(TAG, "rewrite dex element failed path=$actual", it)
                }
                rewriteDexFileIdentity(element, virtual)
            }
            return rewritten
        }

        private fun rewriteDexFileIdentity(element: Any, virtualPath: String) {
            val dexFile = findField(element.javaClass, "dexFile")
                ?.let { field -> runCatching { field.get(element) }.getOrNull() }
                ?: return
            val fileName = findField(dexFile.javaClass, "mFileName") ?: return
            runCatching {
                fileName.set(dexFile, virtualPath)
            }.onFailure {
                Log.w(TAG, "rewrite dex file identity failed path=$virtualPath", it)
            }
        }

        private fun findField(type: Class<*>, name: String): Field? {
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                runCatching {
                    return current.getDeclaredField(name).apply {
                        isAccessible = true
                    }
                }
                current = current.superclass
            }
            return null
        }


        private fun attachApplication(
            spec: ContainerLaunchSpec,
            loader: ClassLoader,
            context: Context,
            hardenedAttachContext: Context,
            runtimeNativeLibraries: RuntimeNativeLibraries
        ): Application {
            val applicationSpec = RuntimeApplicationSpec(
                packageName = spec.packageName,
                applicationInfo = spec.applicationInfo,
                baseApkPath = spec.baseApkPath,
                splitApkPaths = spec.splitApkPaths
            )
            val savedContextFields = mutableListOf<
                Triple<java.lang.reflect.Field, Any, Any?>
            >()
            val attachContextImpl = runCatching {
                var current: Any = hardenedAttachContext
                while (current is android.content.ContextWrapper) {
                    val baseField = android.content.ContextWrapper::class.java
                        .getDeclaredField("mBase")
                        .apply { isAccessible = true }
                    current = baseField.get(current) ?: break
                }
                current
            }.getOrDefault(hardenedAttachContext)
            if (hardenedAttachContext.javaClass.name == "com.coke.nestspace.NestSpaceApp") {
                runCatching {
                    val bound = Class.forName("android.app.ActivityThread")
                        .let { threadClass ->
                            val thread = threadClass
                                .getMethod("currentActivityThread")
                                .invoke(null)
                            threadClass.getDeclaredField("mBoundApplication")
                                .apply { isAccessible = true }
                                .get(thread)
                        }
                    val loadedApk = bound?.javaClass
                        ?.getDeclaredField("info")
                        ?.apply { isAccessible = true }
                        ?.get(bound)
                    val virtualDataRoot = File("/data/user/0/${spec.packageName}")
                    // ContextImpl reads the File fields, not just mDataDir.
                    // Keep every data-directory representation on the target
                    // package so external wrapper startup cannot cache host
                    // paths in its static fields.
                    val loadedApkValues = linkedMapOf<String, Any?>(
                        "mAppDir" to spec.baseApkPath,
                        "mResDir" to spec.baseApkPath,
                        "mDataDir" to virtualDataRoot.absolutePath,
                        "mDataDirFile" to virtualDataRoot,
                        "mDeviceProtectedDataDirFile" to File(
                            "/data/user_de/0/${spec.packageName}"
                        ),
                        "mCredentialProtectedDataDirFile" to virtualDataRoot,
                        "mPackageName" to spec.packageName,
                        "mApplicationInfo" to context.applicationInfo,
                        "mClassLoader" to loader,
                    )
                    if (loadedApk != null) {
                        loadedApkValues.forEach { (name, value) ->
                            val field = findField(loadedApk.javaClass, name)
                                ?: return@forEach
                            savedContextFields.add(
                                Triple(
                                    field,
                                    loadedApk,
                                    field.get(loadedApk)
                                )
                            )
                            field.set(loadedApk, value)
                        }
                    }
                    val values = linkedMapOf<String, Any?>(
                        "mPackageInfo" to loadedApk,
                        "mFilesDir" to File(virtualDataRoot, "files"),
                        "mCacheDir" to File(virtualDataRoot, "cache"),
                        "mNoBackupFilesDir" to File(virtualDataRoot, "no_backup"),
                        "mCodeCacheDir" to File(virtualDataRoot, "code_cache"),
                        "mResources" to context.resources,
                    )
                    values.forEach { (name, value) ->
                        val field = findField(attachContextImpl.javaClass, name)
                            ?: return@forEach
                        savedContextFields += Triple(
                            field,
                            attachContextImpl,
                            field.get(attachContextImpl)
                        )
                        field.set(attachContextImpl, value)
                    }
                    Log.d(
                        TAG,
                        "prepared hardened ContextImpl dataDir=${context.filesDir}"
                    )
                }.onFailure {
                    Log.w(TAG, "prepare hardened ContextImpl failed", it)
                }
            }
            val shellContext = createExternalShellContext(
                attachContextImpl,
                attachContextImpl as? Context ?: hardenedAttachContext,
                context.resources,
                loader,
                spec.packageName,
                spec.applicationInfo
            )
            val externalApplicationName = applicationSpec.applicationInfo.className
            if (ExternalApplicationBootstrap.isExternalApplication(
                    externalApplicationName,
                    spec.packageName
                )
            ) {
                val externalApplication = try {
                    ExternalApplicationBootstrap.attach(
                        loader,
                        shellContext,
                        requireNotNull(externalApplicationName),
                        spec.packageName,
                        spec.baseApkPath,
                        File(spec.dataDir)
                    )
                } finally {
                    savedContextFields.asReversed().forEach { (field, target, value) ->
                        runCatching { field.set(target, value) }
                    }
                }
                externalApplication?.let { application ->
                    // External wrappers commonly retain the bootstrap Context
                    // statically. Replace only those exact references after
                    // attach so later target callbacks use the virtual context.
                    ExternalApplicationBootstrap.rebindStaticContexts(
                        application.javaClass,
                        shellContext,
                        context
                    )
                    ExternalApplicationBootstrap.rebindApplicationContext(
                        application,
                        context
                    )
                    ExternalApplicationBootstrap.lastRealApplication?.let {
                        ExternalApplicationBootstrap.rebindApplicationContext(
                            it,
                            context
                        )
                    }
                    if (context is ContainerContext) {
                        context.targetApplication = application
                    }
                    NativeEnvironmentBridge.installKillGuard()
                    return application
                }
            }
            val application = try {
                TargetApplicationFactory.create(
                    applicationSpec,
                    loader,
                    context,
                    attachContextImpl as? Context ?: hardenedAttachContext
                )
            } finally {
                savedContextFields.asReversed().forEach { (field, target, value) ->
                    runCatching { field.set(target, value) }
                }
            }
            if (context is ContainerContext) {
                context.targetApplication = application
                if (ExternalApplicationBootstrap.isExternalApplication(
                        application.javaClass.name,
                        spec.packageName
                    )
                ) {
                    runCatching {
                        val baseField = android.content.ContextWrapper::class.java
                            .getDeclaredField("mBase")
                            .apply { isAccessible = true }
                        baseField.set(application, context)
                    }.onFailure {
                        Log.w(TAG, "restore external Application base context failed", it)
                    }
                }
            }
            Log.d(
                TAG,
                "attached Application ${application.javaClass.name} through " +
                TargetApplicationFactory.javaClass.name
            )
            return application
        }

        private fun createResources(
            host: Context,
            paths: List<String>,
            classLoader: ClassLoader
        ): Resources {
            Log.d(
                TAG,
                "create target resources paths=${
                    paths.joinToString(File.pathSeparator)
                }"
            )
            val assetManager = AssetManager::class.java
                .getDeclaredConstructor()
                .newInstance()
            val addAssetPath = AssetManager::class.java
                .getMethod("addAssetPath", String::class.java)

            paths.forEachIndexed { index, path ->
                val cookie = addAssetPath.invoke(assetManager, path) as Int
                if (cookie == 0) {
                    error(
                        if (index == 0) {
                            "AssetManager.addAssetPath failed: $path"
                        } else {
                            "split addAssetPath failed: $path"
                        }
                    )
                }
                Log.d(
                    TAG,
                    "target AssetManager.addAssetPath index=$index " +
                        "cookie=$cookie path=$path"
                )
            }

            val base = Resources(
                assetManager,
                host.resources.displayMetrics,
                host.resources.configuration
            )
            // Construct the resource table through the public constructor and
            // only patch its ClassLoader fields. Swapping a ResourcesImpl into
            // a separately constructed Resources leaves AssetManager2 package
            // groups inconsistent on recent Android releases.
            bindResourcesClassLoader(base, classLoader)
            return base
        }

        private fun bindResourcesClassLoader(
            resources: Resources,
            classLoader: ClassLoader
        ) {
            fun patch(owner: Any, type: Class<*>) {
                runCatching {
                    type.getDeclaredField("mClassLoader").apply {
                        isAccessible = true
                        set(owner, classLoader)
                    }
                }.onFailure {
                    Log.w(
                        TAG,
                        "bind ${type.simpleName}.mClassLoader failed",
                        it
                    )
                }
            }
            patch(resources, Resources::class.java)
            runCatching {
                val implField = Resources::class.java
                    .getDeclaredField("mResourcesImpl")
                    .apply { isAccessible = true }
                implField.get(resources)?.let { impl ->
                    patch(impl, impl.javaClass)
                }
            }.onFailure {
                Log.w(TAG, "read ResourcesImpl for classloader binding failed", it)
            }
        }
    }
}
