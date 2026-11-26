package com.coke.nestspace.container.application

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.coke.nestspace.container.ContainerCompat
import com.coke.nestspace.container.ContainerContext
import com.coke.nestspace.runtime.RuntimeApplicationSpec

/**
 * Target Application construction backend. All hardened-Application detection
 * and manual attach rules live here; the container session calls this factory
 * directly.
 */
object TargetApplicationFactory {
    private const val TAG = "CompatAppFactory"

    fun create(
        spec: RuntimeApplicationSpec,
        loader: ClassLoader,
        containerContext: Context,
        hardenedAttachContext: Context = containerContext
    ): Application {
        val bootstrap = runCatching {
            ApplicationBootstrapResolver.resolve(spec, loader)
        }.onFailure {
            Log.w(TAG, "Application bootstrap detection failed", it)
        }.getOrDefault(ApplicationBootstrap.ManifestApplication)

        return when (bootstrap) {
            is ApplicationBootstrap.EmbeddedApplication -> runCatching {
                attachRegularApplication(
                    spec,
                    loader,
                    containerContext,
                    bootstrap.className
                )
            }.onFailure {
                Log.w(TAG, "embedded Application init failed: ${bootstrap.className}", it)
            }.getOrElse {
                if (bootstrap.allowManifestFallback) {
                    attachRegularApplication(spec, loader, containerContext)
                } else {
                    throw IllegalStateException(
                        "unsupported hardened Application: ${bootstrap.className}",
                        it
                    )
                }
            }
            is ApplicationBootstrap.DelegatingApplication -> runCatching {
                attachDelegatingApplication(bootstrap, containerContext)
            }.onFailure {
                Log.w(TAG, "delegating Application init failed", it)
            }.getOrElse {
                if (bootstrap.allowManifestFallback) {
                    attachRegularApplication(spec, loader, containerContext)
                } else {
                    throw IllegalStateException(
                        "unsupported delegating Application: ${bootstrap.wrapperClass.name}",
                        it
                    )
                }
            }
            is ApplicationBootstrap.ApplicationLikePair -> runCatching {
                attachApplicationLikePair(bootstrap, containerContext)
            }.onFailure {
                Log.w(TAG, "ApplicationLike pair attach failed", it)
            }.getOrElse {
                if (bootstrap.allowManifestFallback) {
                    attachRegularApplication(spec, loader, containerContext)
                } else {
                    throw IllegalStateException(
                        "unsupported ApplicationLike pair: " +
                            "${bootstrap.likeClass.name} -> ${bootstrap.realClass.name}",
                        it
                    )
                }
            }
            is ApplicationBootstrap.UnsupportedHardenedApplication ->
                error(
                    "unsupported hardened Application ${bootstrap.className}: " +
                        bootstrap.reason
                )
            ApplicationBootstrap.ManifestApplication ->
                attachRegularApplication(
                    spec,
                    loader,
                    if (ExternalApplicationBootstrap.isExternalApplication(
                            spec.applicationInfo.className,
                            spec.packageName,
                            listOf(spec.baseApkPath) + spec.splitApkPaths
                        )
                    ) {
                        hardenedAttachContext
                    } else {
                        containerContext
                    }
                )
        }
    }

    /**
     * MuteApplication forwards lifecycle callbacks to its delegate. Keep the
     * manifest wrapper as the process Application so framework-only fields
     * copied by Android remain available to startup tasks.
     */
    private fun attachDelegatingApplication(
        bootstrap: ApplicationBootstrap.DelegatingApplication,
        context: Context
    ): Application {
        val wrapper = bootstrap.wrapperClass.getDeclaredConstructor().newInstance()
        if (context is ContainerContext) {
            context.targetApplication = wrapper
        }
        attachBaseContext(wrapper, context)
        val delegate = findDelegatingApplication(wrapper)
            ?: error("delegating Application delegate was not created")
        if (context is ContainerContext) {
            context.targetApplication = delegate
        }
        Log.d(TAG, "delegating Application attached: ${delegate.javaClass.name}")
        return wrapper
    }

    private fun findDelegatingApplication(wrapper: Application): Application? {
        var current: Class<*>? = wrapper.javaClass
        while (current != null) {
            val fields = current.declaredFields
                .filter { Application::class.java.isAssignableFrom(it.type) }
                .sortedByDescending {
                    it.name.contains("delegate", ignoreCase = true)
                }
            fields.forEach { field ->
                val delegate = runCatching {
                    field.isAccessible = true
                    field.get(wrapper) as? Application
                }.getOrNull()
                if (delegate != null && delegate !== wrapper) return delegate
            }
            current = current.superclass
        }
        return null
    }

    private fun attachApplicationLikePair(
        bootstrap: ApplicationBootstrap.ApplicationLikePair,
        context: Context
    ): Application {
        primePatcherSingleton(bootstrap.patcherPrimerClass, context)
        val base = Application()
        if (context is ContainerContext) {
            context.targetApplication = base
        }
        attachBaseContext(base, context)
        val appLike = ApplicationBootstrapResolver.createApplicationLike(
            bootstrap.likeClass,
            base
        )
        val real = ApplicationBootstrapResolver.createRealApplication(
            bootstrap.realClass,
            base,
            appLike
        )
        if (context is ContainerContext) {
            context.targetApplication = real
        }
        attachBaseContext(real, context)
        ContainerCompat.rememberAppLike(real, appLike)
        Log.d(TAG, "embedded application attached: ${real.javaClass.name}")
        return real
    }

    private fun primePatcherSingleton(
        patcherClass: Class<out Application>?,
        context: Context
    ) {
        patcherClass ?: return
        runCatching {
            val patcher = patcherClass.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
            android.content.ContextWrapper::class.java
                .getDeclaredField("mBase")
                .apply { isAccessible = true }
                .set(patcher, context)
            Log.d(TAG, "primed patcher singleton ${patcherClass.name}")
        }.onFailure {
            Log.w(TAG, "prime patcher singleton failed", it)
        }
    }

    private fun attachRegularApplication(
        spec: RuntimeApplicationSpec,
        loader: ClassLoader,
        context: Context,
        classNameOverride: String? = null
    ): Application {
        val className = classNameOverride
            ?: spec.applicationInfo.className?.takeIf { it.isNotBlank() }
            ?: Application::class.java.name
        val appClass = loader.loadClass(className).asSubclass(Application::class.java)
        val application = appClass.getDeclaredConstructor().newInstance()
        if (context is ContainerContext) {
            context.targetApplication = application
        }
        attachBaseContext(application, context)
        return application
    }

    private fun attachBaseContext(application: Application, context: Context) {
        var type: Class<*>? = application.javaClass
        while (type != null) {
            val attachBase = runCatching {
                type.getDeclaredMethod("attachBaseContext", Context::class.java)
            }.getOrNull()
            if (attachBase != null) {
                attachBase.isAccessible = true
                val started = SystemClock.elapsedRealtime()
                Log.d(
                    TAG,
                    "attachBaseContext start application=${application.javaClass.name} " +
                        "context=${context.javaClass.name} package=${context.packageName} " +
                        "files=${context.filesDir} data=${context.applicationInfo.dataDir} " +
                        "loader=${context.classLoader.javaClass.name}"
                )
                attachBase.invoke(application, context)
                Log.d(
                    TAG,
                    "attachBaseContext done application=${application.javaClass.name} " +
                        "took=${SystemClock.elapsedRealtime() - started}ms"
                )
                return
            }
            type = type.superclass
        }
        error("Application.attachBaseContext not found for ${application.javaClass.name}")
    }
}
