package com.coke.nestspace.container.session

import com.coke.nestspace.container.ContainerSession
import android.util.Log
import com.coke.nestspace.container.application.ExternalApplicationBootstrap
import com.coke.nestspace.container.runtime.NativeEnvironmentBridge
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

internal class NativeHookPump(
    private val afterLaunchRefresh: () -> Unit
) {
    private var lastHookCount = -1
    private var lastScannedCount = -1
    private var lastTargetModuleCount = -1

    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "nestspace-native-hook-pump").apply { isDaemon = true }
        }
    }

    fun refresh(scanElfFallback: Boolean = false) {
        if (ExternalApplicationBootstrap.lastRealApplication != null) return
        val snapshot = NativeEnvironmentBridge.refreshHooks(scanElfFallback) ?: return
        if (snapshot.hookCount == lastHookCount &&
            snapshot.scannedModuleCount == lastScannedCount &&
            snapshot.targetModuleCount == lastTargetModuleCount
        ) {
            return
        }
        lastHookCount = snapshot.hookCount
        lastScannedCount = snapshot.scannedModuleCount
        lastTargetModuleCount = snapshot.targetModuleCount
        Log.d(
            TAG,
            "native hooks patched=${snapshot.hookCount} " +
                "modules=${snapshot.hookedModuleCount} " +
                "scanned=${snapshot.scannedModuleCount} " +
                "targetModules=${snapshot.targetModuleCount} " +
                "errors=${snapshot.errorCount} " +
                "virtualProcReads=${snapshot.virtualProcReadCount} " +
                "propertyReads=${snapshot.propertyReadCount} " +
                "dlopenCalls=${snapshot.dlopenCallCount}"
        )
    }

    fun refreshAfterLaunch() {
        runCatching {
            executor.execute {
                runCatching { refresh(scanElfFallback = false) }
                    .onFailure { Log.w(TAG, "native hook refresh failed", it) }
                afterLaunchRefresh()
            }
        }.onFailure {
            Log.w(TAG, "post-launch diagnostics scheduling failed", it)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "ContainerSession"
    }
}
