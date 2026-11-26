package com.coke.nestspace.container.engine.install

import android.os.Build
import com.coke.nestspace.container.loader.ApkDescriptor
import com.coke.nestspace.container.engine.ApkPayloadIdentity
import com.coke.nestspace.container.engine.StoredApp
import com.coke.nestspace.core.i18n.AppLanguage
import java.io.File

internal data class ValidatedApkInstall(
    val packageName: String,
    val base: ApkDescriptor,
    val splits: List<ApkDescriptor>,
    val signatureSha256: String,
    val pendingDigestsByPath: Map<String, String>
) {
    val pendingDigests: List<String> = pendingDigestsByPath.values.toList()
}

internal class ApkInstallException(
    val code: String,
    message: String
) : Exception(message)

internal object ApkInstallInspector {
    fun validate(
        staged: StagedApkFiles,
        existing: StoredApp?
    ): ValidatedApkInstall {
        val descriptors = staged.descriptors
        val packages = descriptors.map { it.packageName }.distinct()
        if (packages.size != 1) {
            throw ApkInstallException(
                "MULTI_PACKAGE",
                AppLanguage.tr(
                    "一次安装只能包含同一个包：${packages.joinToString()}",
                    "One install can only contain APKs of the same package: " +
                        packages.joinToString()
                )
            )
        }
        val packageName = packages.single()
        val bases = descriptors.filter { it.hasLauncher && it.splitName == null }
        if (bases.isEmpty()) {
            throw ApkInstallException(
                "BASE_APK_REQUIRED",
                AppLanguage.tr(
                    "需要选择包含入口 Activity 的 base APK",
                    "Select a base APK that contains the launcher activity"
                )
            )
        }
        if (bases.size > 1) {
            throw ApkInstallException(
                "MULTIPLE_BASE_APK",
                AppLanguage.tr(
                    "一次只能安装一个 base APK（选择 base + split 才合法）",
                    "Only one base APK can be installed at a time (base + splits is allowed)"
                )
            )
        }
        val base = bases.single()
        val splits = descriptors.filter { it !== base }
        if (base.minSdk > Build.VERSION.SDK_INT) {
            throw ApkInstallException(
                "MIN_SDK_TOO_HIGH",
                AppLanguage.tr(
                    "${base.packageName} 需要 API ${base.minSdk}，当前设备 API ${Build.VERSION.SDK_INT}",
                    "${base.packageName} requires API ${base.minSdk}; this device is API ${Build.VERSION.SDK_INT}"
                )
            )
        }
        if (descriptors.any { it.requiresSecureEnv }) {
            throw ApkInstallException(
                "SECURE_ENV_REQUIRED",
                AppLanguage.tr(
                    "${base.packageName} 声明了 REQUIRE_SECURE_ENV=1，拒绝在应用级容器中安装",
                    "${base.packageName} declares REQUIRE_SECURE_ENV=1 and cannot be " +
                        "installed in an app-level container"
                )
            )
        }
        val signature = base.signatureSha256?.takeIf { it.isNotBlank() }
            ?: throw ApkInstallException(
                "SIGNATURE_INVALID",
                AppLanguage.tr(
                    "无法读取 APK 签名",
                    "Could not read the APK signature"
                )
            )
        val certificatesMatch = base.signingCertDigests.isNotEmpty() &&
            descriptors.all { descriptor ->
                descriptor.signingCertDigests.isNotEmpty() &&
                    base.signingCertDigests.containsAll(descriptor.signingCertDigests)
            }
        val legacySignersMatch = base.v1SignatureSha256 != null &&
            descriptors.all { it.v1SignatureSha256 == base.v1SignatureSha256 }
        if (!certificatesMatch && !legacySignersMatch) {
            throw ApkInstallException(
                "SIGNATURE_MISMATCH",
                AppLanguage.tr(
                    "base 与 split APK 的签名不一致",
                    "The base and split APKs are signed differently"
                )
            )
        }
        if (existing != null && existing.signatureSha256 != signature) {
            throw ApkInstallException(
                "SIGNATURE_MISMATCH",
                AppLanguage.tr(
                    "新 APK 与已安装版本签名不一致（$packageName）",
                    "The new APK is signed differently from the installed version ($packageName)"
                )
            )
        }
        val pendingDigestsByPath = staged.files.associate {
            it.absolutePath to ApkPayloadIdentity.sha256(it)
        }
        return ValidatedApkInstall(
            packageName = packageName,
            base = base,
            splits = splits,
            signatureSha256 = signature,
            pendingDigestsByPath = pendingDigestsByPath
        )
    }
}
