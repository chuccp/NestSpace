package com.coke.nestspace.container.application

import com.coke.nestspace.container.ContainerLaunchSpec
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.coke.nestspace.container.loader.ApkClassIndex
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Starts an Application implementation supplied by an external wrapper.
 *
 * Some applications declare a manifest Application which is located outside
 * their own package and initializes the real Application through native or
 * generated code. The bootstrap discovers that contract from class shapes and
 * runtime values instead of matching package, class, signature or hash
 * fingerprints.
 */
internal object ExternalApplicationBootstrap {
    private const val TAG = "ExternalAppBootstrap"
    private const val CACHE_FILE = "external-entry-application.txt"

    @Volatile
    var lastRealApplication: Application? = null
        private set

    fun isExternalApplication(className: String?, packageName: String): Boolean {
        if (className.isNullOrBlank()) return false
        return !className.startsWith("$packageName.")
    }

    /**
     * An Application class in a sibling namespace is still a normal target
     * class when its DEX is packaged inside the target APK. Only treat it as
     * an external wrapper when the declared class is actually absent from the
     * target code paths.
     */
    fun isExternalApplication(spec: ContainerLaunchSpec): Boolean {
        val className = spec.applicationInfo.className
        return isExternalApplication(
            className,
            spec.packageName,
            listOf(spec.baseApkPath) + spec.splitApkPaths
        )
    }

    fun isExternalApplication(
        className: String?,
        packageName: String,
        apkPaths: List<String>
    ): Boolean {
        if (className.isNullOrBlank()) return false
        if (className.startsWith("$packageName.")) return false
        return className !in ApkClassIndex.classNames(apkPaths)
    }

    fun cachedEntryName(dataRoot: File, baseApkPath: String): String? =
        runCatching {
            val lines = entryCache(dataRoot).readLines()
            if (lines.size < 2 || lines[0] != cacheKey(baseApkPath)) {
                null
            } else {
                lines[1].trim().takeIf(String::isNotBlank)
            }
        }.getOrNull()

    /**
     * Wrappers commonly retain the Context passed to attachBaseContext in a
     * static field. The container borrows a temporary host ContextImpl while
     * the wrapper unpacks its code, then restores that host object. Any
     * retained reference would therefore start resolving target classes and
     * resources through the host package after bootstrap.
     */
    fun rebindStaticContexts(
        applicationType: Class<*>,
        previous: Context,
        replacement: Context
    ): Int {
        var rebound = 0
        staticFields(applicationType).forEach { field ->
            if (!Context::class.java.isAssignableFrom(field.type)) {
                return@forEach
            }
            runCatching {
                if (field.get(null) === previous) {
                    field.set(null, replacement)
                    rebound += 1
                }
            }.onFailure {
                Log.w(TAG, "rebind external Application context failed: ${field.name}", it)
            }
        }
        if (rebound != 0) {
            Log.d(
                TAG,
                "rebound external Application contexts=${rebound}"
            )
        }
        return rebound
    }

    fun rebindApplicationContext(
        application: Application,
        context: Context
    ) {
        runCatching {
            val baseField = baseContextField()
            if (baseField.get(application) !== context) {
                baseField.set(application, context)
            }
        }.onFailure {
            Log.w(TAG, "rebind Application context failed", it)
        }
    }

    fun attach(
        loader: ClassLoader,
        context: Context,
        wrapperClassName: String,
        targetPackageName: String,
        baseApkPath: String,
        dataRoot: File
    ): Application? = runCatching {
        val wrapperClass = loader.loadClass(wrapperClassName)
            .asSubclass(Application::class.java)
        configureEntryAlias(
            wrapperClass,
            cachedEntryName(dataRoot, baseApkPath)
        )
        val wrapper = wrapperClass.getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        // The wrapper owns its startup sequence. In particular, a hardened
        // wrapper may decrypt code, load its native runtime, and attach the
        // target Application from its attachBaseContext override. Calling that
        // lifecycle method is required; populating the wrapper's target slots
        // beforehand can make it skip that startup path entirely.
        attachBaseContext(wrapper, context)
        val real = findMountedApplication(
            wrapperClass,
            wrapper,
            context,
            targetPackageName
        )
        if (real == null || real === wrapper) {
            Log.d(TAG, "external Application initialized in place: ${wrapperClass.name}")
            return@runCatching wrapper
        }

        lastRealApplication = real
        writeEntryCache(dataRoot, baseApkPath, real.javaClass.name)
        Log.d(
            TAG,
            "external Application mounted wrapper=${wrapperClass.name} " +
                "target=${real.javaClass.name}"
        )
        wrapper
    }.onFailure {
        Log.e(TAG, "external Application bootstrap failed", it)
    }.getOrNull()

