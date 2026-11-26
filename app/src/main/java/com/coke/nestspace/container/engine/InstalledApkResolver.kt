package com.coke.nestspace.container.engine

import android.content.Context
import android.util.Log
import com.coke.nestspace.container.loader.ApkDescriptor
import com.coke.nestspace.container.loader.ApkInspector
import com.coke.nestspace.container.loader.ApkMetadataCache
import com.coke.nestspace.container.loader.NativeLibs
import com.coke.nestspace.core.i18n.AppLanguage
import java.io.File

internal data class ResolvedInstalledApk(
    val descriptor: ApkDescriptor,
    val index: ApkInstallIndex
)

internal class InstalledApkResolver(private val context: Context) {
    fun resolve(app: StoredApp): ResolvedInstalledApk {
        val cached = app.apkIndex?.let { index ->
            ApkMetadataCache.read(
                index = index,
                baseApkPath = app.baseApkPath,
                expectedPackageName = app.packageName,
                expectedVersionCode = app.versionCode,
                expectedSignatureSha256 = app.signatureSha256
            )
        }
        if (cached != null) {
            Log.d(TAG, "APK metadata cache hit: ${app.packageName}")
            return ResolvedInstalledApk(cached, app.apkIndex!!)
        }

        Log.d(TAG, "APK metadata cache rebuild: ${app.packageName}")
        val descriptor = ApkInspector.inspect(context, app.baseApkPath, true)
        if (descriptor.packageName != app.packageName ||
            descriptor.versionCode != app.versionCode ||
            (app.signatureSha256.isNotBlank() &&
                descriptor.signatureSha256 != app.signatureSha256)
        ) {
            error(
                AppLanguage.tr(
                    "APK 与安装记录不一致",
                    "APK does not match the install record"
                )
            )
        }
        val paths = listOf(app.baseApkPath) + app.splitApkPaths
        val digests = paths.associateWith { ApkPayloadIdentity.sha256(File(it)) }
        val nativeAbis = descriptor.nativeAbis +
            if (app.splitApkPaths.isEmpty()) {
                emptySet()
            } else {
                NativeLibs.abisOf(app.splitApkPaths)
            }
        val index = ApkInstallIndex(
            schemaVersion = APK_INDEX_SCHEMA_VERSION,
            files = paths.map { path ->
                ApkFileFingerprint(
                    path = path,
                    size = File(path).length(),
                    lastModified = File(path).lastModified(),
                    sha256 = digests.getValue(path)
                )
            },
            nativeAbis = nativeAbis,
            descriptorCachePath = ApkMetadataCache.descriptorPath(app.baseApkPath)
        )
        ApkMetadataCache.write(descriptor, index)
        return ResolvedInstalledApk(descriptor, index)
    }

    private companion object {
        const val TAG = "MiniContainerEngine"
    }
}
