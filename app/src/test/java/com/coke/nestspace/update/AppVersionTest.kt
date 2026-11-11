package com.coke.nestspace.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun comparesReleaseTagsWithShortInstalledVersions() {
        assertTrue(AppVersion.isNewer("v1.0.7", "1.0"))
        assertTrue(AppVersion.isNewer("1.0.10", "1.0.9"))
        assertFalse(AppVersion.isNewer("v1.0", "1.0.0"))
        assertFalse(AppVersion.isNewer("1.0.6", "1.0.7"))
    }

    @Test
    fun stableReleaseSortsAfterPreRelease() {
        assertTrue(AppVersion.isNewer("v1.1.0", "v1.1.0-beta.1"))
        assertEquals(0, AppVersion.compare("V1.0.7+build", "1.0.7"))
    }

    @Test
    fun choosesApkVariantForInstalledAbi() {
        val release = GitHubRelease(
            version = "1.0.7",
            tagName = "v1.0.7",
            releaseUrl = "https://github.com/chuccp/NestSpace/releases/tag/v1.0.7",
            releaseNotes = "",
            assets = listOf(
                GitHubReleaseAsset("NestSpace-v1.0.7-arm64.apk", "arm64"),
                GitHubReleaseAsset("NestSpace-v1.0.7-universal.apk", "universal"),
                GitHubReleaseAsset("NestSpace-v1.0.7-x86_64.apk", "x86")
            )
        )

        assertEquals("arm64", preferredApkDownload(release, "arm64-v8a"))
        assertEquals("x86", preferredApkDownload(release, "x86_64"))
        assertEquals("universal", preferredApkDownload(release, "armeabi-v7a"))
    }

    @Test
    fun parsesLatestReleaseResponseAssets() {
        val release = parseGitHubRelease(
            """
            {
              "tag_name": "v1.0.7",
              "html_url": "https://github.com/chuccp/NestSpace/releases/tag/v1.0.7",
              "body": "更新内容",
              "assets": [
                {
                  "name": "NestSpace-v1.0.7-arm64.apk",
                  "browser_download_url": "https://example.com/arm64.apk"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("1.0.7", release.version)
        assertEquals("更新内容", release.releaseNotes)
        assertEquals(
            "https://example.com/arm64.apk",
            preferredApkDownload(release, "arm64-v8a")
        )
    }
}
