package com.coke.nestspace.container.engine.install

import com.coke.nestspace.container.engine.ApkPayloadIdentity
import com.coke.nestspace.core.i18n.AppLanguage
import java.io.File

internal fun moveApkFile(
    source: File,
    target: File,
    expectedSha256: String
) {
    target.parentFile?.mkdirs()
    if (source.renameTo(target)) return
    source.copyTo(target, overwrite = true)
    if (ApkPayloadIdentity.sha256(target) != expectedSha256) {
        target.delete()
        throw ApkInstallException(
            "INSTALL_FAILED",
            AppLanguage.tr(
                "APK 移动后内容校验失败",
                "APK content verification failed after moving the file"
            )
        )
    }
    source.delete()
}
