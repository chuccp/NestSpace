package com.coke.testapp;

import android.app.Activity;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cross-checks procfs, mount, root-path and environment observations. */
final class ProcSurfaceProbe {
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

    private static final String[] ROOT_PATHS = {
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/data/adb/magisk",
        "/data/adb/modules"
    };

    private ProcSurfaceProbe() {}

    static void appendRows(Activity activity, List<String> rows) {
        appendKernelRelease(rows);
        appendMemory(rows);
        appendProcIo(rows);
        appendMounts(activity, rows);
        appendRootAndEnvironment(rows);
    }

    private static void appendKernelRelease(List<String> rows) {
        try {
            String uname = Os.uname().release;
            String version = read("/proc/version").trim();
            String osRelease = read(
                "/proc/sys/kernel/osrelease"
            ).trim();
            String osType = read("/proc/sys/kernel/ostype").trim();
            String bootId = read(
                "/proc/sys/kernel/random/boot_id"
            ).trim();
            String detail = "uname=" + uname
                + " osrelease=" + osRelease
                + " ostype=" + osType
                + " bootId=" + bootId
                + " version=" + version;
            boolean osReleaseMatches =
                osRelease.startsWith("error=") ||
                    uname.equals(osRelease);
            boolean osTypeMatches =
                osType.startsWith("error=") ||
                    "Linux".equals(osType);
            boolean versionMatches =
                version.startsWith("error=") ||
                    version.contains(uname);
            add(
                rows,
                "通用内核版本/proc 属性",
                detail,
                !uname.isEmpty() &&
                    osReleaseMatches &&
                    osTypeMatches &&
                    bootId.matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-" +
                            "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                            "[0-9a-fA-F]{12}"
                    ) &&
                    versionMatches &&
                    !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用内核版本/proc 属性", describe(error), false);
        }
    }

    private static void appendMemory(List<String> rows) {
        try {
            String statm = read("/proc/self/statm").trim();
            String[] pages = statm.split("\\s+");
            long residentPages = pages.length > 1
                ? Long.parseLong(pages[1])
                : -1;
            long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
            long statmBytes = residentPages * pageSize;
            String smaps = read("/proc/self/smaps_rollup");
            long smapsBytes = parseKbField(smaps, "Rss:") * 1024L;
            long difference = Math.abs(statmBytes - smapsBytes);
            long largest = Math.max(statmBytes, smapsBytes);
            add(
                rows,
                "通用 statm/smaps 内存视图",
                "statm=" + statmBytes
                    + " smaps=" + smapsBytes
                    + " pageSize=" + pageSize,
                statmBytes > 0 &&
                    smapsBytes > 0 &&
                    difference <= Math.max(2L * 1024L * 1024L,
                        largest / 4L)
            );
        } catch (Throwable error) {
            add(rows, "通用 statm/smaps 内存视图", describe(error), false);
        }
    }

    private static void appendProcIo(List<String> rows) {
        try {
            String io = read("/proc/self/io");
            long readBytes = parseLongField(io, "rchar:");
            long writtenBytes = parseLongField(io, "wchar:");
            boolean counters = readBytes >= 0 && writtenBytes >= 0;
            String limits = read("/proc/self/limits");
            boolean limitShape = limits.contains("Max open files")
                && limits.contains("Max processes");
            String personality = read(
                "/proc/self/personality"
            ).trim();
            String wchan = read("/proc/self/wchan").trim();
            add(
                rows,
                "通用 proc io/limits/personality",
                "rchar=" + readBytes
                    + " wchar=" + writtenBytes
                    + " limits=" + limitShape
                    + " personality=" + personality
                    + " wchan=" + wchan,
                counters &&
                    limitShape &&
                    !personality.startsWith("error=") &&
                    !containsHostMarker(io) &&
                    !containsHostMarker(limits)
            );
        } catch (Throwable error) {
            add(rows, "通用 proc io/limits/personality", describe(error), false);
        }
    }

    private static void appendMounts(
        Activity activity,
        List<String> rows
    ) {
        try {
            String mounts = read("/proc/self/mounts");
            String mountinfo = read("/proc/self/mountinfo");
            boolean dataRootVisible;
            try {
                Os.lstat(activity.getDataDir().getAbsolutePath());
                dataRootVisible = true;
            } catch (Throwable ignored) {
                dataRootVisible = false;
            }
            Set<String> unsafe = new LinkedHashSet<>();
            for (String line : mounts.split("\n")) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 4) continue;
                String target = fields[1];
                String options = fields[3];
                if (target.equals("/system") ||
                    target.equals("/system/bin") ||
                    target.equals("/vendor") ||
                    target.equals("/data/adb")) {
                    if (options.contains("rw")) unsafe.add(line);
                }
                if (containsHostMarker(line)) unsafe.add(line);
                if (unsafe.size() >= 4) break;
            }
            add(
                rows,
                "通用 mounts/mountinfo 根视图",
                "mounts=" + countLines(mounts)
                    + " mountinfo=" + countLines(mountinfo)
                    + " dataRootVisible=" + dataRootVisible
                    + (unsafe.isEmpty()
                        ? ""
                        : " unsafe=" + unsafe),
                !mounts.startsWith("error=") &&
                    !mountinfo.startsWith("error=") &&
                    dataRootVisible &&
                    unsafe.isEmpty()
            );
        } catch (Throwable error) {
            add(rows, "通用 mounts/mountinfo 根视图", describe(error), false);
        }
    }

    private static void appendRootAndEnvironment(List<String> rows) {
        List<String> found = new ArrayList<>();
        for (String path : ROOT_PATHS) {
            File file = new File(path);
            if (file.exists()) found.add(path);
        }
        add(
            rows,
            "通用 root/越权路径",
            found.isEmpty() ? "not present" : String.join(", ", found),
            found.isEmpty()
        );

        Set<String> loaded = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            String lowerKey = key.toLowerCase(java.util.Locale.ROOT);
            String lowerValue = value.toLowerCase(java.util.Locale.ROOT);
            if (lowerKey.contains("preload") ||
                lowerKey.contains("frida") ||
                lowerKey.contains("substrate") ||
                lowerValue.contains("frida") ||
                lowerValue.contains("xposed") ||
                lowerValue.contains("magisk") ||
                containsHostMarker(value)) {
                loaded.add(key + "=" + value);
            }
        }
        add(
            rows,
            "通用注入环境变量",
            loaded.isEmpty() ? "0" : String.join("; ", loaded),
            loaded.isEmpty()
        );
    }

    private static long parseKbField(String report, String key) {
        for (String line : report.split("\n")) {
            if (!line.startsWith(key)) continue;
            String[] values = line.substring(key.length())
                .trim()
                .split("\\s+");
            try {
                return Long.parseLong(values[0]);
            } catch (Throwable ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static long parseLongField(String report, String key) {
        for (String line : report.split("\n")) {
            if (!line.startsWith(key)) continue;
            try {
                return Long.parseLong(
                    line.substring(key.length()).trim()
                );
            } catch (Throwable ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static int countLines(String report) {
        if (report == null || report.startsWith("error=")) return -1;
        int count = 0;
        for (String line : report.split("\n")) {
            if (!line.isEmpty()) ++count;
        }
        return count;
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

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() +
            (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    private static void add(
        List<String> rows,
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
