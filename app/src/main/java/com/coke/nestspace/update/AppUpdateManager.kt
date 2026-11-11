package com.coke.nestspace.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.coke.nestspace.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AppUpdateState(
    val currentVersion: String = "",
    val release: GitHubRelease? = null,
    val skippedVersion: String? = null,
    val checking: Boolean = false,
    val checkedAtMillis: Long = 0L,
    val error: String? = null
) {
    val hasUpdate: Boolean
        get() = release != null &&
            AppVersion.isNewer(release.version, currentVersion)

    val hasUnreadUpdate: Boolean
        get() = hasUpdate && skippedVersion != release?.version
}

/**
 * Coordinates the once-per-local-day GitHub check and process-wide update UI
 * state. Manual checks are allowed at any time and count as that day's check.
 */
object AppUpdateManager {
    private val checkMutex = Mutex()
    private val mutableState = MutableStateFlow(AppUpdateState())
    private val client = GitHubReleaseClient()

    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    suspend fun checkOncePerDay(context: Context): AppUpdateState =
        checkMutex.withLock {
            val appContext = context.applicationContext
            val store = AppUpdateStore(appContext)
            val currentVersion = installedVersion(appContext)
            val cachedRelease = store.cachedRelease()?.takeIf {
                AppVersion.isNewer(it.version, currentVersion)
            }
            mutableState.value = stateFromStore(
                store,
                currentVersion,
                cachedRelease
            )
            if (store.lastAutoCheckDate() == AppUpdateStore.todayKey()) {
                return mutableState.value
            }
            store.markAutoCheckAttempted(AppUpdateStore.todayKey())
            checkLocked(appContext, store, currentVersion)
        }

    suspend fun checkNow(context: Context): AppUpdateState =
        checkMutex.withLock {
            val appContext = context.applicationContext
            val store = AppUpdateStore(appContext)
            val currentVersion = installedVersion(appContext)
            store.markAutoCheckAttempted(AppUpdateStore.todayKey())
            checkLocked(appContext, store, currentVersion)
        }

    fun shouldPrompt(context: Context, state: AppUpdateState): Boolean {
        val release = state.release ?: return false
        if (!state.hasUpdate || state.skippedVersion == release.version) return false
        val store = AppUpdateStore(context)
        return store.promptedVersion() != release.version
    }

    fun markPrompted(context: Context, state: AppUpdateState) {
        state.release?.version?.let {
            AppUpdateStore(context).markPrompted(it)
        }
    }

    fun skipCurrentVersion(context: Context) {
        val release = mutableState.value.release ?: return
        AppUpdateStore(context).skipVersion(release.version)
        mutableState.update { it.copy(skippedVersion = release.version) }
    }

    fun restoreSkippedVersion(context: Context) {
        AppUpdateStore(context).clearSkippedVersion()
        mutableState.update { it.copy(skippedVersion = null) }
    }

    fun openDownload(context: Context) {
        val release = mutableState.value.release ?: return
        val url = preferredApkDownload(release, installedAbi(context))
            ?: release.releaseUrl
        openUrl(context, url)
    }

    fun openReleasePage(context: Context) {
        val release = mutableState.value.release ?: return
        if (release.releaseUrl.isNotBlank()) {
            openUrl(context, release.releaseUrl)
        }
    }

    private suspend fun checkLocked(
        context: Context,
        store: AppUpdateStore,
        currentVersion: String
    ): AppUpdateState {
        val cachedRelease = store.cachedRelease()?.takeIf {
            AppVersion.isNewer(it.version, currentVersion)
        }
        mutableState.value = stateFromStore(
            store,
            currentVersion,
            cachedRelease
        ).copy(checking = true, error = null)
        return try {
            val release = withContext(Dispatchers.IO) {
                client.latestRelease()
            }
            val availableRelease = release.takeIf {
                AppVersion.isNewer(it.version, currentVersion)
            }
            if (availableRelease != null) {
                store.saveRelease(availableRelease)
            } else {
                store.clearCachedRelease()
            }
            val checkedAt = System.currentTimeMillis()
            store.markCheckedAt(checkedAt)
            mutableState.value = AppUpdateState(
                currentVersion = currentVersion,
                release = availableRelease,
                skippedVersion = store.skippedVersion(),
                checking = false,
                checkedAtMillis = checkedAt
            )
            mutableState.value
        } catch (error: Throwable) {
            Log.w("AppUpdateManager", "GitHub update check failed", error)
            mutableState.update {
                it.copy(
                    currentVersion = currentVersion,
                    checking = false,
                    error = AppLanguage.tr(
                        "检查更新失败，请稍后重试。",
                        "Could not check for updates. Please try again later."
                    )
                )
            }
            mutableState.value
        }
    }

    private fun stateFromStore(
        store: AppUpdateStore,
        currentVersion: String,
        release: GitHubRelease?
    ): AppUpdateState = AppUpdateState(
        currentVersion = currentVersion,
        release = release,
        skippedVersion = store.skippedVersion(),
        checking = false,
        checkedAtMillis = store.checkedAtMillis()
    )

    private fun installedVersion(context: Context): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.versionName?.takeIf { it.isNotBlank() }
            ?: packageInfo.longVersionCode.toString()
    }

    private fun installedAbi(context: Context): String? {
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
            ?.replace('\\', '/')
            .orEmpty()
        return when {
            nativeLibraryDir.endsWith("/arm64") -> "arm64-v8a"
            nativeLibraryDir.endsWith("/arm64-v8a") -> "arm64-v8a"
            nativeLibraryDir.endsWith("/x86_64") -> "x86_64"
            nativeLibraryDir.endsWith("/x86") -> "x86"
            nativeLibraryDir.endsWith("/arm") -> "armeabi-v7a"
            else -> Build.SUPPORTED_ABIS.firstOrNull()
        }
    }

    private fun openUrl(context: Context, url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                Log.w("AppUpdateManager", "Could not open update URL", it)
            }
    }
}