    private fun configureEntryAlias(
        wrapperClass: Class<*>,
        entryApplication: String?
    ) {
        if (entryApplication.isNullOrBlank()) return
        staticFields(wrapperClass)
            .filter { it.type == String::class.java }
            .filter { field ->
                val name = field.name.lowercase()
                (name.contains("entry") && name.contains("application")) ||
                    runCatching { field.get(null) as? String }
                        .getOrNull()
                        ?.let {
                            it.startsWith("entry") &&
                                it.endsWith("Application", ignoreCase = true)
                        } == true
            }
            .forEach { field ->
                runCatching {
                    field.set(null, entryApplication)
                    Log.d(
                        TAG,
                        "configured external wrapper entry field=${field.name} " +
                            "target=$entryApplication"
                    )
                }.onFailure {
                    Log.w(
                        TAG,
                        "configure external wrapper entry field failed: ${field.name}",
                        it
                    )
                }
            }
    }

    private fun findMountedApplication(
        wrapperClass: Class<*>,
        wrapper: Application,
        context: Context,
        targetPackageName: String
    ): Application? {
        val fieldCandidates = staticFields(wrapperClass)
            .filter { Application::class.java.isAssignableFrom(it.type) }
            .mapNotNull { field ->
                runCatching { field.get(null) as? Application }.getOrNull()
            }
            .filter { it !== wrapper }
            .sortedWith(
                compareByDescending<Application> {
                    it.javaClass.name.startsWith("$targetPackageName.")
                }.thenBy { it.javaClass.name }
            )
        fieldCandidates.firstOrNull()?.let { return it }

        val methodCandidates = staticMethods(wrapperClass)
            .filter { method ->
                Application::class.java.isAssignableFrom(method.returnType) &&
                    method.parameterTypes.all { type ->
                        Application::class.java.isAssignableFrom(type) ||
                            Context::class.java.isAssignableFrom(type)
                    }
            }
            .sortedWith(
                compareBy<Method> { it.parameterCount }
                    .thenBy { it.name }
            )
        for (method in methodCandidates) {
            val arguments = method.parameterTypes.map { type ->
                if (Context::class.java.isAssignableFrom(type)) context else wrapper
            }.toTypedArray()
            val value = runCatching {
                method.isAccessible = true
                method.invoke(null, *arguments) as? Application
            }.onFailure {
                Log.w(TAG, "external Application resolver failed: ${method.name}", it)
            }.getOrNull()
            if (value != null && value !== wrapper) return value
        }
        return null
    }

    private fun attachBaseContext(application: Application, context: Context) {
        val baseField = baseContextField()
        val attachBase = attachBaseContextMethod(application.javaClass)
        attachBase?.invoke(application, context)
        if (baseField.get(application) == null || baseField.get(application) !== context) {
            baseField.set(application, context)
        }
    }

    private fun baseContextField(): Field =
        ContextWrapper::class.java
            .getDeclaredField("mBase")
            .apply { isAccessible = true }

    private fun attachBaseContextMethod(type: Class<*>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(
                    "attachBaseContext",
                    Context::class.java
                ).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private fun staticFields(type: Class<*>): Sequence<Field> =
        generateSequence(type as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }

    private fun staticMethods(type: Class<*>): Sequence<Method> =
        generateSequence(type as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { Modifier.isStatic(it.modifiers) && !it.isSynthetic }

    private fun writeEntryCache(
        dataRoot: File,
        baseApkPath: String,
        className: String
    ) {
        runCatching {
            val target = entryCache(dataRoot)
            target.parentFile?.mkdirs()
            target.writeText("${cacheKey(baseApkPath)}\n$className\n")
        }.onFailure {
            Log.w(TAG, "write external Application cache failed", it)
        }
    }

    private fun entryCache(dataRoot: File): File =
        File(dataRoot, ".nestspace/$CACHE_FILE")

    private fun cacheKey(baseApkPath: String): String {
        val apk = File(baseApkPath)
        return "$baseApkPath:${apk.length()}:${apk.lastModified()}"
    }
}
