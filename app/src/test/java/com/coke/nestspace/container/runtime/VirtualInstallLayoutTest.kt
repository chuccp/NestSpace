package com.coke.nestspace.container.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualInstallLayoutTest {
    @Test
    fun buildsStableAndroidInstallPathsForBaseAndSplits() {
        val first = VirtualInstallLayoutFactory.create(
            packageName = "com.example.target",
            versionCode = 42,
            signature = "signature",
            baseApkPath = "/data/user/0/host/files/vspace/app/base.apk",
            splitApkPaths = listOf(
                "/data/user/0/host/files/vspace/app/split_config.arm64_v8a.apk"
            )
        )
        val second = VirtualInstallLayoutFactory.create(
            packageName = "com.example.target",
            versionCode = 42,
            signature = "signature",
            baseApkPath = "/data/user/0/host/files/vspace/app/base.apk",
            splitApkPaths = listOf(
                "/data/user/0/host/files/vspace/app/split_config.arm64_v8a.apk"
            )
        )

        assertEquals(first, second)
        assertTrue(first.virtualBaseApkPath.startsWith("/data/app/"))
        assertTrue(first.virtualBaseApkPath.endsWith("/base.apk"))
        assertTrue(
            first.virtualBaseApkPath.matches(
                Regex(
                    "^/data/app/~~[A-Za-z0-9_-]{22}==/" +
                        "com\\.example\\.target-[A-Za-z0-9_-]{22}==/base\\.apk$"
                )
            )
        )
        assertEquals(
            "/split_config.arm64_v8a.apk",
            first.virtualSplitApkPaths.single()
                .substringAfter(first.virtualBaseApkPath.removeSuffix("/base.apk"))
        )
    }

    @Test
    fun changesInstallPathWhenPackageVersionChanges() {
        val first = VirtualInstallLayoutFactory.create(
            packageName = "com.example.target",
            versionCode = 42,
            signature = "signature",
            baseApkPath = "/data/local/base.apk",
            splitApkPaths = emptyList()
        )
        val second = VirtualInstallLayoutFactory.create(
            packageName = "com.example.target",
            versionCode = 43,
            signature = "signature",
            baseApkPath = "/data/local/base.apk",
            splitApkPaths = emptyList()
        )

        assertNotEquals(first.virtualBaseApkPath, second.virtualBaseApkPath)
    }

    @Test
    fun keepsNativeLibrariesInsideTheVirtualInstallRoot() {
        val layout = VirtualInstallLayoutFactory.create(
            packageName = "com.example.target",
            versionCode = 42,
            signature = "signature",
            baseApkPath = "/data/local/base.apk",
            splitApkPaths = emptyList(),
            actualNativeLibraryDir = "/data/user/0/host/cache/arm64",
            nativeAbi = "arm64-v8a"
        )

        assertEquals(
            layout.virtualBaseApkPath.substringBeforeLast("/"),
            layout.virtualNativeLibraryDir?.substringBeforeLast("/lib/")
        )
        assertTrue(
            layout.virtualNativeLibraryDir.orEmpty().endsWith("/lib/arm64")
        )
    }

    @Test
    fun encodesAndroidInstallTokensWithoutUnsafePathCharacters() {
        val token = VirtualInstallLayoutFactory.androidPathToken(
            "ffffffffffffffffffffffffffffffff"
        )

        assertEquals(24, token.length)
        assertTrue(token.endsWith("=="))
        assertTrue(
            token.removeSuffix("==").all {
                it.isLetterOrDigit() || it == '-' || it == '_'
            }
        )
    }

    @Test
    fun preservesRealInstalledPackagePaths() {
        val baseApk =
            "/data/app/~~real-hash==/com.example.target-signature==/base.apk"
        val nativeLibraryDir =
            "/data/app/~~real-hash==/com.example.target-signature==/lib/arm64"
        val layout = VirtualInstallLayoutFactory.create(
            packageName = "com.example.target",
            versionCode = 42,
            signature = "signature",
            baseApkPath = baseApk,
            splitApkPaths = emptyList(),
            actualNativeLibraryDir = nativeLibraryDir,
            nativeAbi = "arm64-v8a"
        )

        assertEquals(baseApk, layout.virtualBaseApkPath)
        assertEquals(nativeLibraryDir, layout.virtualNativeLibraryDir)
    }

}
