package com.coke.testapp;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.system.Os;
import android.system.StructStat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashSet;
import java.util.Set;

/** Additional integrity inputs observed in hardened Android applications. */
final class AdvancedTamperProbe {
    private static final String[] HOST_MARKERS = {
        "com.coke.nestspace",
        "/.nestspace/",
        "/vspace/",
        "native-cache",
        "libnestspace",
        "libsandbox",
        "libsandhook",
        "shadowhook"
    };

    private AdvancedTamperProbe() {}

    static void appendRows(
        Activity activity,
        String nativeReport,
        java.util.List<String> rows
    ) {
        appendDataAliasRow(activity, rows);
        appendSignatureRows(activity, rows);
        appendSharedLibraryRow(activity, rows);
        appendMountRootRow(rows);
        appendMappedLibraryRow(nativeReport, rows);
        appendMemoryMapRows(rows);
        appendWebViewRendererRow(activity, rows);
    }

    private static void appendDataAliasRow(
        Activity activity,
        java.util.List<String> rows
    ) {
        try {
            String packageName = activity.getPackageName();
            StructStat user = Os.stat("/data/user/0/" + packageName);
            StructStat alias = Os.stat("/data/data/" + packageName);
            add(
                rows,
                "Metasec data/data 与 user/0 inode",
                "user.dev=" + user.st_dev + " user.ino=" + user.st_ino
                    + " alias.dev=" + alias.st_dev
                    + " alias.ino=" + alias.st_ino,
                user.st_dev == alias.st_dev &&
                    user.st_ino == alias.st_ino &&
                    user.st_uid == alias.st_uid
            );
        } catch (Throwable error) {
            add(rows, "Metasec data alias stat", describe(error), false);
        }
    }

