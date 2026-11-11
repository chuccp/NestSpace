package com.coke.nestspace.update

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class AppUpdateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun lastAutoCheckDate(): String? =
        preferences.getString(KEY_LAST_AUTO_CHECK_DATE, null)

    fun markAutoCheckAttempted(today: String) {
        preferences.edit().putString(KEY_LAST_AUTO_CHECK_DATE, today).apply()
    }

    fun promptedVersion(): String? =
        preferences.getString(KEY_PROMPTED_VERSION, null)

    fun markPrompted(version: String) {
        preferences.edit().putString(KEY_PROMPTED_VERSION, version).apply()
    }

    fun skippedVersion(): String? =
        preferences.getString(KEY_SKIPPED_VERSION, null)

    fun skipVersion(version: String) {
        preferences.edit().putString(KEY_SKIPPED_VERSION, version).apply()
    }

    fun clearSkippedVersion() {
        preferences.edit().remove(KEY_SKIPPED_VERSION).apply()
    }

    fun checkedAtMillis(): Long = preferences.getLong(KEY_CHECKED_AT, 0L)

    fun markCheckedAt(millis: Long) {
        preferences.edit().putLong(KEY_CHECKED_AT, millis).apply()
    }

    fun cachedRelease(): GitHubRelease? {
        val version = preferences.getString(KEY_RELEASE_VERSION, null) ?: return null
        val tagName = preferences.getString(KEY_RELEASE_TAG, null) ?: return null
        val releaseUrl = preferences.getString(KEY_RELEASE_URL, null) ?: return null
        val assets = preferences.getString(KEY_ASSETS, "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val fields = line.split(ASSET_SEPARATOR, limit = 2)
                if (fields.size == 2 && fields[0].isNotBlank() && fields[1].isNotBlank()) {
                    GitHubReleaseAsset(fields[0], fields[1])
                } else {
                    null
                }
            }
            .toList()
        return GitHubRelease(
            version = version,
            tagName = tagName,
            releaseUrl = releaseUrl,
            releaseNotes = preferences.getString(KEY_RELEASE_NOTES, "").orEmpty(),
            assets = assets
        )
    }

    fun saveRelease(release: GitHubRelease) {
        preferences.edit()
            .putString(KEY_RELEASE_VERSION, release.version)
            .putString(KEY_RELEASE_TAG, release.tagName)
            .putString(KEY_RELEASE_URL, release.releaseUrl)
            .putString(KEY_RELEASE_NOTES, release.releaseNotes)
            .putString(
                KEY_ASSETS,
                release.assets.joinToString("\n") {
                    "${it.name}$ASSET_SEPARATOR${it.downloadUrl}"
                }
            )
            .apply()
    }

    fun clearCachedRelease() {
        preferences.edit()
            .remove(KEY_RELEASE_VERSION)
            .remove(KEY_RELEASE_TAG)
            .remove(KEY_RELEASE_URL)
            .remove(KEY_RELEASE_NOTES)
            .remove(KEY_ASSETS)
            .apply()
    }

    companion object {
        fun todayKey(now: Date = Date()): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)

        private const val PREFERENCES_NAME = "app_updates"
        private const val KEY_LAST_AUTO_CHECK_DATE = "last_auto_check_date"
        private const val KEY_PROMPTED_VERSION = "prompted_version"
        private const val KEY_SKIPPED_VERSION = "skipped_version"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val KEY_RELEASE_VERSION = "release_version"
        private const val KEY_RELEASE_TAG = "release_tag"
        private const val KEY_RELEASE_URL = "release_url"
        private const val KEY_RELEASE_NOTES = "release_notes"
        private const val KEY_ASSETS = "assets"
        private const val ASSET_SEPARATOR = "\t"
    }
}
