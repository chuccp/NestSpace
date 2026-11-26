package com.coke.nestspace.container.session

import com.coke.nestspace.container.ContainerSession
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.provider.Settings
import android.util.Log
import com.coke.nestspace.R
import com.coke.nestspace.container.identity.HostIdentity

internal class HostProcessState(
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
