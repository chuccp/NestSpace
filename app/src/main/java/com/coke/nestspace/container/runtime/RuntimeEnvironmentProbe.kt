package com.coke.nestspace.container.runtime

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Emits the process-facing identity that target code will observe. This is a
 * diagnostic probe for finding the first point where direct-install and
 * virtual-install startup state diverge.
 */
object RuntimeEnvironmentProbe {
    private const val TAG = "RuntimeEnvironmentProbe"

    fun log(stage: String, context: Context) {
        fun read(path: String): String = runCatching {
            File(path).readText().trim('\u0000', '\n', ' ')
        }.getOrDefault("unavailable")
        val status = runCatching {
            File("/proc/self/status").readLines()
                .filter {
                    it.startsWith("Name:") ||
                        it.startsWith("Uid:") ||
                        it.startsWith("Gid:") ||
                        it.startsWith("Groups:")
                }
                .joinToString("; ")
        }.getOrDefault("unavailable")

        val proc = linkedMapOf(
            "status" to status,
            "attr" to read("/proc/self/attr/current"),
            "cgroup" to read("/proc/self/cgroup"),
            "uid_map" to read("/proc/self/uid_map"),
            "gid_map" to read("/proc/self/gid_map"),
            "stat" to read("/proc/self/stat")
        )
        val info = context.applicationInfo
        val packageSnapshot = runCatching {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                0
            )
            val appInfo = packageInfo.applicationInfo
            val launch = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.component
            "versionName=${packageInfo.versionName} " +
                "versionCode=${packageInfo.longVersionCode} " +
                "uid=${appInfo?.uid} sourceDir=${appInfo?.sourceDir} " +
                "dataDir=${appInfo?.dataDir} " +
                "nativeLibraryDir=${appInfo?.nativeLibraryDir} " +
                "processName=${appInfo?.processName} " +
                "launch=$launch"
        }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
        val activityThread = runCatching {
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getMethod("currentActivityThread").invoke(null)
            fun value(method: String): Any? = runCatching {
                type.getMethod(method).invoke(thread)
            }.getOrNull()
            val fields = type.declaredFields
                .filter {
                    it.type == String::class.java &&
                        (it.name.contains("op", true) ||
                            it.name.contains("package", true))
                }
                .joinToString(",") { field ->
                    runCatching {
                        field.isAccessible = true
                        "${field.name}=${field.get(thread)}"
                    }.getOrDefault("${field.name}=?")
                }
            "package=${value("currentPackageName")} " +
                "process=${value("currentProcessName")} " +
                "opPackage=${value("currentOpPackageName")} fields=$fields"
        }.getOrDefault("unavailable")
        val paths = linkedMapOf(
            "dataDir" to context.dataDir.absolutePath,
            "filesDir" to context.filesDir.absolutePath,
            "cacheDir" to context.cacheDir.absolutePath,
            "codeCacheDir" to context.codeCacheDir.absolutePath,
            "databaseDir" to context.getDatabasePath("probe.db").parent,
            "noBackupDir" to context.noBackupFilesDir.absolutePath,
            "obbDir" to context.obbDir.absolutePath,
            "externalFiles" to context.getExternalFilesDir(null)?.absolutePath,
            "externalCache" to context.externalCacheDir?.absolutePath,
            "deviceProtected" to context
                .createDeviceProtectedStorageContext()
                .dataDir
                .absolutePath
        )
        Log.d(
            TAG,
            "$stage javaUid=${Process.myUid()} " +
                "package=${context.packageName} " +
                "applicationUid=${info.uid} sourceDir=${info.sourceDir} " +
                "publicSourceDir=${info.publicSourceDir} " +
                "nativeLibraryDir=${info.nativeLibraryDir} " +
                "dataDir=${info.dataDir} " +
                "deviceProtectedDataDir=${info.deviceProtectedDataDir} " +
                "splitSourceDirs=${info.splitSourceDirs?.contentToString()} " +
                "packageManager={$packageSnapshot} " +
                "activityThread={$activityThread} paths=$paths proc=$proc"
        )
    }
}
