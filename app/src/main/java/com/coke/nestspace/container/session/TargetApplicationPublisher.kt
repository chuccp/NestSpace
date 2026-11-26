package com.coke.nestspace.container.session

import android.annotation.SuppressLint
import android.app.AppComponentFactory
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.coke.nestspace.container.ContainerAppComponentFactory
import com.coke.nestspace.container.ContainerLaunchSpec

internal object TargetApplicationPublisher {
    /**
     * Makes native startup checks see the target package before the target
     * Application class is loaded. Static initializers of hardened apps may
     * call PackageManager from JNI_OnLoad, which runs during class loading and
     * before attachBaseContext.
     */
    @SuppressLint("BlockedPrivateApi")
    fun publishVirtualIdentity(
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
                runCatching {
                    val factoryField = loadedApkClass
                        .getDeclaredField("mAppComponentFactory")
                        .apply { isAccessible = true }
                    val current = factoryField.get(loadedApk)
                        as? AppComponentFactory
                    if (current !is ContainerAppComponentFactory) {
                        factoryField.set(
                            loadedApk,
                            ContainerAppComponentFactory(current)
                        )
                    }
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
        }.onFailure {
            Log.w(TAG, "prepublish initial application failed", it)
        }

        runCatching {
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
                "prepublished system context: pmFields=$pmFields " +
                    "appInfoFields=$appInfoFields"
            )
        }.onFailure { Log.w(TAG, "prepublish system context failed", it) }
    }

    fun publishApplicationMetadata(
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

    fun applyTargetRuntimeIdentity(spec: ContainerLaunchSpec) {
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

    private const val TAG = "ContainerSession"
}
