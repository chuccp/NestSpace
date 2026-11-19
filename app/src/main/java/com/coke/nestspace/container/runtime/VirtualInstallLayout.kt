package com.coke.nestspace.container.runtime

import android.os.Build
import com.coke.nestspace.container.ContainerLaunchSpec
import java.io.File
import java.security.MessageDigest

/**
 * Stable package-install view exposed to the target app. Runtime file APIs map
 * these virtual paths back to the imported APK files inside NestSpace.
 */
data class VirtualInstallLayout(
    val actualBaseApkPath: String,
    val virtualBaseApkPath: String,
    val actualSplitApkPaths: List<String>,
    val virtualSplitApkPaths: List<String>,
    val actualNativeLibraryDir: String? = null,
    val virtualNativeLibraryDir: String? = null
) {
    val actualApkPaths: List<String>
        get() = listOf(actualBaseApkPath) + actualSplitApkPaths

    val virtualApkPaths: List<String>
        get() = listOf(virtualBaseApkPath) + virtualSplitApkPaths
}

object VirtualInstallLayoutFactory {
    fun create(
        spec: ContainerLaunchSpec,
        actualNativeLibraryDir: String? = null,
        nativeAbi: String? = null
    ): VirtualInstallLayout {
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            spec.packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            spec.packageInfo.versionCode.toLong()
        }
        return create(
            packageName = spec.packageName,
            versionCode = versionCode,
            signature = spec.packageInfo.signatures
                ?.joinToString("|") { it.toCharsString() }
                .orEmpty(),
            baseApkPath = spec.baseApkPath,
            splitApkPaths = spec.splitApkPaths,
            actualNativeLibraryDir = actualNativeLibraryDir,
            nativeAbi = nativeAbi
        )
    }

    internal fun create(
        packageName: String,
        versionCode: Long,
        signature: String,
        baseApkPath: String,
        splitApkPaths: List<String>,
        actualNativeLibraryDir: String? = null,
        nativeAbi: String? = null
    ): VirtualInstallLayout {
        val fingerprint = sha256(
            "$packageName|$versionCode|$signature"
        )
        val installRoot = "/data/app/${fingerprint.take(16)}/" +
            "$packageName-${fingerprint.take(16)}"
        // When the imported APK is the system's real installed copy, use its
        // actual /data/app path. Packers often reject a synthetic package path
        // that looks valid but does not exist on the filesystem.
        val installedBase = baseApkPath.takeIf {
            it.startsWith("/data/app/") && it.endsWith("/base.apk")
        }
        val virtualBase = installedBase ?: "$installRoot/base.apk"
        val actualSplits = splitApkPaths.filter(String::isNotBlank)
        val virtualSplits = actualSplits.map { actual ->
            if (installedBase != null && actual.startsWith("/data/app/")) {
                actual
            } else {
                "$installRoot/${File(actual).name}"
            }
        }
        val virtualNativeLibraryDir = actualNativeLibraryDir
            ?.takeIf(String::isNotBlank)
            ?.let { actual ->
                if (installedBase != null) {
                    actual
                } else {
                    "$installRoot/lib/${nativeAbiDirectory(nativeAbi)}"
                }
            }
        return VirtualInstallLayout(
            actualBaseApkPath = baseApkPath,
            virtualBaseApkPath = virtualBase,
            actualSplitApkPaths = actualSplits,
            virtualSplitApkPaths = virtualSplits,
            actualNativeLibraryDir = actualNativeLibraryDir,
            virtualNativeLibraryDir = virtualNativeLibraryDir
        )
    }

    private fun nativeAbiDirectory(abi: String?): String = when (abi) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> abi?.takeIf(String::isNotBlank) ?: "arm64"
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
