package com.coke.testapp;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Feishu's login-page rebuild check compares package metadata across
 * PackageManager, the install source, the APK path and process identity.
 * Keep this vendor-facing rule set in the test app rather than the container
 * runtime.
 */
final class FeishuPackageProbe {
    private static final String TARGET = "com.ss.android.lark";
    private static final String[] HOST_MARKERS = {
        "com.coke.nestspace",
        "/.nestspace/",
        "/vspace/",
        "libnestspace"
    };

    private FeishuPackageProbe() {}

    static void appendRows(List<String> rows, Activity activity) {
        PackageManager pm = activity.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(
                TARGET,
                PackageManager.GET_SIGNING_CERTIFICATES
            );
            ApplicationInfo app = info.applicationInfo;
            add(
                rows,
                "Feishu PackageInfo",
                "version=" + info.versionName
                    + " code=" + info.getLongVersionCode()
                    + " uid=" + app.uid
                    + " targetSdk=" + app.targetSdkVersion,
                TARGET.equals(info.packageName) &&
                    info.getLongVersionCode() > 0 &&
                    app.targetSdkVersion > 0
            );

            String flags = "0x" + Integer.toHexString(app.flags);
            boolean unsafeFlags =
                (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0 ||
                    (app.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0;
            add(
                rows,
                "Feishu ApplicationInfo flags",
                flags + " enabled=" + app.enabled +
                    " category=" + app.category,
                !unsafeFlags && app.enabled
            );

            String source = app.sourceDir;
            String publicSource = app.publicSourceDir;
            File sourceFile = new File(source);
            add(
                rows,
                "Feishu APK identity",
                "source=" + source
                    + " public=" + publicSource
                    + " native=" + app.nativeLibraryDir,
                source.equals(publicSource) &&
                    sourceFile.isFile() &&
                    sourceFile.canRead() &&
                    !containsHostMarker(source) &&
                    !containsHostMarker(app.nativeLibraryDir)
            );

            String data = app.dataDir;
            String deviceProtected = app.deviceProtectedDataDir;
            add(
                rows,
                "Feishu data identity",
                "data=" + data
                    + " deviceProtected=" + deviceProtected,
                data != null &&
                    data.endsWith("/" + TARGET) &&
                    deviceProtected != null &&
                    deviceProtected.endsWith("/" + TARGET) &&
                    !containsHostMarker(data) &&
                    !containsHostMarker(deviceProtected)
            );

            String[] splitNames = app.splitNames;
            String[] splitDirs = app.splitSourceDirs;
            add(
                rows,
                "Feishu split APK identity",
                "names=" + java.util.Arrays.toString(splitNames)
                    + " dirs=" + java.util.Arrays.toString(splitDirs),
                sameLength(splitNames, splitDirs) &&
                    !containsHostMarker(java.util.Arrays.toString(splitDirs))
            );

            String digest = signerDigest(info);
            add(
                rows,
                "Feishu signing certificate",
                digest,
                digest.length() == 64 &&
                    !"missing".equals(digest) &&
                    !digest.startsWith("error=")
            );
            add(
                rows,
                "Feishu signature relation",
                "checkSignatures=" +
                    pm.checkSignatures(TARGET, TARGET),
                pm.checkSignatures(TARGET, TARGET) ==
                    PackageManager.SIGNATURE_MATCH
            );

            appendInstallSource(rows, pm);
        } catch (Throwable error) {
            add(
                rows,
                "Feishu PackageInfo",
                describe(error),
                false
            );
        }
    }

    private static void appendInstallSource(
        List<String> rows,
        PackageManager pm
    ) {
        try {
            String installer = pm.getInstallerPackageName(TARGET);
            String detail = "installer=" + installer;
            boolean passed = !containsHostMarker(installer);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo source = pm.getInstallSourceInfo(TARGET);
                detail += " initiating=" + source.getInitiatingPackageName()
                    + " originating=" + source.getOriginatingPackageName()
                    + " updateOwner=" + source.getUpdateOwnerPackageName();
                passed = passed &&
                    !containsHostMarker(source.getInitiatingPackageName()) &&
                    !containsHostMarker(source.getOriginatingPackageName()) &&
                    !containsHostMarker(source.getUpdateOwnerPackageName());
            }
            add(rows, "Feishu install source", detail, passed);
        } catch (Throwable error) {
            add(rows, "Feishu install source", describe(error), false);
        }
    }

    private static String signerDigest(PackageInfo info) {
        try {
            if (info.signingInfo == null) return "missing";
            Signature[] signers = info.signingInfo.hasMultipleSigners()
                ? info.signingInfo.getApkContentsSigners()
                : info.signingInfo.getSigningCertificateHistory();
            if (signers == null || signers.length == 0) return "missing";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(signers[0].toByteArray());
            StringBuilder result = new StringBuilder(value.length * 2);
            for (byte item : value) {
                result.append(String.format(Locale.US, "%02x", item));
            }
            return result.toString();
        } catch (Throwable error) {
            return describe(error);
        }
    }

    private static boolean sameLength(String[] first, String[] second) {
        int firstLength = first == null ? 0 : first.length;
        int secondLength = second == null ? 0 : second.length;
        return firstLength == secondLength;
    }

    private static boolean containsHostMarker(String value) {
        if (value == null) return false;
        for (String marker : HOST_MARKERS) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private static String describe(Throwable error) {
        return error.getClass().getName() +
            (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    private static void add(
        List<String> rows,
        String label,
        String detail,
        boolean passed
    ) {
        rows.add(
            label + ": " + detail.replace('\n', ' ') +
                (passed ? " PASS" : " FAIL")
        );
    }
}
