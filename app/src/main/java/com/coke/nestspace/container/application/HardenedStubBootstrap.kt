package com.coke.nestspace.container.application

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import java.io.File

/**
 * Bootstraps the Jiagu StubApp without routing library extraction back through
 * an Application whose virtual paths differ from the framework's instance.
 */
object HardenedStubBootstrap {
    private const val TAG = "HardenedStubBootstrap"
    private const val ENTRY_CACHE_FILE = "hardened-entry-application.txt"

    fun cachedEntryName(dataRoot: File, baseApkPath: String): String? =
        runCatching {
            val lines = entryCache(dataRoot).readLines()
            if (lines.size < 2 || lines[0] != cacheKey(baseApkPath)) {
                null
            } else {
                lines[1].trim().takeIf(String::isNotBlank)
            }
        }.getOrNull()

    @Volatile
    var lastRealApplication: Application? = null
        private set

    fun attach(
        loader: ClassLoader,
        context: Context,
        packerRoot: File
    ): Application? = runCatching {
        val stubClass = Class.forName("com.stub.StubApp", true, loader)
        val stub = stubClass.getDeclaredConstructor()
            .newInstance() as Application
        attachApplicationBase(stub, context)

        val runtimeLibraryName = "libjiagu_64.so"
        val compatibilityLibraryName = "libjiagu.so"
        val library = File(packerRoot, runtimeLibraryName)
        require(library.isFile) {
            "Packer runtime library is missing: ${library.absolutePath}"
        }
        require(File(packerRoot, compatibilityLibraryName).isFile) {
            "Packer compatibility library is missing"
        }

        setStatic(stubClass, "a", stub)
        setStatic(stubClass, "d", context)
        setStatic(stubClass, "e", compatibilityLibraryName)
        setStatic(stubClass, "i", runtimeLibraryName)
        setStatic(stubClass, "f", library.absolutePath)
        setStatic(
            stubClass,
            "g",
            File(packerRoot, runtimeLibraryName).absolutePath
        )
        setStatic(stubClass, "h", packerRoot.absolutePath)
        val dataRoot = requireNotNull(packerRoot.parentFile) {
            "Packer root has no parent directory"
        }
        val entryCache = entryCache(dataRoot)
        val cacheKey = cacheKey(context.packageCodePath)
        val cachedEntry = cachedEntryName(
            dataRoot,
            context.packageCodePath
        )
        if (!cachedEntry.isNullOrBlank()) {
            setStatic(stubClass, "strEntryApplication", cachedEntry)
            Log.d(TAG, "restored entry application cache=$cachedEntry")
        }

        Runtime::class.java
            .getDeclaredMethod(
                "load0",
                Class::class.java,
                String::class.java
            )
            .apply { isAccessible = true }
            .invoke(
                Runtime.getRuntime(),
                stubClass,
                library.absolutePath
            )
        val dtcLoader = Class.forName("com.tianyu.util.DtcLoader", true, loader)
        dtcLoader.getDeclaredMethod("init")
            .apply { isAccessible = true }
            .invoke(null)

        stubClass.getDeclaredMethod("interface5", Application::class.java)
            .apply { isAccessible = true }
            .invoke(null, stub)
        runCatching {
            stubClass.getDeclaredMethod("interface21", Application::class.java)
                .apply { isAccessible = true }
                .invoke(null, stub)
            Log.d(TAG, "StubApp interface21 completed")
        }.onFailure {
            Log.w(TAG, "StubApp interface21 failed", it)
        }
        val entryName = stubClass.getDeclaredField("strEntryApplication")
            .apply { isAccessible = true }
            .get(null) as? String
        val nativeApplication = stubClass.getDeclaredField("b")
            .apply { isAccessible = true }
            .get(null) as? Application
        Log.d(
            TAG,
            "StubApp native initialization returned " +
                "entry=$entryName nativeApplication=$nativeApplication"
        )

        val real = nativeApplication
            ?: stubClass.getDeclaredMethod("a", Context::class.java)
                .apply { isAccessible = true }
                .invoke(null, context) as? Application
        if (real == null) {
            Log.w(TAG, "StubApp did not resolve entryRunApplication")
            return@runCatching null
        }
        Log.d(TAG, "resolved target application=${real.javaClass.name}")
        runCatching {
            entryCache.parentFile?.mkdirs()
            entryCache.writeText("$cacheKey\n${real.javaClass.name}\n")
        }.onFailure {
            Log.w(TAG, "write entry application cache failed", it)
        }
        lastRealApplication = real

        Application::class.java
            .getDeclaredMethod("attach", Context::class.java)
            .apply { isAccessible = true }
            .invoke(real, context)
        stubClass.getDeclaredMethod("interface99", Application::class.java)
            .apply { isAccessible = true }
            .invoke(null, real)
        Log.d(
            TAG,
            "prepared real application=${real.javaClass.name} " +
                "through StubApp lifecycle"
        )
        stub
    }.onFailure {
        Log.e(TAG, "hardened bootstrap failed", it)
    }.getOrNull()

    private fun attachApplicationBase(application: Application, context: Context) {
        ContextWrapper::class.java
            .getDeclaredField("mBase")
            .apply { isAccessible = true }
            .set(application, context)
    }

    private fun setStatic(type: Class<*>, name: String, value: Any?) {
        type.getDeclaredField(name).apply {
            isAccessible = true
            set(null, value)
        }
    }

    private fun entryCache(dataRoot: File): File =
        File(dataRoot, ".nestspace/$ENTRY_CACHE_FILE")

    private fun cacheKey(baseApkPath: String): String {
        val apk = File(baseApkPath)
        return "$baseApkPath:${apk.length()}:${apk.lastModified()}"
    }
}
