package com.coke.nestspace.container

import com.coke.nestspace.container.runtime.VirtualStorageLayout
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import java.io.File

internal fun ContainerContext.externalStorageDir(): File =
    android.os.Environment.getExternalStorageDirectory()

    internal fun ContainerContext.wrapDerived(
        newBase: Context,
        deviceProtected: Boolean = this.deviceProtected
    ): ContainerContext {
        val adjusted = createDerivedResources(
            newBase.resources.displayMetrics,
            newBase.resources.configuration
        )
        val wrapped = ContainerContext(
            base = newBase,
            targetPackage = targetPackage,
            instanceId = instanceId,
            dataRoot = dataRoot,
            targetClassLoader = targetClassLoader,
            targetResources = adjusted,
            targetAppInfo = targetAppInfo,
            targetRequestedPermissions = targetRequestedPermissions,
            baseApkPath = baseApkPath,
            resourceApkPath = resourceApkPath,
            splitApkPaths = splitApkPaths,
            deviceProtected = deviceProtected
        )
        wrapped.targetApplication = targetApplication
        wrapped.targetTheme = adjusted.newTheme()
        wrapped.navigator = navigator
        wrapped.serviceBridge = serviceBridge
        wrapped.targetContentResolver = targetContentResolver
        wrapped.targetPackageManager = targetPackageManager
        wrapped.broadcastManager = broadcastManager
        wrapped.jobScheduler = jobScheduler
        wrapped.virtualRegistry = virtualRegistry
        wrapped.mPackageInfo = mPackageInfo
        return wrapped
    }

    internal fun ContainerContext.createDerivedResources(
        metrics: android.util.DisplayMetrics,
        configuration: Configuration
    ): Resources {
        val assetManager = AssetManager::class.java
            .getDeclaredConstructor()
            .newInstance()
        val addAssetPath = AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)
        (listOf(resourceApkPath) + splitApkPaths).forEach { path ->
            check(addAssetPath.invoke(assetManager, path) as Int != 0) {
                "AssetManager.addAssetPath failed: $path"
            }
        }
        return bindTargetClassLoader(
            Resources(assetManager, metrics, configuration),
            targetClassLoader
        )
    }

    internal fun ContainerContext.bindTargetClassLoader(
        base: Resources,
        classLoader: ClassLoader
    ): Resources {
        runCatching {
            Resources::class.java
                .getDeclaredField("mClassLoader")
                .apply { isAccessible = true }
                .set(base, classLoader)
        }.onFailure {
            android.util.Log.w(
                "ContainerContext",
                "derived Resources classloader binding failed",
                it
            )
        }
        return base
    }

    internal fun ContainerContext.externalDirectory(suffix: String): File? {
        val actual = File(
            VirtualStorageLayout.externalInstanceRoot(targetPackage, instanceId),
            suffix
        )
        if (!ensureWritableDirectory(actual)) return null
        return File(
            File(externalStorageDir(), "Android/data/$targetPackage"),
            suffix
        )
    }

    internal fun ContainerContext.ensureWritableDirectory(directory: File): Boolean = runCatching {
        if (!directory.isDirectory && !directory.mkdirs()) return@runCatching false
        val probe = File.createTempFile(".nestspace-write-", ".probe", directory)
        probe.delete()
    }.getOrDefault(false)
