package com.coke.nestspace.container

import com.coke.nestspace.container.loader.ContainerClassLoader


import android.content.Context
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Virtual apps run in the host process, but some framework calls still enforce
 * the host package/UID (for example AppWidgetManager rejects the target package
 * name). Those failures arrive as uncaught exceptions on target-created
 * background threads and would kill the whole container.
 *
 * A thread whose context ClassLoader is a ContainerClassLoader belongs to a
 * virtual app. Log its uncaught exceptions instead of letting them terminate
 * the host process; host threads keep the default behavior.
 *
 * Some target apps install their own uncaught handler during Application
 * creation and call System.exit() from it. install(force = true) is called
 * again after Application.onCreate so this guard stays in front of it.
 */
object TargetCrashGuard {

    private const val TAG = "TargetCrashGuard"
    private val lock = Any()

    @Volatile
    private var installed = false

    private var guard: Thread.UncaughtExceptionHandler? = null
    private var hostHandler: Thread.UncaughtExceptionHandler? = null
    private var preHandlerInstalled = false

    @Volatile
    private var crashContext: Context? = null

    @Volatile
    private var targetPackage: String? = null

    @Volatile
    private var targetInstance: String? = null

    /**
     * Registers the running virtual app so swallowed failures can leave a
     * crash marker for the main process (crash isolation UX).
     */
    fun noteSession(packageName: String, instanceId: String, context: Context) {
        targetPackage = packageName
        targetInstance = instanceId
        crashContext = context.applicationContext
    }

    fun install(force: Boolean = false) {
        synchronized(lock) {
            if (installed && !force) return
            installPreHandler()
            if (hostHandler == null) {
                val current = Thread.getDefaultUncaughtExceptionHandler()
                if (current !== guard) hostHandler = current
            }
            val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
                if (isTargetFailure(throwable) && causedByTarget(thread, throwable)) {
                    if (TargetFragmentPagerHook.recoverFromFragmentAlreadyAdded(throwable)) {
                        Log.w(
                            TAG,
                            "recovered FragmentPagerAdapter duplicate add on ${thread.name}"
                        )
                        return@UncaughtExceptionHandler
                    }
                    Log.e(
                        TAG,
                        "swallowed target crash on ${thread.name}: ${throwable.javaClass.name}",
                        throwable
                    )
                    recordCrash(thread, throwable)
                    if (TargetCrashContainment.shouldTerminate(
                            thread,
                            Looper.getMainLooper().thread
                        )
                    ) {
                        // Main-thread exceptions are fatal under normal Android
                        // lifecycle rules. Keeping the slot alive after one
                        // leaves a half-initialized UI that can later freeze
                        // the whole container. Record the marker and let the
                        // process pool restart this slot on the next launch.
                        Log.e(
                            TAG,
                            "terminating target slot after main-thread crash"
                        )
                        Process.killProcess(Process.myPid())
                    }
                } else {
                    hostHandler?.uncaughtException(thread, throwable)
                }
            }
            guard = handler
            Thread.setDefaultUncaughtExceptionHandler(handler)
            installed = true
        }
    }

    /**
     * Some packed applications replace the default handler and then exit on
     * uncaught failures without logging the original exception. ART exposes a
     * pre-handler that runs before the application-owned handler; it is used
     * only for diagnostics so process containment policy remains unchanged.
     */
    private fun installPreHandler() {
        if (preHandlerInstalled) return
        val method = runCatching {
            Thread::class.java.getDeclaredMethod(
                "setUncaughtExceptionPreHandler",
                Thread.UncaughtExceptionHandler::class.java
            ).apply { isAccessible = true }
        }.onFailure {
            Log.w(TAG, "uncaught pre-handler unavailable", it)
        }.getOrNull() ?: return
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            if (isTargetFailure(throwable) && causedByTarget(thread, throwable)) {
                Log.e(
                    TAG,
                    "target uncaught before application handler on ${thread.name}: " +
                        throwable.javaClass.name,
                    throwable
                )
                recordCrash(thread, throwable)
            }
        }
        runCatching {
            method.invoke(null, handler)
            preHandlerInstalled = true
        }.onFailure {
            Log.w(TAG, "install uncaught pre-handler failed", it)
        }
    }

    /**
     * Virtual apps commonly fail with LinkageError while probing optional
     * platform libraries (NoClassDefFoundError); those failures concern only
     * the target and should not tear down every other virtual app in the
     * process. VM-level failures such as OutOfMemoryError still go to the host.
     */
    private fun recordCrash(thread: Thread, throwable: Throwable) {
        val context = crashContext ?: return
        val packageName = targetPackage ?: return
        val instanceId = targetInstance ?: return
        ContainerCrashStore.record(
            context,
            packageName,
            instanceId,
            "uncaught ${throwable.javaClass.name} on ${thread.name}",
            throwable
        )
    }

    private fun isTargetFailure(throwable: Throwable): Boolean =
        throwable is Exception ||
            throwable is LinkageError ||
            throwable is AssertionError

    /**
     * Target-created threads (thread pools, split installers) usually inherit
     * the host context ClassLoader, so the thread alone is not enough. Also
     * treat a failure as target-owned when the exception class or any stack
     * frame was loaded from a target APK.
     */
    private fun causedByTarget(thread: Thread, throwable: Throwable): Boolean {
        if (thread.contextClassLoader is ContainerClassLoader) return true
        if (throwable.javaClass.classLoader is ContainerClassLoader) return true
        val packagePrefix = targetPackage?.let { "$it." }
        var current: Throwable? = throwable
        while (current != null) {
            if (current.stackTrace.any { frame ->
                    frame.className in ContainerClassLoader.loadedClasses ||
                        (packagePrefix != null && frame.className.startsWith(packagePrefix))
                }
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}

internal object TargetCrashContainment {
    fun shouldTerminate(thread: Thread, mainThread: Thread): Boolean =
        thread === mainThread
}
