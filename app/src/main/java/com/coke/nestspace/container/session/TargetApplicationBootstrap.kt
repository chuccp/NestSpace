package com.coke.nestspace.container.session

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.coke.nestspace.container.ContainerContext
import com.coke.nestspace.container.ContainerLaunchSpec
import com.coke.nestspace.container.api.RuntimeApplicationSpec
import com.coke.nestspace.container.application.ExternalApplicationBootstrap
import com.coke.nestspace.container.application.TargetApplicationFactory
import com.coke.nestspace.container.runtime.NativeEnvironmentBridge
import java.io.File

internal object TargetApplicationBootstrap {
    fun attach(
        spec: ContainerLaunchSpec,
        loader: ClassLoader,
        context: Context,
        hardenedAttachContext: Context
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
            while (current is ContextWrapper) {
                val baseField = ContextWrapper::class.java
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
                        val field = TargetClassPathBinder.findField(
                            loadedApk.javaClass,
                            name
                        )
                            ?: return@forEach
                        savedContextFields.add(
                            Triple(field, loadedApk, field.get(loadedApk))
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
                    val field = TargetClassPathBinder.findField(
                        attachContextImpl.javaClass,
                        name
                    )
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
        val shellContext = TargetClassPathBinder.createExternalShellContext(
            attachContextImpl,
            attachContextImpl as? Context ?: hardenedAttachContext,
            context.resources,
            loader,
            spec.packageName,
            spec.applicationInfo
        )
        val externalApplicationName = applicationSpec.applicationInfo.className
        if (ExternalApplicationBootstrap.isExternalApplication(spec)) {
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
                    spec.packageName,
                    listOf(spec.baseApkPath) + spec.splitApkPaths
                )
            ) {
                runCatching {
                    val baseField = ContextWrapper::class.java
                        .getDeclaredField("mBase")
                        .apply { isAccessible = true }
                    baseField.set(application, context)
                }.onFailure {
                    Log.w(
                        TAG,
                        "restore external Application base context failed",
                        it
                    )
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

    private const val TAG = "ContainerSession"
}
