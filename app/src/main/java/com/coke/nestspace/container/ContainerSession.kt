package com.coke.nestspace.container

import com.coke.nestspace.container.identity.ProcessNameCompat
import com.coke.nestspace.container.intent.ContainerIntentResolver
import com.coke.nestspace.container.job.ContainerJobScheduler
import com.coke.nestspace.container.loader.withContainerClassLoader
import com.coke.nestspace.container.application.ExternalApplicationBootstrap
import com.coke.nestspace.container.application.toRuntimeApplicationSpec
import com.coke.nestspace.runtime.plugin.RuntimeProfile
import com.coke.nestspace.container.runtime.NativeEnvironmentBridge
import com.coke.nestspace.container.runtime.AndroidStartupCoordinator
import com.coke.nestspace.container.runtime.AndroidStartupPhase
import com.coke.nestspace.container.runtime.AndroidStartupReport
import com.coke.nestspace.container.runtime.RuntimeBootGraphRecovery
import com.coke.nestspace.container.runtime.RuntimeProcessSentinel
import com.coke.nestspace.container.runtime.VirtualEnvironment
import com.coke.nestspace.container.runtime.VirtualEnvironmentRegistry
import com.coke.nestspace.container.runtime.EnvironmentValidation
import com.coke.nestspace.container.vpm.VirtualPackageRegistry
import com.coke.nestspace.container.session.HostProcessState
import com.coke.nestspace.container.session.NativeHookPump
import com.coke.nestspace.container.session.ProcessIdentityCoordinator
import com.coke.nestspace.container.session.RuntimeDiagnosticsWriter
import com.coke.nestspace.container.session.ContainerSessionFactory
import com.coke.nestspace.container.session.applyTargetLoadedApkIdentity
import com.coke.nestspace.container.session.createTargetActivity
import com.coke.nestspace.container.session.startTargetActivity
import com.coke.nestspace.container.session.TargetApplicationPublisher


import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import dalvik.system.PathClassLoader
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * ActivityThread is process-global, but a slot process alternates between
 * NestSpace's host Activities and one virtual app. Keep the host fields so
 * they can be restored before the framework creates another host Activity.
 */
/**
 * One live virtual-app session: target Application + ClassLoader + Resources.
 *
 * Activity hosting is one real StageAActivity per target Activity. This keeps
 * the host token real, so the target's `finish()` behaves naturally (it closes
 * only its own host and the previous host resumes). `startActivity` inside the
 * target package is intercepted by [ContainerInstrumentation] and turned into
 * another StageAActivity launch backed by this same session.
 */
class ContainerSession internal constructor(
    val spec: ContainerLaunchSpec,
    internal val loader: ClassLoader,
    internal val resources: Resources,
    val context: ContainerContext,
    val application: Application,
    internal val bootstrapApplication: Application?,
    val hostContext: Context,
    internal val activityThread: Any,
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

    internal val targetHosts = ConcurrentHashMap<Activity, StageAActivity>()
    internal val onCreateFieldRetry = ThreadLocal<Boolean>()
    private val processIdentity by lazy {
        ProcessIdentityCoordinator(
            restoreHost = {
                hostProcessState.restore()
                ProcessNameCompat.restorePersistent()
                Log.d(
                    TAG,
                    "restored host process identity ${hostProcessState.description}"
                )
            },
            activateTarget = {
                ProcessNameCompat.setPersistent(spec.targetProcessName)
                // The target Application may have installed a framework-standard
                // ClassLoader wrapper during onCreate. Keep using the active
                // process loader instead of replacing it with the base APK loader.
                TargetApplicationPublisher.publishVirtualIdentity(
                    activityThread,
                    context,
                    activeClassLoader
                )
                publishAsProcessApplication()
                Log.d(TAG, "activated target process identity")
            }
        )
    }
    private val runtimeDiagnosticsWriter by lazy {
        RuntimeDiagnosticsWriter(
            virtualEnvironment = virtualEnvironment,
            environmentValidation = environmentValidation,
            startupCoordinator = startupCoordinator,
            isHeadless = { headless }
        )
    }
    private val nativeHookPump = NativeHookPump(runtimeDiagnosticsWriter::write)
    internal var publishedLoadedApk: Any? = null

    @Volatile
    var navigator: StageAActivity? = null

    val startupReport: AndroidStartupReport
        get() = startupCoordinator.report()

    init {
        context.virtualEnvironment = virtualEnvironment
        context.broadcastManager = broadcastManager
        broadcastManager.adopt(context.earlyReceiverRegistry?.drain().orEmpty())
        context.earlyReceiverRegistry = null
        runCatching {
            System.setProperty("java.io.tmpdir", context.cacheDir.absolutePath)
        }.onFailure {
            Log.w(TAG, "publish target java.io.tmpdir failed", it)
        }
        context.jobScheduler = jobScheduler
        context.virtualRegistry = virtualPackages
        context.permissions.attachRegistry(virtualPackages)
        context.navigator = ContainerNavigator { intent, requestCode ->
            val host = navigator ?: return@ContainerNavigator false
            this.startTargetActivity(host, intent, requestCode)
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
        (loader as? com.coke.nestspace.container.loader.TargetContextInstaller)
            ?.installTargetContext(context)
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
        Log.d(TAG, "session initialized ${spec.packageName}")
    }

    fun noteFirstActivityScheduled() {
        startupCoordinator.markOnce(AndroidStartupPhase.FIRST_ACTIVITY_SCHEDULED)
    }

    fun noteFirstActivityOpened() {
        startupCoordinator.markOnce(AndroidStartupPhase.FIRST_ACTIVITY_OPENED)
        nativeHookPump.refreshAfterLaunch()
    }

    internal fun refreshNativeHooks(scanElfFallback: Boolean = false) {
        nativeHookPump.refresh(scanElfFallback)
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
        nativeHookPump.shutdown()
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
        return processIdentity.withHost(block)
    }

    fun suspendProcessIdentity() {
        processIdentity.suspend()
    }

    fun activateProcessIdentity() {
        processIdentity.activate()
    }

    fun attachHostFrameworkState(activity: Activity, restoreTheme: Boolean) {
        hostProcessState.attachHostFrameworkState(activity, restoreTheme)
    }

    fun createActivity(
        host: StageAActivity,
        className: String?,
        sourceIntent: Intent?,
        savedState: Bundle? = null
    ): Activity = ProcessNameCompat.withProcessName(spec.targetProcessName) {
        withContainerClassLoader(loader) {
            createTargetActivity(host, className, sourceIntent, savedState)
        }
    }






    fun resolveActivity(className: String?): ActivityInfo? =
        intents.findActivity(className?.takeIf { it.isNotBlank() } ?: spec.launcherActivity)

    fun hostFor(target: Activity): StageAActivity? = targetHosts[target]

    fun detach(target: Activity) {
        targetHosts.remove(target)
    }

    /**
     * Called by target Activity.startActivity / Context.startActivity.
     * Returns false for intents outside the target package so the host can
     * launch them normally.
     */




    /**
     * AMS validates the caller package against the process UID. The container
     * process advertises the target package to the app/native layers, so a
     * real host Activity start must temporarily restore the host package
     * identity or AMS rejects it with "package does not belong to uid".
     */

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

    companion object {
        private const val TAG = "ContainerSession"

        fun create(host: Context, spec: ContainerLaunchSpec): ContainerSession =
            ContainerSessionFactory.create(host, spec)
    }
}
