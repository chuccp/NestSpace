package com.coke.nestspace.container.loader

/**
 * Parent loader for a target app backed by the platform's real
 * PathClassLoader.
 *
 * Target classes deliberately do not see the host application classpath:
 * that would let host copies of AndroidX, Kotlin or networking libraries win
 * over the versions bundled in the target APK. Only the container's own
 * framework bridge is exposed here because framework instrumentation may ask
 * a target class loader to instantiate host activity slots while serving the
 * target application.
 */
internal class HostBootstrapClassLoader(
    private val hostLoader: ClassLoader,
    private val platformLoader: ClassLoader?
) : ClassLoader(platformLoader) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(this) {
            findLoadedClass(name)?.let {
                if (resolve) resolveClass(it)
                return it
            }
            val loaded = when {
                name.startsWith(HOST_PREFIX) -> hostLoader.loadClass(name)
                isPlatformClass(name) -> {
                    platformLoader?.loadClass(name)
                        ?: Class.forName(name, false, null)
                }
                else -> throw ClassNotFoundException(name)
            }
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }

    private fun isPlatformClass(name: String): Boolean =
        PLATFORM_PREFIXES.any(name::startsWith)

    private companion object {
        const val HOST_PREFIX = "com.coke.nestspace."

        val PLATFORM_PREFIXES = listOf(
            "java.",
            "javax.",
            "dalvik.",
            "android.",
            "libcore.",
            "sun.",
            "com.sun.",
            "com.android.",
            "org.json.",
            "org.w3c.",
            "org.xmlpull."
        )
    }
}