    private static void appendSignatureRows(
        Activity activity,
        java.util.List<String> rows
    ) {
        try {
            PackageManager pm = activity.getPackageManager();
            String packageName = activity.getPackageName();
            PackageInfo info = pm.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            );
            Signature[] signers = info.signingInfo == null
                ? null
                : info.signingInfo.getApkContentsSigners();
            Signature[] history = info.signingInfo == null
                ? null
                : info.signingInfo.getSigningCertificateHistory();
            android.content.pm.ApplicationInfo app = info.applicationInfo;
            add(
                rows,
                "Metasec 签名历史",
                "signers=" + (signers == null ? 0 : signers.length)
                    + " history=" + (history == null ? 0 : history.length)
                    + " multiple=" + (
                        info.signingInfo != null &&
                            info.signingInfo.hasMultipleSigners()
                    ),
                info.signingInfo != null &&
                    signers != null && signers.length > 0 &&
                    history != null && history.length > 0
            );
            add(
                rows,
                "Metasec ApplicationInfo 标志",
                app == null
                    ? "applicationInfo=null"
                    : "uid=" + app.uid
                        + " enabled=" + app.enabled
                        + " flags=0x" + Integer.toHexString(app.flags)
                        + " native=" + app.nativeLibraryDir,
                app != null &&
                    app.uid == android.os.Process.myUid() &&
                    app.enabled &&
                    !containsHostMarker(app.nativeLibraryDir)
            );
            ApkSigningBlockProbe.Result signingBlock =
                ApkSigningBlockProbe.parse(new File(app.sourceDir));
            add(
                rows,
                "Metasec APK Signing Block",
                ApkSigningBlockProbe.describe(signingBlock),
                signingBlock.passed
            );
        } catch (Throwable error) {
            add(rows, "Metasec 签名历史", describe(error), false);
        }
    }

    private static void appendSharedLibraryRow(
        Activity activity,
        java.util.List<String> rows
    ) {
        try {
            String[] libraries = activity.getPackageManager()
                .getSystemSharedLibraryNames();
            int count = libraries == null ? 0 : libraries.length;
            Set<String> leaks = new LinkedHashSet<>();
            if (libraries != null) {
                for (String library : libraries) {
                    if (containsHostMarker(library)) leaks.add(library);
                }
            }
            add(
                rows,
                "Metasec system shared libraries",
                "count=" + count
                    + (leaks.isEmpty() ? "" : " leaks=" + leaks),
                count > 0 && leaks.isEmpty()
            );
        } catch (Throwable error) {
            add(rows, "Metasec system shared libraries", describe(error), false);
        }
    }

    private static void appendMountRootRow(java.util.List<String> rows) {
        String mountinfo = read("/proc/self/mountinfo");
        if (mountinfo.startsWith("error=")) {
            add(rows, "Metasec mountinfo 根视图", mountinfo, false);
            return;
        }
        Set<String> leaks = new LinkedHashSet<>();
        int count = 0;
        for (String line : mountinfo.split("\n")) {
            if (line.isEmpty()) continue;
            ++count;
            String[] fields = line.split(" ");
            if (fields.length < 5 ||
                !fields[3].startsWith("/") ||
                !fields[4].startsWith("/") ||
                containsHostMarker(line)) {
                leaks.add(line);
                if (leaks.size() >= 4) break;
            }
        }
        add(
            rows,
            "Metasec mountinfo 根视图",
            "mounts=" + count
                + (leaks.isEmpty() ? "" : " leaks=" + leaks),
            count > 0 && leaks.isEmpty()
        );
    }

    private static void appendMappedLibraryRow(
        String nativeReport,
        java.util.List<String> rows
    ) {
        String path = field(nativeReport, "native.libraryPath=");
        add(
            rows,
            "Metasec JNI 模块路径",
            path,
            path.contains("libtestapp_probe.so") &&
                path.startsWith("/data/app/") &&
                !containsHostMarker(path)
        );
    }

    private static void appendMemoryMapRows(java.util.List<String> rows) {
        String maps = read("/proc/self/maps");
        if (maps.startsWith("error=")) {
            add(rows, "Metasec 文件映射权限", maps, false);
            return;
        }
        Set<String> writableExecutable = new LinkedHashSet<>();
        Set<String> deletedExecutable = new LinkedHashSet<>();
        for (String line : maps.split("\n")) {
            String[] fields = line.trim().split("\\s+", 6);
            if (fields.length < 6 || !fields[5].startsWith("/")) continue;
            String permissions = fields[1];
            boolean writable = permissions.length() > 1 &&
                permissions.charAt(1) == 'w';
            boolean executable = permissions.length() > 2 &&
                permissions.charAt(2) == 'x';
            if (writable && executable) writableExecutable.add(line);
            boolean systemJitCache =
                fields[5].contains("/memfd:jit-cache (deleted)") ||
                fields[5].contains("/memfd:jit-zygote-cache (deleted)");
            if (executable && fields[5].contains("(deleted)") &&
                !systemJitCache) {
                deletedExecutable.add(line);
            }
        }
        add(
            rows,
            "Metasec 可写可执行文件映射",
            writableExecutable.isEmpty()
                ? "0"
                : String.join("; ", writableExecutable),
            writableExecutable.isEmpty()
        );
        add(
            rows,
            "Metasec deleted 可执行映射",
            deletedExecutable.isEmpty()
                ? "0"
                : String.join("; ", deletedExecutable),
            deletedExecutable.isEmpty()
        );
    }

    private static void appendWebViewRendererRow(
        Activity activity,
        java.util.List<String> rows
    ) {
        Set<String> matches = new LinkedHashSet<>();
        String prefix = activity.getPackageName() + ":sandboxed_process";
        String[] processes = new File("/proc").list();
        if (processes != null) {
            for (String process : processes) {
                if (!process.matches("[0-9]+")) continue;
                String cmdline = read("/proc/" + process + "/cmdline")
                    .replace('\0', '|')
                    .replace('\n', ' ')
                    .trim();
                if (!cmdline.startsWith(prefix)) continue;
                String uid = field(
                    read("/proc/" + process + "/status"),
                    "Uid:"
                );
                matches.add(process + ":" + cmdline + " uid=" + uid);
            }
        }
        boolean valid = true;
        for (String match : matches) {
            if (!match.contains("uid=" + android.os.Process.myUid())) {
                valid = false;
                break;
            }
        }
        add(
            rows,
            "Metasec WebView renderer 身份",
            matches.isEmpty()
                ? "not running"
                : String.join("; ", matches),
            valid
        );
    }

    private static boolean containsHostMarker(String value) {
        if (value == null) return false;
        for (String marker : HOST_MARKERS) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private static String read(String path) {
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                value.append(line).append('\n');
            }
        } catch (Throwable error) {
            return "error=" + describe(error);
        }
        return value.toString();
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

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() +
            (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    private static void add(
        java.util.List<String> rows,
        String label,
        String detail,
        boolean passed
    ) {
        rows.add(
            label + ": " + detail.replace('\n', ' ')
                + (passed ? " PASS" : " FAIL")
        );
    }
}
