package com.coke.nestspace.container.application

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
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

    fun cachedEntryName(dataRoot: File, baseApkPath: String): String? =
        runCatching {
            val lines = entryCache(dataRoot).readLines()
            if (lines.size < 2 || lines[0] != cacheKey(baseApkPath)) {
                null
            } else {
                lines[1].trim().takeIf(String::isNotBlank)
            }
        }.getOrNull()

    fun attach(
        loader: ClassLoader,
        context: Context,
        wrapperClassName: String,
        targetPackageName: String,
        runtimeLibraries: RuntimeNativeLibraries,
        classNames: List<String>,
        baseApkPath: String,
        dataRoot: File
    ): Application? = runCatching {
        val wrapperClass = loader.loadClass(wrapperClassName)
            .asSubclass(Application::class.java)
        val wrapper = wrapperClass.getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        attachBaseContext(wrapper, context)

        val cachedEntry = cachedEntryName(dataRoot, baseApkPath)
        bindStaticValues(
            wrapperClass,
            wrapper,
            context,
            cachedEntry,
            runtimeLibraries
        )
        loadLibraries(runtimeLibraries)
        initializeLoaderClasses(loader, classNames)
        invokeApplicationLifecycle(wrapperClass, wrapper)

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

        attachBaseContext(real, context)
        invokeApplicationLifecycle(wrapperClass, real)
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

    private fun bindStaticValues(
        wrapperClass: Class<*>,
        wrapper: Application,
        context: Context,
        cachedEntry: String?,
        runtimeLibraries: RuntimeNativeLibraries
    ) {
        val libraryByBasename = runtimeLibraries.files.associateBy { it.name }
        staticFields(wrapperClass).forEach { field ->
            runCatching {
                val type = field.type
                val current = runCatching { field.get(null) }.getOrNull()
                when {
                    Application::class.java.isAssignableFrom(type) &&
                        current == null -> field.set(null, wrapper)
                    Context::class.java.isAssignableFrom(type) &&
                        current == null -> field.set(null, context)
                    type == String::class.java -> {
                        val value = current as? String
                        val name = field.name.lowercase()
                        val library = if (!value.isNullOrBlank()) {
                            libraryByBasename[value.substringAfterLast('/')]
                        } else {
                            null
                        }
                        when {
                            library != null -> field.set(null, library.absolutePath)
                            !cachedEntry.isNullOrBlank() &&
                                name.contains("entry") &&
                                name.contains("app") -> field.set(null, cachedEntry)
                            value.isNullOrBlank() &&
                                name.contains("lib") &&
                                name.contains("dir") ->
                                field.set(null, runtimeLibraries.root.absolutePath)
                        }
                    }
                }
            }.onFailure {
                Log.w(TAG, "bind external Application field failed: ${field.name}", it)
            }
        }
    }

    private fun initializeLoaderClasses(
        loader: ClassLoader,
        classNames: List<String>
    ) {
        classNames.asSequence()
            .filter { name ->
                val simple = name.substringAfterLast('.')
                simple.contains("Loader", ignoreCase = true)
            }
            .sortedWith(compareBy<String> { it.length }.thenBy { it })
            .forEach { name ->
                val type = runCatching { loader.loadClass(name) }.getOrNull()
                    ?: return@forEach
                val initializer = runCatching {
                    type.getDeclaredMethod("init").takeIf {
                        Modifier.isStatic(it.modifiers) &&
                            it.parameterCount == 0
                    }
                }.getOrNull() ?: return@forEach
                runCatching {
                    initializer.isAccessible = true
                    initializer.invoke(null)
                    Log.d(TAG, "initialized runtime loader: $name")
                }.onFailure {
                    Log.w(TAG, "runtime loader initialization failed: $name", it)
                }
            }
    }

    private fun loadLibraries(runtimeLibraries: RuntimeNativeLibraries) {
        runtimeLibraries.root.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".so", ignoreCase = true) }
            .sortedBy { it.name }
            .forEach { library ->
            runCatching {
                System.load(library.absolutePath)
            }.onFailure {
                Log.d(TAG, "runtime library not loadable: ${library.absolutePath}")
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

    private fun invokeApplicationLifecycle(
        wrapperClass: Class<*>,
        application: Application
    ) {
        staticMethods(wrapperClass)
            .filter { method ->
                method.parameterCount == 1 &&
                    Application::class.java.isAssignableFrom(
                        method.parameterTypes[0]
                    )
            }
            .sortedWith(compareBy<Method> { it.name }.thenBy { it.parameterCount })
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(null, application)
                }.onFailure {
                    Log.w(TAG, "external Application lifecycle hook failed: ${method.name}", it)
                }
            }
    }

    private fun attachBaseContext(application: Application, context: Context) {
        val baseField = ContextWrapper::class.java
            .getDeclaredField("mBase")
            .apply { isAccessible = true }
        val attachBase = attachBaseContextMethod(application.javaClass)
        val current = baseField.get(application)
        if (current == null && attachBase != null) {
            attachBase.invoke(application, context)
        }
        if (baseField.get(application) == null || baseField.get(application) !== context) {
            baseField.set(application, context)
        }
    }

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
