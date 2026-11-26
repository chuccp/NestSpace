package com.coke.nestspace.container.engine

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.coke.nestspace.core.files.safePathSegment
import com.coke.nestspace.data.VirtualApp
import java.io.File
import com.coke.nestspace.data.VirtualInstance

internal class InstalledAppProjector(
    private val context: Context,
    private val dataRootDir: File
) {
    private val iconCache = mutableMapOf<String, ImageBitmap?>()

    fun publicApps(
        spaceId: String,
        apps: Collection<StoredApp>
    ): List<VirtualApp> =
        apps.filter { it.spaceId == spaceId }
            .sortedBy { it.label.lowercase() }
            .map(::toPublic)

    fun allPublicApps(apps: Collection<StoredApp>): List<VirtualApp> =
        apps.map(::toPublic)
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))

    fun toPublic(app: StoredApp): VirtualApp = VirtualApp(
        spaceId = app.spaceId,
        packageName = app.packageName,
        label = app.label,
        versionName = app.versionName,
        versionCode = app.versionCode,
        launcherActivity = app.launcherActivity,
        signatureSha256 = app.signatureSha256,
        hasNativeLibs = app.hasNativeLibs,
        installedAt = app.installedAt,
        updatedAt = app.updatedAt,
        instances = app.instances.map {
            VirtualInstance(
                id = it.id,
                createdAt = it.createdAt,
                lastLaunchedAt = it.lastLaunchedAt,
                dataDir = dataDirFor(app.packageName, it.id).absolutePath,
                environmentVersion = it.environmentVersion,
                lastProbeResult = it.lastProbeResult
            )
        },
        icon = loadIcon(app)
    )

    private fun loadIcon(app: StoredApp): ImageBitmap? {
        val cacheKey = "${app.baseApkPath}@${app.updatedAt}"
        synchronized(iconCache) {
            if (iconCache.containsKey(cacheKey)) return iconCache[cacheKey]
        }
        val icon = runCatching {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(app.baseApkPath, 0)?.applicationInfo
                ?: return@runCatching null
            info.sourceDir = app.baseApkPath
            info.publicSourceDir = app.baseApkPath
            info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
        synchronized(iconCache) { iconCache[cacheKey] = icon }
        return icon
    }

    private fun dataDirFor(packageName: String, instanceId: String): File =
        File(
            File(dataRootDir, safePathSegment(packageName)),
            safePathSegment(instanceId)
        )
}
