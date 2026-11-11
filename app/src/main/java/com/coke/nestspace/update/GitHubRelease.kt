package com.coke.nestspace.update

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class GitHubReleaseAsset(
    val name: String,
    val downloadUrl: String
)

data class GitHubRelease(
    val version: String,
    val tagName: String,
    val releaseUrl: String,
    val releaseNotes: String,
    val assets: List<GitHubReleaseAsset>
)

internal class GitHubReleaseClient(
    private val client: OkHttpClient = OkHttpClient()
) {
    fun latestRelease(): GitHubRelease {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NestSpace-Android")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(
                    "GitHub release request failed with HTTP ${response.code}"
                )
            }
            if (body.isBlank()) {
                throw IOException("GitHub release response was empty")
            }
            return parseGitHubRelease(body)
        }
    }

    companion object {
        const val REPOSITORY = "chuccp/NestSpace"
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/$REPOSITORY/releases/latest"
    }
}

internal fun parseGitHubRelease(body: String): GitHubRelease {
    val json = JSONObject(body)
    val tagName = json.optString("tag_name").trim()
    require(tagName.isNotBlank()) { "GitHub release has no tag name" }
    val assetsJson = json.optJSONArray("assets")
    val assets = buildList {
        if (assetsJson != null) {
            for (index in 0 until assetsJson.length()) {
                val asset = assetsJson.optJSONObject(index) ?: continue
                val name = asset.optString("name").trim()
                val downloadUrl = asset.optString("browser_download_url").trim()
                if (name.isNotBlank() && downloadUrl.isNotBlank()) {
                    add(GitHubReleaseAsset(name, downloadUrl))
                }
            }
        }
    }
    return GitHubRelease(
        version = AppVersion.normalize(tagName),
        tagName = tagName,
        releaseUrl = json.optString("html_url").trim(),
        releaseNotes = json.optString("body").trim(),
        assets = assets
    )
}

/**
 * The release workflow publishes these three APK variants. Prefer the APK
 * matching the installed host ABI, then the universal package.
 */
fun preferredApkDownload(release: GitHubRelease, primaryAbi: String?): String? {
    val apkAssets = release.assets.filter {
        it.name.endsWith(".apk", ignoreCase = true)
    }
    val preferredName = when {
        primaryAbi?.startsWith("arm64", ignoreCase = true) == true -> "arm64"
        primaryAbi?.startsWith("x86", ignoreCase = true) == true -> "x86_64"
        else -> "universal"
    }
    return (
        apkAssets.firstOrNull {
            it.name.contains(preferredName, ignoreCase = true)
        } ?: apkAssets.firstOrNull {
            it.name.contains("universal", ignoreCase = true)
        } ?: apkAssets.firstOrNull()
        )?.downloadUrl
}
