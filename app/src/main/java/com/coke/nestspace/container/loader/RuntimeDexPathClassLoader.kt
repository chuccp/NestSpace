package com.coke.nestspace.container.loader

import android.util.Log
import android.content.Context
import dalvik.system.DexClassLoader
import dalvik.system.InMemoryDexClassLoader
import dalvik.system.PathClassLoader
import android.os.Build
import java.io.File
import java.io.InputStream
import java.lang.reflect.Array as ReflectionArray
import java.net.URL
import java.nio.ByteBuffer
import java.util.Collections
import java.util.Enumeration

/**
 * A PathClassLoader that can see runtime-generated DEX while an Application
 * is still being attached. The imported APK remains the primary path;
 * generated DEX files are only used when a normal class lookup fails.
 */
class RuntimeDexPathClassLoader(
    dexPath: String,
    private val librarySearchPath: String?,
    parent: ClassLoader?,
    private val dataRoot: File,
    private val optimizedDirectory: File
) : PathClassLoader(dexPath, librarySearchPath, parent),
    TargetContextInstaller {
    private val lock = Any()
    private val resolvingFallback = ThreadLocal<Boolean>()
    private val resources = ClassPathResourceFinder(dexPath)
    private var mergedDexSignature: String? = null
    private var fallbackDexLoader: ClassLoader? = null
    private val hostClassLoader =
        RuntimeDexPathClassLoader::class.java.classLoader
    private val seededClasses =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val targetLoadedClasses =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var targetContext: Context? = null

    override fun installTargetContext(context: Context) {
        targetContext = context
        val loaded = synchronized(lock) {
            targetLoadedClasses.toList().mapNotNull { name: String ->
                findLoadedClass(name)
            }
        }
        loaded.forEach { seedContext(it, it.name) }
    }

    override fun findLibrary(name: String): String? {
        val resolved = super.findLibrary(name)
        val fallback = if (resolved == null) {
            findLibraryOnSearchPath(name)
        } else {
            null
        }
        Log.d(
            TAG,
            "find library name=$name path=${resolved ?: fallback ?: "<null>"}"
        )
        return resolved ?: fallback
    }

    override fun findResource(name: String): URL? =
        resources.findResource(name) ?: super.findResource(name)

    override fun findResources(name: String): Enumeration<URL> =
        resourceEnumeration(
            resources.findResources(name) +
                runCatching {
                    Collections.list(super.findResources(name))
                }.getOrDefault(emptyList())
        )

    override fun getResource(name: String): URL? =
        resources.findResource(name) ?: super.getResource(name)

    override fun getResourceAsStream(name: String): InputStream? =
        resources.openResource(name) ?: super.getResourceAsStream(name)

    private fun findLibraryOnSearchPath(name: String): String? {
        val fileName = System.mapLibraryName(name)
        return librarySearchPath
            .orEmpty()
            .split(File.pathSeparatorChar)
            .asSequence()
            .filter(String::isNotBlank)
            .map { File(it, fileName) }
            .firstOrNull(File::isFile)
            ?.absolutePath
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("com.coke.nestspace.")) {
            hostClassLoader?.let {
                return it.loadClass(name).also { type ->
                    noteTargetClass(type, name)
                    if (resolve) resolveClass(type)
                }
            }
        }
        if (resolvingFallback.get() == true) {
            return super.loadClass(name, resolve)
        }
        val loaded = try {
            super.loadClass(name, resolve)
        } catch (missing: ClassNotFoundException) {
            val fallback = installGeneratedDex()
                ?: run {
                    if (name.contains("Application", ignoreCase = true)) {
                        Log.w(
                            TAG,
                            "application class missing name=$name " +
                                "generatedDex=${generatedDexFiles().size}",
                            missing
                        )
                    }
                    throw missing
                }
            try {
                resolvingFallback.set(true)
                runCatching { super.loadClass(name, resolve) }
                    .getOrElse { fallback.loadClass(name) }
            } catch (fallbackMissing: ClassNotFoundException) {
                if (name.contains("Application", ignoreCase = true)) {
                    Log.w(
                        TAG,
                        "application class fallback missing name=$name " +
                            "generatedDex=${generatedDexFiles().size}",
                        fallbackMissing
                    )
                }
                fallbackMissing.addSuppressed(missing)
                throw fallbackMissing
            } finally {
                resolvingFallback.remove()
            }
        }
        noteTargetClass(loaded, name)
        if (loaded.classLoader === this && shouldSeedEarly(name)) {
            seedContext(loaded, name)
        }
        return loaded
    }

    private fun installGeneratedDex(): ClassLoader? {
        val dexFiles = generatedDexFiles()
        if (dexFiles.isEmpty()) return null
        val signature = dexFiles.joinToString("|") { file ->
            "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        }
        synchronized(lock) {
            if (signature == mergedDexSignature) return fallbackDexLoader
            val loader = runCatching {
                createGeneratedDexLoader(dexFiles)
            }.onFailure {
                Log.w(TAG, "create runtime DEX loader failed", it)
            }.getOrNull()
            if (loader != null) {
                runCatching {
                    mergeDexElements(loader)
                }.onSuccess {
                    Log.d(
                        TAG,
                        "merged ${dexFiles.size} generated DEX files into target loader"
                    )
                }.onFailure {
                    Log.w(TAG, "merge runtime DEX elements failed", it)
                }
            }
            mergedDexSignature = signature
            fallbackDexLoader = loader
            return loader
        }
    }

    private fun mergeDexElements(source: ClassLoader) {
        val targetPathList = findField(javaClass, "pathList")
            ?.apply { isAccessible = true }
            ?.get(this)
            ?: error("target pathList not found")
        val sourcePathList = findField(source.javaClass, "pathList")
            ?.apply { isAccessible = true }
            ?.get(source)
            ?: error("generated pathList not found")
        val targetField = findField(targetPathList.javaClass, "dexElements")
            ?.apply { isAccessible = true }
            ?: error("target dexElements not found")
        val sourceField = findField(sourcePathList.javaClass, "dexElements")
            ?.apply { isAccessible = true }
            ?: error("generated dexElements not found")
        val target = targetField.get(targetPathList) as Array<*>
        val generated = sourceField.get(sourcePathList) as Array<*>
        val component = target.javaClass.componentType
        val merged = ReflectionArray.newInstance(
            component,
            target.size + generated.size
        ) as Array<Any?>
        for (index in target.indices) {
            merged[index] = target[index]
        }
        for (index in generated.indices) {
            merged[target.size + index] = generated[index]
        }
        targetField.set(targetPathList, merged)
    }

    private fun findField(owner: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = owner
        while (current != null) {
            runCatching { current.getDeclaredField(name) }
                .getOrNull()
                ?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun createGeneratedDexLoader(dexFiles: List<File>): ClassLoader {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val buffers = dexFiles.map { file ->
                ByteBuffer.wrap(file.readBytes())
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                InMemoryDexClassLoader(buffers.toTypedArray(), this)
            } else {
                InMemoryDexClassLoader(buffers.first(), this)
            }
        }
        optimizedDirectory.mkdirs()
        return DexClassLoader(
            dexFiles.joinToString(File.pathSeparator) { it.absolutePath },
            optimizedDirectory.absolutePath,
            null,
            this
        )
    }

    private fun generatedDexFiles(): List<File> {
        return dataRoot.walkTopDown()
            .maxDepth(5)
            .onEnter { directory ->
                directory.name != "cache" &&
                    directory.name != "no_backup" &&
                    !directory.name.startsWith("process-dex-backup-") &&
                    !directory.name.startsWith("process-dex-stale-")
            }
            .filter { file ->
                file.isFile &&
                    file.extension.equals("dex", ignoreCase = true) &&
                    file.length() > 0L
            }
            .distinctBy(File::getAbsolutePath)
            .sortedBy(File::getAbsolutePath)
            .toList()
    }

    private fun seedContext(type: Class<*>, name: String) {
        val context = targetContext ?: return
        if (!seededClasses.add(name)) return
        TargetContextSeeder.seed(type, context)
    }

    private fun shouldSeedEarly(name: String): Boolean =
        name.endsWith("Builder") ||
            name.endsWith("ContextHolder") ||
            name.endsWith("ContextProvider")

    private fun noteTargetClass(type: Class<*>, name: String) {
        if (type.classLoader === this) targetLoadedClasses.add(name)
    }

    private companion object {
        const val TAG = "RuntimeDexLoader"
    }
}
