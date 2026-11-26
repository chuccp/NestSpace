package com.coke.nestspace.container.engine.install

import android.content.Context
import android.net.Uri
import com.coke.nestspace.container.loader.ApkDescriptor
import com.coke.nestspace.container.loader.ApkInspector
import com.coke.nestspace.core.i18n.AppLanguage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal data class StagedApkFiles(
    val files: List<File>,
    val descriptors: List<ApkDescriptor>
)

internal class ApkInputStager(
    private val context: Context,
    private val tempDir: File
) {
    fun stage(
        workDir: File,
        apkUris: List<String>,
        namePrefix: String
    ): StagedApkFiles {
        val files = apkUris.mapIndexed { index, uri ->
            copyUriToTemp(uri, File(workDir, "$namePrefix-$index.apk"))
        }
        val descriptors = files.map {
            ApkInspector.inspect(context, it.absolutePath, requireLauncher = false)
        }
        return StagedApkFiles(files, descriptors)
    }

    inline fun <T> withStaged(
        apkUris: List<String>,
        namePrefix: String,
        block: (StagedApkFiles) -> T
    ): T? {
        val workDir = File(tempDir, UUID.randomUUID().toString().replace("-", ""))
            .apply { mkdirs() }
        return try {
            block(stage(workDir, apkUris, namePrefix))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun copyUriToTemp(uriString: String, target: File): File {
        val source = when {
            uriString.startsWith("content://") -> null
            uriString.startsWith("file://") -> File(
                Uri.parse(uriString).path
                    ?: error(AppLanguage.tr("无效 URI: $uriString", "Invalid URI: $uriString"))
            )
            else -> File(uriString)
        }
        if (source != null) {
            if (!source.isFile) {
                error(
                    AppLanguage.tr(
                        "APK 不存在: ${source.absolutePath}",
                        "APK does not exist: ${source.absolutePath}"
                    )
                )
            }
            source.copyTo(target, overwrite = true)
        } else {
            val input = context.contentResolver.openInputStream(Uri.parse(uriString))
                ?: error(
                    AppLanguage.tr(
                        "无法读取 APK: $uriString",
                        "Could not read the APK: $uriString"
                    )
                )
            input.use { from ->
                target.outputStream().use { to -> from.copyTo(to) }
            }
        }
        if (!target.isFile || target.length() == 0L) {
            error(
                AppLanguage.tr(
                    "APK 拷贝失败: $uriString",
                    "Failed to copy the APK: $uriString"
                )
            )
        }
        return target
    }
}
