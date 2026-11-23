package com.coke.testapp;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Local approximation of the checks performed by BDMS and native integrity SDKs. */
final class IntegrityProbe {
    private IntegrityProbe() {}

    static final class Result {
        final List<String> rows;
        final boolean passed;

        Result(List<String> rows, boolean passed) {
            this.rows = rows;
            this.passed = passed;
        }
    }

    static Result run(Activity activity) {
        List<String> rows = new ArrayList<>();
        int expectedUid = Process.myUid();
        String packageName = activity.getPackageName();
        PackageManager pm = activity.getPackageManager();

        try {
            PackageInfo info = pm.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            );
            boolean signatures = info.signingInfo != null &&
                (info.signingInfo.hasMultipleSigners() ||
                    info.signingInfo.getApkContentsSigners().length > 0);
            add(
                rows,
                "签名证书可用",
                signatures + " digest=" + signatureDigest(info),
                signatures
            );
            File apk = new File(info.applicationInfo.sourceDir);
            add(
                rows,
                "APK 文件",
                apk.getAbsolutePath() + " exists=" + apk.isFile()
                    + " readable=" + apk.canRead()
                    + " size=" + apk.length(),
                apk.isFile() && apk.canRead() && apk.length() > 0
            );
            add(
                rows,
                "PackageInfo UID",
                String.valueOf(info.applicationInfo.uid),
                info.applicationInfo.uid == expectedUid
            );
        } catch (Throwable error) {
            add(rows, "PackageInfo", describe(error), false);
        }

        add(rows, "Process.myUid", String.valueOf(expectedUid), true);
        add(
            rows,
            "Binder.getCallingUid",
            String.valueOf(Binder.getCallingUid()),
            Binder.getCallingUid() == expectedUid
        );
        try {
            add(
                rows,
                "PackageManager.getPackageUid",
                String.valueOf(pm.getPackageUid(packageName, 0)),
                pm.getPackageUid(packageName, 0) == expectedUid
            );
        } catch (Throwable error) {
            add(rows, "PackageManager.getPackageUid", describe(error), false);
        }
        try {
            String[] packages = pm.getPackagesForUid(expectedUid);
            add(
                rows,
                "getPackagesForUid",
                Arrays.toString(packages),
                packages != null && Arrays.asList(packages).contains(packageName)
            );
        } catch (Throwable error) {
            add(rows, "getPackagesForUid", describe(error), false);
        }
        try {
            long dataDirUid = Os.lstat(
                activity.getDataDir().getAbsolutePath()
            ).st_uid;
            add(
                rows,
                "dataDir stat.uid",
                String.valueOf(dataDirUid),
                dataDirUid == expectedUid
            );
        } catch (Throwable error) {
            add(rows, "dataDir stat.uid", describe(error), false);
        }

        String status = read("/proc/self/status");
        String statusUid = field(status, "Uid:");
        add(
            rows,
            "/proc/self/status",
            statusUid,
            statusUid.contains(String.valueOf(expectedUid))
        );
        add(
            rows,
            "TracerPid",
            field(status, "TracerPid:"),
            "0".equals(field(status, "TracerPid:"))
        );
        String cmdline = read("/proc/self/cmdline")
            .replace("\0", "|")
            .replace("\n", "");
        add(
            rows,
            "/proc/self/cmdline",
            cmdline,
            cmdline.startsWith(packageName)
        );

        String raw = RawBinderProbe.report(packageName);
        String rawUid = field(raw, "uidForPackage=");
        add(
            rows,
            "raw IPackageManager UID",
            rawUid,
            rawUid.equals(String.valueOf(expectedUid))
        );

        String nativeReport;
        try {
            NativeProbe.ensureLoaded();
            nativeReport = NativeProbe.nativeHookProbe(
                activity.getDataDir().getAbsolutePath(),
                activity.getApplicationInfo().sourceDir,
                ""
            );
        } catch (Throwable error) {
            nativeReport = "error=" + describe(error);
        }
        checkNative(rows, nativeReport, "libc.getuid", expectedUid);
        checkNative(rows, nativeReport, "syscall.getuid", expectedUid);
        checkNative(
            rows,
            nativeReport,
            "jni.activityThread.currentPackageName",
            packageName
        );
        checkNative(
            rows,
            nativeReport,
            "jni.currentApplication.getPackageName",
            packageName
        );

        String maps = read("/proc/self/maps");
        boolean hostLeak = maps.contains("com.coke.nestspace") ||
            maps.contains("libnestspace_runtime");
        add(
            rows,
            "宿主运行时映射",
            hostLeak ? "LEAK" : "not present",
            !hostLeak
        );

        boolean passed = true;
        for (String row : rows) {
            if (row.endsWith("FAIL")) {
                passed = false;
                break;
            }
        }
        return new Result(rows, passed);
    }

    private static void checkNative(
        List<String> rows,
        String report,
        String key,
        Object expected
    ) {
        String value = field(report, key + "=");
        add(
            rows,
            "native " + key,
            value,
            value.equals(String.valueOf(expected))
        );
    }

    private static String field(String report, String prefix) {
        if (report == null) return "";
        for (String line : report.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String read(String path) {
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) value.append(line).append('\n');
        } catch (Throwable error) {
            return "error=" + describe(error);
        }
        return value.toString();
    }

    private static String signatureDigest(PackageInfo info) {
        try {
            if (info.signingInfo == null) return "missing";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = info.signingInfo.getApkContentsSigners()[0].toByteArray();
            byte[] hashed = digest.digest(bytes);
            StringBuilder value = new StringBuilder();
            for (byte item : hashed) value.append(String.format("%02x", item));
            return value.toString();
        } catch (Throwable error) {
            return "error=" + describe(error);
        }
    }

    private static void add(
        List<String> rows,
        String label,
        String detail,
        boolean passed
    ) {
        rows.add(label + ": " + detail + "  " + (passed ? "PASS" : "FAIL"));
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
}
