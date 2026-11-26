package com.coke.nestspace.container.runtime

import com.coke.nestspace.container.ContainerLaunchSpec
import android.os.Build
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
        // Android's package manager stores current installs below two
        // URL-safe Base64 path tokens. Keeping the same shape matters to
        // integrity SDKs that validate packageManager/ApplicationInfo and
        // /proc maps against the platform's installation layout.
        val installToken = androidPathToken(
            sha256("$fingerprint|install")
        )
        val packageToken = androidPathToken(
            sha256("$fingerprint|package")
        )
        val installRoot = "/data/app/~~$installToken/" +
            "$packageName-$packageToken"
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

    /**
     * 128 bits encoded with the URL-safe alphabet used by package manager
     * install directory names. Manual encoding keeps the implementation
     * available below API 26, where java.util.Base64 is unavailable.
     */
    internal fun androidPathToken(fingerprint: String): String {
        val alphabet =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bytes = ByteArray(16) { index ->
            fingerprint.substring(index * 2, index * 2 + 2)
                .toInt(16)
                .toByte()
        }
        val encoded = buildString(22) {
            var bitBuffer = 0
            var bitCount = 0
            for (byte in bytes) {
                bitBuffer = (bitBuffer shl 8) or (byte.toInt() and 0xff)
                bitCount += 8
                while (bitCount >= 6) {
                    bitCount -= 6
                    append(alphabet[(bitBuffer shr bitCount) and 0x3f])
                }
                if (bitCount == 0) {
                    bitBuffer = 0
                } else {
                    bitBuffer = bitBuffer and ((1 shl bitCount) - 1)
                }
            }
            if (bitCount > 0) {
                append(alphabet[(bitBuffer shl (6 - bitCount)) and 0x3f])
            }
        }
        return "$encoded=="
    }
}
