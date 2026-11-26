package com.coke.nestspace.container.engine

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.control.ContainerUiKeepAlive
import com.coke.nestspace.core.files.safePathSegment
import java.io.File

internal class InstanceRuntimeCoordinator(
    private val context: Context,
    private val dataRootDir: File
) {
    fun finishPreviousHostTasks(slot: Int) {
        val hostNames = setOf(
            ContainerProcessPool.activityClass(slot).name,
            ContainerProcessPool.isolatedActivityClass(slot).name
        )
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        manager.appTasks.orEmpty().forEach { task ->
            val info = runCatching { task.taskInfo }.getOrNull() ?: return@forEach
            val matchesHost = listOfNotNull(
                info.baseActivity?.className,
                info.topActivity?.className
            ).any { it in hostNames }
            if (!matchesHost) {
                return@forEach
            }
            Log.d(TAG, "finish old host task ${info.taskId} for slot $slot")
            runCatching { task.finishAndRemoveTask() }
        }
    }

    fun closeInstanceRuntime(packageName: String, instanceId: String) {
        val closedSlots = ContainerProcessPool.closeInstance(
            context,
            packageName,
            instanceId
        )
        closedSlots.forEach { slot ->
            ContainerUiKeepAlive.unbind(context, slot)
            finishPreviousHostTasks(slot)
        }
    }

    fun ensureInstanceDirs(packageName: String, instanceId: String) {
        val root = dataDirFor(packageName, instanceId)
        listOf(
            "files",
            "cache",
            "code_cache",
            "no_backup",
            "databases",
            "shared_prefs",
            "obb"
        ).forEach { File(root, it).mkdirs() }
    }

    fun dataDirFor(packageName: String): File =
        File(dataRootDir, safePathSegment(packageName))

    fun dataDirFor(packageName: String, instanceId: String): File =
        File(dataDirFor(packageName), safePathSegment(instanceId))

    private companion object {
        const val TAG = "MiniContainerEngine"
    }
}
