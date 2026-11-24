package com.coke.nestspace.container.loader

import android.content.Context
import dalvik.system.PathClassLoader
import java.io.InputStream
import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * Child-first class loader for a virtual app.
 *
 * A normal PathClassLoader delegates to its parent first. Because NestSpace
 * itself bundles AndroidX, a target APK that also bundles AndroidX (very
 * common in real apps) would silently link against the host's version. That
 * breaks apps whose bundled version has different members.
 *
 * PathClassLoader (rather than DexClassLoader) is deliberate: split APK
 * frameworks commonly downcast the application class loader to PathClassLoader
 * to append split dex paths. Both loaders are
 * BaseDexClassLoader variants; PathClassLoader just matches what installed
 * apps expose.
 *
 * Try the target dex files first, then fall back to the host/system loader.
 */
class ContainerClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader
) : PathClassLoader(dexPath, librarySearchPath, parent),
    TargetContextInstaller {

    private val lock = Any()
    private val resources = ClassPathResourceFinder(dexPath)
    private val seededClasses =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var targetContext: Context? = null

    override fun installTargetContext(context: Context) {
        targetContext = context
        val loaded = synchronized(lock) {
            loadedClasses.toList().mapNotNull { name: String ->
                findLoadedClass(name)
            }
        }
        // Reflection can resolve field types and therefore call back into this
        // loader. Seeding while holding the loader lock creates a lock inversion
        // with class initialization on another thread.
        loaded.forEach { seedContext(it, it.name) }
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

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        val clazz = synchronized(lock) {
            var clazz = findLoadedClass(name)
            if (clazz == null) {
                // Some SDKs bundle compile-time stubs of framework classes.
                // Loading those instead of the real boot classes makes the SDK
                // throw RuntimeException("Stub!"). Framework namespaces always
                // come from the parent/boot loader.
                clazz = if (isPlatformClass(name)) {
                    super.loadClass(name, false)
                } else {
                    val target = runCatching { findClass(name) }.getOrNull()
                    if (target != null) {
                        loadedClasses.add(name)
                        target
                    } else {
                        super.loadClass(name, false)
                    }
                }
            }
            if (resolve) resolveClass(clazz)
            clazz
        }
        if (clazz.classLoader === this) {
            seedContext(clazz, name)
        }
        return clazz
    }

    private fun isPlatformClass(name: String): Boolean =
        name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("dalvik.") ||
            name.startsWith("android.") ||
            name.startsWith("libcore.") ||
            name.startsWith("sun.") ||
            name.startsWith("com.sun.") ||
            name.startsWith("org.w3c.") ||
            name.startsWith("org.xmlpull.")

    private fun seedContext(type: Class<*>, name: String) {
        val context = targetContext ?: return
        if (!seededClasses.add(name)) return
        TargetContextSeeder.seed(type, context)
    }

    companion object {
        /**
         * Names of classes actually loaded from a target APK. Target-created
         * threads often keep the host context ClassLoader, so crash guard
         * checks the stack trace against this set instead of the thread.
         */
        val loadedClasses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}

/**
 * Native libraries commonly capture
 * Thread.getContextClassLoader() during JNI initialization and use it to find
 * target-side classes. Target code executes on the host main thread, whose
 * context loader is NestSpace's own loader; without this swap the bridge sees
 * the host loader and disables target native modules.
 */
inline fun <T> withContainerClassLoader(loader: ClassLoader, block: () -> T): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    thread.contextClassLoader = loader
    return try {
        block()
    } finally {
        thread.contextClassLoader = previous
    }
}
