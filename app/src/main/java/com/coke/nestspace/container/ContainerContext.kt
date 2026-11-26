package com.coke.nestspace.container

import android.annotation.SuppressLint
import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.container.runtime.VirtualEnvironment
import com.coke.nestspace.container.runtime.VirtualStorageLayout
import com.coke.nestspace.container.vpm.ContainerPermissions
import com.coke.nestspace.container.vpm.VirtualPackageRegistry

import android.app.Application
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.UserHandle
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executor
import com.coke.nestspace.container.gms.GmsBridge

/**
 * Stage B context virtualization.
 *
 * The target APK sees its own package name, code/resource paths, ApplicationInfo
 * and a per-instance data directory. File/cache/database/getDir access is
 * redirected here; SharedPreferences are namespaced by package+instance because
 * SharedPreferences files themselves are still managed by the host process.
 *
 * This is intentionally not a full ContextImpl replacement: content providers,
 * PackageManager queries, UID and native paths are Stage C/D work.
 */
class ContainerContext(
    base: Context,
    internal val targetPackage: String,
    val instanceId: String,
    internal val dataRoot: File,
    internal val targetClassLoader: ClassLoader,
    internal val targetResources: Resources,
    internal val targetAppInfo: ApplicationInfo,
    internal val targetRequestedPermissions: Array<String>?,
    internal val baseApkPath: String,
    internal val resourceApkPath: String = baseApkPath,
    internal val splitApkPaths: List<String>,
    internal val deviceProtected: Boolean = false
) : ContextWrapper(base) {

    var targetApplication: Application? = null
    var targetTheme: Resources.Theme? = null
    var virtualEnvironment: VirtualEnvironment? = null
    var navigator: ContainerNavigator? = null

    /**
     * The host Activity's WindowManager. Target Activities are attached with
     * this container context, so without this override dialogs created by a
     * target (privacy prompts, bottom sheets) would use the application-level
     * WindowManager with no window token and fail with BadTokenException.
     */
    var hostWindowManager: android.view.WindowManager? = null
    internal var windowManagerProxy: android.view.WindowManager? = null
    var serviceBridge: ContainerServiceBridge? = null
    var targetContentResolver: ContentResolver? = null
    var targetPackageManager: PackageManager? = null
    var broadcastManager: ContainerBroadcastManager? = null
    internal var earlyReceiverRegistry: ContainerEarlyReceiverRegistry? = null
    var jobScheduler: JobScheduler? = null
    var virtualRegistry: VirtualPackageRegistry? = null

    /**
     * Android exposes the app's LoadedApk as ContextImpl.mPackageInfo. Target
     * frameworks use this standard reflection path to install a wrapper around
     * LoadedApk.mClassLoader during Application.onCreate. Keep the field and
     * resolve components through it so those wrappers stay in effect.
     */
    @Suppress("unused")
    internal var mPackageInfo: Any? = null

    /** Virtual grant model for this target package (shared by PM/context). */
    val permissions: ContainerPermissions = ContainerPermissions(
        hostContext = base.applicationContext,
        targetPackage = targetPackage,
        targetInstanceId = instanceId,
        hostPackage = HostIdentity.packageName,
        targetRequestedPermissions = targetRequestedPermissions
    )

    private val declaredPermissions: Set<String> by lazy {
        targetRequestedPermissions?.toSet() ?: emptySet()
    }

    /**
     * LayoutInflater.from(context) goes through getSystemService(). The base
     * host service resolves layout ids against NestSpace's resource table, so
     * any target code that inflates resources dynamically (ViewStub, adapters,
     * dialogs) would get Resources.NotFoundException. Clone the host inflater
     * onto this context so it uses the target Resources.
     */
    internal val targetLayoutInflater: android.view.LayoutInflater by lazy {
        android.view.LayoutInflater.from(base).cloneInContext(this)
    }

    /**
     * AppWidgetManager validates widget ownership against the calling UID and
     * cannot see packages installed only inside the container. Sandbox it so
     * target widget bookkeeping stays local instead of raising SecurityException.
     */
    internal val targetAppWidgetManager: Any? by lazy {
        ContainerAppWidgetManager.create(this)
    }

    internal var locationManager: android.location.LocationManager? = null
    internal var locationManagerReady = false
    internal val sharedPreferences = mutableMapOf<String, SharedPreferences>()

    internal val activeDataRoot: File
        get() = if (deviceProtected) {
            VirtualStorageLayout.deviceProtectedRoot(dataRoot)
        } else {
            dataRoot
        }

    internal val visibleDataRoot: File
        get() = File(
            if (deviceProtected) "/data/user_de/0" else "/data/user/0",
            targetPackage
        )

    internal fun superGetSystemService(name: String): Any? =
        super.getSystemService(name)

    internal fun superGetFilesDir(): File =
        super.getFilesDir()

    internal fun superGetSharedPreferences(
        name: String,
        mode: Int
    ): SharedPreferences = super.getSharedPreferences(name, mode)

    internal fun superRegisterReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?
    ): Intent? = super.registerReceiver(receiver, filter)

    @SuppressLint("NewApi")
    internal fun superRegisterReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        flags: Int
    ): Intent? = super.registerReceiver(receiver, filter, flags)

    @SuppressLint("NewApi")
    internal fun superRegisterReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: Handler?
    ): Intent? = super.registerReceiver(
        receiver,
        filter,
        broadcastPermission,
        scheduler
    )

    internal fun superRegisterReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: Handler?,
        flags: Int
    ): Intent? = super.registerReceiver(
        receiver,
        filter,
        broadcastPermission,
        scheduler,
        flags
    )

    internal fun superUnregisterReceiver(receiver: BroadcastReceiver) {
        super.unregisterReceiver(receiver)
    }

    internal fun superSendBroadcast(intent: Intent) {
        super.sendBroadcast(intent)
    }

    internal fun superSendBroadcast(
        intent: Intent,
        receiverPermission: String?
    ) {
        super.sendBroadcast(intent, receiverPermission)
    }

    @SuppressLint("NewApi")
    internal fun superSendBroadcast(
        intent: Intent,
        receiverPermission: String?,
        options: Bundle?
    ) {
        super.sendBroadcast(intent, receiverPermission, options)
    }

    internal fun superSendBroadcastAsUser(
        intent: Intent,
        user: UserHandle
    ) {
        super.sendBroadcastAsUser(intent, user)
    }

    internal fun superSendBroadcastAsUser(
        intent: Intent,
        user: UserHandle,
        receiverPermission: String?
    ) {
        super.sendBroadcastAsUser(intent, user, receiverPermission)
    }

    @Synchronized

    /**
     * Target windows (notably Toast) carry the target package in
     * WindowManager.LayoutParams. The system accepts only the host package for
     * this UID, so rewrite the package before the real WindowManager sees it.
     */

    override fun getSystemService(name: String): Any? =
        getContainerSystemService(name)

    internal var autofillManager: Any? = null
    internal var shortcutManager: Any? = null

    internal var localeManager: Any? = null

    internal fun defaultServiceValue(type: Class<*>): Any? = when (type) {
        Void.TYPE -> null
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> '\u0000'
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> null
    }

    // Context.getSystemService(Class) is final, but it dispatches through the
    // name-based getSystemService(String), so the override above is enough.

    internal fun targetDeclares(permission: String): Boolean = permission in declaredPermissions

    override fun checkSelfPermission(permission: String): Int =
        permissions.checkSelfPermission(permission)

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        permissions.checkCallingPermission(permission, pid, uid)

    override fun checkCallingOrSelfPermission(permission: String): Int =
        permissions.checkSelfPermission(permission)

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (permissions.checkCallingPermission(permission, pid, uid) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        throw SecurityException(message ?: "Permission denied: $permission")
    }

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        permissions.enforce(permission, message)
    }

    /**
     * Android 17 added Context.getPermissionRequestState(String), which
     * Activity.requestPermissions uses to short-circuit requests. It is not in
     * the compile-time stubs, so declare it without `override`; ART still
     * dispatches the virtual call here on releases that have it. Values match
     * Context.PERMISSION_REQUEST_STATE_GRANTED/REQUESTABLE/UNREQUESTABLE.
     *
     * Without this, a host-granted permission would report GRANTED even after
     * the user revoked it for the virtual app, bypassing the virtual policy.
     */
    @Suppress("unused")
    fun getPermissionRequestState(permission: String): Int = when {
        permissions.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED -> 0
        targetDeclares(permission) -> 1
        else -> 2
    }

    override fun getPackageName(): String =
        if (calledFromToast()) HostIdentity.packageName else targetPackage

    /**
     * Android 12+ rejects a Toast whose package does not belong to the caller
     * UID ("Package <target> not in UID ..."). The target context still
     * has to report its virtual package everywhere else, so only the
     * Toast constructor sees the host package.
     */

    /**
     * Attribution (AppOps, IME, connectivity, notification, storage) is
     * validated against the real kernel UID. The host package is the value
     * system_server can verify, so this method intentionally remains host
     * scoped even though the target's other package-facing APIs are virtual.
     */
    override fun getOpPackageName(): String = when {
        calledFromToast() -> HostIdentity.packageName
        calledFromFrameworkAttribution() -> HostIdentity.packageName
        else -> targetPackage
    }

    /**
     * ActivityThread.currentOpPackageName() reads the application context, so
     * an app calling it would otherwise hit the framework-attribution branch
     * above and receive the host package. Keep system services host-scoped,
     * while preserving the target package when the target app or JNI called
     * the static helper directly.
     */

    override fun getDataDir(): File {
        ensureBackingDirectory(activeDataRoot)
        return visibleDataRoot
    }

    override fun isDeviceProtectedStorage(): Boolean = deviceProtected

    override fun createDeviceProtectedStorageContext(): Context =
        wrapDerived(super.createDeviceProtectedStorageContext(), deviceProtected = true)

    override fun getFilesDir(): File = dir("files")

    override fun getCacheDir(): File = dir("cache")

    override fun getCodeCacheDir(): File = dir("code_cache")

    override fun getNoBackupFilesDir(): File = dir("no_backup")

    override fun getDir(name: String, mode: Int): File = dir("app_$name")

    override fun getDatabasePath(name: String): File =
        File(dir("databases"), name)

    override fun openFileInput(name: String): FileInputStream =
        FileInputStream(File(getFilesDir(), name))

    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        val file = File(getFilesDir(), name)
        ensureBackingDirectory(File(activeDataRoot, "files"))
        return FileOutputStream(file, mode and Context.MODE_APPEND != 0)
    }

    override fun deleteFile(name: String): Boolean = File(getFilesDir(), name).delete()

    override fun fileList(): Array<String> = getFilesDir().list() ?: emptyArray()

    override fun getSharedPreferences(
        name: String?,
        mode: Int
    ): SharedPreferences = getContainerSharedPreferences(name, mode)

    override fun getExternalFilesDir(type: String?): File? {
        val suffix = if (type.isNullOrBlank()) {
            "files"
        } else {
            "files/${type.substringAfterLast('/')}"
        }
        return externalDirectory(suffix)
    }

    override fun getExternalCacheDir(): File? {
        return externalDirectory("cache")
    }

    override fun getObbDir(): File {
        val actual = File(
            VirtualStorageLayout.externalInstanceRoot(targetPackage, instanceId),
            "obb"
        )
        ensureWritableDirectory(actual)
        return File(
            File(externalStorageDir(), "Android/obb"),
            targetPackage
        )
    }

    override fun getObbDirs(): Array<File?> = arrayOf(getObbDir())

    override fun getExternalFilesDirs(type: String?): Array<File?> =
        arrayOf(getExternalFilesDir(type))

    override fun getExternalCacheDirs(): Array<File?> =
        arrayOf(getExternalCacheDir())

    override fun getExternalMediaDirs(): Array<File?> {
        val actual = File(
            VirtualStorageLayout.externalInstanceRoot(targetPackage, instanceId),
            "media"
        )
        ensureWritableDirectory(actual)
        return arrayOf(
            File(File(externalStorageDir(), "Android/media"), targetPackage)
        )
    }

    override fun createPackageContext(packageName: String, flags: Int): Context =
        if (packageName == targetPackage) this else super.createPackageContext(packageName, flags)

    override fun getPackageCodePath(): String = baseApkPath

    override fun getPackageResourcePath(): String = resourceApkPath

    override fun getClassLoader(): ClassLoader =
        packageInfoClassLoader() ?: targetClassLoader

    override fun getResources(): Resources = targetResources

    override fun getAssets() = targetResources.assets

    override fun getApplicationInfo(): ApplicationInfo = targetAppInfo

    override fun getApplicationContext(): Context = targetApplication ?: this

    override fun getTheme(): Resources.Theme = targetTheme ?: super.getTheme()

    fun bindPackageInfo(packageInfo: Any?) {
        mPackageInfo = packageInfo
    }

    internal fun currentClassLoader(): ClassLoader =
        packageInfoClassLoader() ?: targetClassLoader

    private fun packageInfoClassLoader(): ClassLoader? {
        val packageInfo = mPackageInfo ?: return null
        return runCatching {
            packageInfo.javaClass
                .getMethod("getClassLoader")
                .invoke(packageInfo) as? ClassLoader
        }.getOrNull()
    }

    /**
     * Some Activities rewrite their base context through
     * createConfigurationContext for locale handling. The stock implementation
     * returns a ContextImpl backed by the host resources, which makes the
     * target's drawable IDs disappear. Re-wrap the new configuration around
     * the target APK's AssetManager instead.
     */
    override fun createConfigurationContext(
        overrideConfiguration: Configuration
    ): Context {
        val configuration = Configuration(targetResources.configuration).apply {
            updateFrom(overrideConfiguration)
        }
        val metrics = targetResources.displayMetrics
        val dynamicResources = Resources(
            targetResources.assets,
            metrics,
            configuration
        )
        return object : ContextWrapper(this) {
            override fun getResources(): Resources = dynamicResources
            override fun getAssets() = dynamicResources.assets
        }
    }

    /**
     * Contexts derived from the container context (device-protected storage,
     * display, window, attribution, user) must stay container contexts.
     * Otherwise the framework backs them with the host LoadedApk, whose
     * package name was rewritten to the target, and getExternalFilesDir()
     * builds target paths that StorageManager refuses to create for the host
     * UID ("does not contain calling package").
     */
    override fun createDisplayContext(display: android.view.Display): Context =
        wrapDerived(super.createDisplayContext(display))

    override fun createWindowContext(
        type: Int,
        options: Bundle?
    ): Context = wrapDerived(super.createWindowContext(type, options))

    override fun createWindowContext(
        display: android.view.Display,
        type: Int,
        options: Bundle?
    ): Context = wrapDerived(super.createWindowContext(display, type, options))

    override fun createAttributionContext(attributionTag: String?): Context =
        wrapDerived(super.createAttributionContext(attributionTag))

    override fun startActivity(intent: Intent) {
        if (navigator?.startTargetActivity(intent, -1) == true) return
        if (openWebIntentInside(intent)) return
        android.util.Log.w(
            "ContainerContext",
            "startActivity falls back to host: component=${intent.component} " +
                "action=${intent.action} package=${intent.`package`}"
        )
        super.startActivity(
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        if (navigator?.startTargetActivity(intent, -1) == true) return
        if (openWebIntentInside(intent)) return
        super.startActivity(
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            options
        )
    }

    override fun startService(service: Intent): ComponentName? {
        serviceBridge?.startService(service, false)?.let { return it }
        return super.startService(service)
    }

    override fun startForegroundService(service: Intent): ComponentName? {
        serviceBridge?.startService(service, true)?.let { return it }
        return super.startForegroundService(
            Intent(service).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** GMS client/broker classes live in the GMS APK, not the target APK. */
    private val gmsClassLoader: ClassLoader? by lazy {
        val sourceDir = runCatching {
            getPackageManager().getApplicationInfo("com.google.android.gms", 0).sourceDir
        }.getOrNull()
        if (sourceDir.isNullOrBlank()) {
            null
        } else {
            runCatching {
                dalvik.system.DexClassLoader(sourceDir, null, null, targetClassLoader)
            }.onFailure {
                android.util.Log.w("ContainerContext", "GMS DexClassLoader failed", it)
            }.getOrNull()
        }
    }

    override fun bindService(
        service: Intent,
        conn: ServiceConnection,
        flags: Int
    ): Boolean {
        android.util.Log.d(
            "ContainerContext",
            "bindService action=${service.action} comp=${service.component} " +
                "pkg=${service.`package`}"
        )
        if (serviceBridge?.bindService(service, conn, flags) == true) return true
        return GmsBridge.bind(
            targetClassLoader,
            gmsClassLoader,
            service,
            conn,
            targetPackage,
            HostIdentity.packageName
        ) { connection ->
            super.bindService(
                Intent(service).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                connection,
                flags
            )
        }
    }

    override fun bindService(
        service: Intent,
        flags: Int,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean {
        android.util.Log.d(
            "ContainerContext",
            "bindService(executor) action=${service.action} comp=${service.component} " +
                "pkg=${service.`package`}"
        )
        if (serviceBridge?.bindService(service, conn, flags) == true) return true
        return GmsBridge.bind(
            targetClassLoader,
            gmsClassLoader,
            service,
            conn,
            targetPackage,
            HostIdentity.packageName
        ) { connection ->
            super.bindService(
                Intent(service).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags,
                executor,
                connection
            )
        }
    }

    override fun unbindService(conn: ServiceConnection) {
        if (serviceBridge?.unbindService(conn) == true) return
        GmsBridge.unbind(conn) { super.unbindService(it) }
    }

    override fun stopService(service: Intent): Boolean {
        serviceBridge?.stopService(service)?.let { return it }
        return super.stopService(service)
    }

    override fun getContentResolver(): ContentResolver =
        targetContentResolver ?: super.getContentResolver()

    override fun getPackageManager(): PackageManager =
        targetPackageManager ?: super.getPackageManager()

override fun registerReceiver(
    receiver: BroadcastReceiver?,
    filter: IntentFilter?
): Intent? = registerContainerReceiver(receiver, filter)

override fun registerReceiver(
    receiver: BroadcastReceiver?,
    filter: IntentFilter?,
    flags: Int
): Intent? = registerContainerReceiver(receiver, filter, flags)

override fun registerReceiver(
    receiver: BroadcastReceiver?,
    filter: IntentFilter?,
    broadcastPermission: String?,
    scheduler: Handler?
): Intent? = registerContainerReceiver(
    receiver,
    filter,
    broadcastPermission,
    scheduler
)

override fun registerReceiver(
    receiver: BroadcastReceiver?,
    filter: IntentFilter?,
    broadcastPermission: String?,
    scheduler: Handler?,
    flags: Int
): Intent? = registerContainerReceiver(
    receiver,
    filter,
    broadcastPermission,
    scheduler,
    flags
)

override fun unregisterReceiver(receiver: BroadcastReceiver) =
    unregisterContainerReceiver(receiver)

override fun sendBroadcast(intent: Intent) =
    sendContainerBroadcast(intent)

override fun sendBroadcast(intent: Intent, receiverPermission: String?) =
    sendContainerBroadcast(intent, receiverPermission)

override fun sendBroadcast(
    intent: Intent,
    receiverPermission: String?,
    options: Bundle?
) = sendContainerBroadcast(intent, receiverPermission, options)

override fun sendBroadcastAsUser(intent: Intent, user: UserHandle) =
    sendContainerBroadcastAsUser(intent, user)

override fun sendBroadcastAsUser(
    intent: Intent,
    user: UserHandle,
    receiverPermission: String?
) = sendContainerBroadcastAsUser(intent, user, receiverPermission)

    override fun sendOrderedBroadcast(intent: Intent, receiverPermission: String?) {
        if (sendLocal(
                intent,
                receiverPermission,
                true,
                null,
                null,
                0,
                null,
                null,
                false
            )
        ) {
            return
        }
        super.sendOrderedBroadcast(forwardToHost(intent), receiverPermission)
    }

    override fun sendOrderedBroadcast(
        intent: Intent,
        receiverPermission: String?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        if (sendLocal(
                intent,
                receiverPermission,
                true,
                resultReceiver,
                scheduler,
                initialCode,
                initialData,
                initialExtras,
                false
            )
        ) {
            return
        }
        super.sendOrderedBroadcast(
            forwardToHost(intent),
            receiverPermission,
            resultReceiver,
            scheduler,
            initialCode,
            initialData,
            initialExtras
        )
    }

    override fun sendOrderedBroadcast(
        intent: Intent,
        receiverPermission: String?,
        options: Bundle?
    ) {
        if (sendLocal(
                intent,
                receiverPermission,
                true,
                null,
                null,
                0,
                null,
                null,
                false
            )
        ) {
            return
        }
        super.sendOrderedBroadcast(forwardToHost(intent), receiverPermission, options)
    }

    override fun sendOrderedBroadcast(
        intent: Intent,
        receiverPermission: String?,
        receiverAppOp: String?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        if (sendLocal(
                intent,
                receiverPermission,
                true,
                resultReceiver,
                scheduler,
                initialCode,
                initialData,
                initialExtras,
                false
            )
        ) {
            return
        }
        super.sendOrderedBroadcast(
            forwardToHost(intent),
            receiverPermission,
            receiverAppOp,
            resultReceiver,
            scheduler,
            initialCode,
            initialData,
            initialExtras
        )
    }

    override fun sendOrderedBroadcastAsUser(
        intent: Intent,
        user: UserHandle,
        receiverPermission: String?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        if (sendLocal(
                intent,
                receiverPermission,
                true,
                resultReceiver,
                scheduler,
                initialCode,
                initialData,
                initialExtras,
                false
            )
        ) {
            return
        }
        super.sendOrderedBroadcastAsUser(
            forwardToHost(intent),
            user,
            receiverPermission,
            resultReceiver,
            scheduler,
            initialCode,
            initialData,
            initialExtras
        )
    }

    override fun sendStickyBroadcast(intent: Intent) {
        if (sendLocal(intent, null, false, null, null, 0, null, null, true)) return
        if (broadcastManager == null) runCatching { super.sendStickyBroadcast(intent) }
        else android.util.Log.d(
            "ContainerContext",
            "sticky broadcast kept in container: ${intent.action}"
        )
    }

    override fun sendStickyBroadcastAsUser(intent: Intent, user: UserHandle) {
        if (sendLocal(intent, null, false, null, null, 0, null, null, true)) return
        if (broadcastManager == null) {
            runCatching { super.sendStickyBroadcastAsUser(intent, user) }
        } else {
            android.util.Log.d(
                "ContainerContext",
                "sticky broadcast kept in container: ${intent.action}"
            )
        }
    }

    override fun sendStickyOrderedBroadcast(
        intent: Intent,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        if (sendLocal(
                intent,
                null,
                true,
                resultReceiver,
                scheduler,
                initialCode,
                initialData,
                initialExtras,
                true
            )
        ) {
            return
        }
        if (broadcastManager == null) {
            runCatching {
                super.sendStickyOrderedBroadcast(
                    intent,
                    resultReceiver,
                    scheduler,
                    initialCode,
                    initialData,
                    initialExtras
                )
            }
        } else {
            android.util.Log.d(
                "ContainerContext",
                "sticky ordered broadcast kept in container: ${intent.action}"
            )
        }
    }

    override fun sendStickyOrderedBroadcastAsUser(
        intent: Intent,
        user: UserHandle,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        if (sendLocal(
                intent,
                null,
                true,
                resultReceiver,
                scheduler,
                initialCode,
                initialData,
                initialExtras,
                true
            )
        ) {
            return
        }
        if (broadcastManager == null) {
            runCatching {
                super.sendStickyOrderedBroadcastAsUser(
                    intent,
                    user,
                    resultReceiver,
                    scheduler,
                    initialCode,
                    initialData,
                    initialExtras
                )
            }
        } else {
            android.util.Log.d(
                "ContainerContext",
                "sticky ordered broadcast kept in container: ${intent.action}"
            )
        }
    }

    /**
     * Marks a broadcast that the container already delivered locally before
     * handing it to the host. The host-side bridge drops marked intents so a
     * broadcast cannot be delivered to target Receivers twice.
     */
    internal fun forwardToHost(intent: Intent): Intent =
        Intent(intent).putExtra(ContainerBroadcastManager.EXTRA_BRIDGE_ORIGIN, targetPackage)

    internal fun sendLocal(
        intent: Intent,
        receiverPermission: String?,
        ordered: Boolean,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?,
        sticky: Boolean
    ): Boolean = broadcastManager?.send(
        intent,
        receiverPermission,
        ordered,
        resultReceiver,
        scheduler,
        initialCode,
        initialData,
        initialExtras,
        sticky
    ) == true

companion object {
    fun clearPackagePrefs(base: Context, packageName: String) =
        ContainerContextStorage.clearPackagePrefs(base, packageName)

    fun clearInstancePrefs(
        base: Context,
        packageName: String,
        instanceId: String
    ) = ContainerContextStorage.clearInstancePrefs(
        base,
        packageName,
        instanceId
    )

    fun externalRootFor(
        base: Context,
        packageName: String,
        instanceId: String
    ): File? = ContainerContextStorage.externalRootFor(
        base,
        packageName,
        instanceId
    )
}
}
