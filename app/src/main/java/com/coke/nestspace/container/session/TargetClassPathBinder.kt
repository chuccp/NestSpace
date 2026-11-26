package com.coke.nestspace.container.session

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.util.Log
import com.coke.nestspace.container.loader.HostBootstrapClassLoader
import com.coke.nestspace.container.runtime.NativeEnvironmentBridge
import com.coke.nestspace.container.runtime.VirtualInstallLayout
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Field

internal object TargetClassPathBinder {
    fun bootstrapClassLoader(hostLoader: ClassLoader?): ClassLoader {
        val systemLoader = ClassLoader.getSystemClassLoader()
        return HostBootstrapClassLoader(
            hostLoader = hostLoader
                ?: TargetClassPathBinder::class.java.classLoader
                ?: systemLoader,
            platformLoader = systemLoader.parent
        )
    }

    fun createExternalApplicationClassLoader(
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

    fun createExternalShellContext(
        sourceContextImpl: Any,
        fallback: Context,
        resources: Resources,
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

    fun exposeVirtualClassPath(
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

    fun findField(type: Class<*>, name: String): Field? {
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

    private const val TAG = "ContainerSession"
}
