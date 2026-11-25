package com.coke.nestspace.container.runtime

import android.os.Process
import android.os.SystemClock
import com.coke.nestspace.container.ContainerLaunchSpec
import com.coke.nestspace.container.identity.ContainerIdentity
import java.io.File

enum class RuntimeCapability {
    NATIVE_UID_FACADE,
    NATIVE_PROC_VIEW,
    NATIVE_PROPERTY_VIEW,
    MULTIPROCESS_COMPONENTS,
    DEVICE_PROTECTED_STORAGE,
    EXTERNAL_STORAGE,
    ACCOUNT_KEYSTORE_BASIC,
    NATIVE_BRIDGE,
    NATIVE_UID_PROBE,
    NATIVE_PROC_STATUS_PROBE,
    NATIVE_PROC_MAPS_PROBE,
    NATIVE_PROPERTY_PROBE,
    SUBPROCESS_SLOTS,
    NATIVE_DIRECT_SYSCALL_PROBE,
    NATIVE_PROC_MOUNTINFO_PROBE,
    NATIVE_SELINUX_PROBE,
    ACCOUNT_BASIC,
    PLUGIN_CLASS_PATH
}

/**
 * Immutable process-local view of the Android environment exposed to a target
 * instance. Dynamic DID/IID values live in [VirtualIdentitySnapshot] because
 * target SDKs discover and update them asynchronously.
 */
data class VirtualEnvironment(
    val instanceId: String,
    val packageName: String,
    val hostUid: Int,
    val observedUid: Int,
    val virtualPid: Int,
    val dataRoot: String,
    val externalDataRoot: String? = null,
    val deviceProtectedDataRoot: String,
    val filesDir: String,
    val cacheDir: String,
    val codeCacheDir: String,
    val apkPaths: List<String>,
    val splitApkPaths: List<String>,
    val nativeLibraryDir: String?,
    val processName: String,
    val versionName: String?,
    val versionCode: Long,
    val properties: Map<String, String>,
    val selinuxContext: String?,
    val capabilities: Set<RuntimeCapability>,
    val bootTimeMillis: Long,
    val installTimeMillis: Long,
    val updateTimeMillis: Long,
    val virtualApkPaths: List<String> = apkPaths,
    val virtualSplitApkPaths: List<String> = splitApkPaths,
    val virtualNativeLibraryDir: String? = nativeLibraryDir,
    val supplementaryGroups: List<Int> = emptyList()
)

data class VirtualIdentitySnapshot(
    val revision: Long,
    val androidId: String?,
    val clientUdid: String?,
    val deviceId: String?,
    val installId: String?,
    val source: String
) {
    companion object {
        fun uninitialized(): VirtualIdentitySnapshot = VirtualIdentitySnapshot(
            revision = 0,
            androidId = null,
            clientUdid = null,
            deviceId = null,
            installId = null,
            source = "uninitialized"
        )
    }
}

internal object VirtualEnvironmentFactory {
    private fun observedUid(spec: ContainerLaunchSpec): Int {
        return ContainerIdentity.hostUid()
    }

    fun create(
        spec: ContainerLaunchSpec,
        nativeLibraryDir: String?,
        runtimeCapabilities: RuntimeCapabilityReport,
        installLayout: VirtualInstallLayout,
        packageGids: List<Int> = emptyList()
    ): VirtualEnvironment {
        val dataRoot = File(spec.dataDir)
        val now = System.currentTimeMillis()
        val targetUid = observedUid(spec)
        val supplementaryGroups = buildList {
            addAll(packageGids)
            add(9_997)
            // Android derives these two shared-app groups from the app id.
            add(targetUid + 10_000)
            add(targetUid + 40_000)
        }.distinct()
        return VirtualEnvironment(
            instanceId = spec.instanceId,
            packageName = spec.packageName,
            hostUid = ContainerIdentity.hostUid(),
            observedUid = targetUid,
            virtualPid = Process.myPid(),
            dataRoot = dataRoot.absolutePath,
            externalDataRoot = VirtualStorageLayout
                .externalInstanceRoot(spec.packageName, spec.instanceId)
                .absolutePath,
            deviceProtectedDataRoot = VirtualStorageLayout
                .deviceProtectedRoot(dataRoot)
                .absolutePath,
            filesDir = File(dataRoot, "files").absolutePath,
            cacheDir = File(dataRoot, "cache").absolutePath,
            codeCacheDir = File(dataRoot, "code_cache").absolutePath,
            apkPaths = listOf(spec.baseApkPath) + spec.splitApkPaths,
            splitApkPaths = spec.splitApkPaths,
            nativeLibraryDir = nativeLibraryDir,
            processName = spec.targetProcessName,
            versionName = spec.packageInfo.versionName,
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                spec.packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                spec.packageInfo.versionCode.toLong()
            },
            properties = runtimeCapabilities.properties,
            selinuxContext = null,
            capabilities = runtimeCapabilities.capabilities,
            bootTimeMillis = now - SystemClock.elapsedRealtime(),
            installTimeMillis = spec.packageInfo.firstInstallTime,
            updateTimeMillis = spec.packageInfo.lastUpdateTime,
            virtualApkPaths = installLayout.virtualApkPaths,
            virtualSplitApkPaths = installLayout.virtualSplitApkPaths,
            virtualNativeLibraryDir = installLayout.virtualNativeLibraryDir,
            supplementaryGroups = supplementaryGroups
        )
    }
}
