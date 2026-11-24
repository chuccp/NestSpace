package com.coke.testapp;

import android.app.Activity;
import android.app.ActivityManager;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructUtsname;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Cross-checks kernel, procfs, Android framework and Java runtime views. */
final class KernelSurfaceProbe {
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

    private KernelSurfaceProbe() {}

    static void appendRows(Activity activity, List<String> rows) {
        appendUname(rows);
        appendCpu(rows);
        appendMemory(activity, rows);
        appendFdInfo(rows);
        appendSmapsRollup(rows);
        appendRuntime(rows);
        appendRandomDevice(rows);
    }

    private static void appendUname(List<String> rows) {
        try {
            StructUtsname uname = Os.uname();
            String osVersion = System.getProperty("os.version", "");
            String detail = "uname=" + uname.sysname + "/"
                + uname.release + "/" + uname.machine
                + " os.version=" + osVersion;
            add(
                rows,
                "通用 uname/os.version",
                detail,
                uname.release != null &&
                    uname.release.length() > 0 &&
                    (osVersion.isEmpty() || osVersion.equals(uname.release)) &&
                    !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用 uname/os.version", describe(error), false);
        }
    }

    private static void appendCpu(List<String> rows) {
        try {
            long configured = Os.sysconf(OsConstants._SC_NPROCESSORS_CONF);
            long online = Os.sysconf(OsConstants._SC_NPROCESSORS_ONLN);
            String possible = read("/sys/devices/system/cpu/possible").trim();
            String cpuinfo = read("/proc/cpuinfo");
            int cpuinfoCount = countLinesStartingWith(cpuinfo, "processor");
            String detail = "configured=" + configured + " online=" + online
                + " possible=" + possible + " cpuinfo=" + cpuinfoCount;
            add(
                rows,
                "通用 CPU 配置/在线视图",
                detail,
                configured > 0 && online > 0 && online <= configured &&
                    !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用 CPU 配置/在线视图", describe(error), false);
        }
    }

    private static void appendMemory(
        Activity activity,
        List<String> rows
    ) {
        try {
            String meminfo = read("/proc/meminfo");
            long memTotal = parseMeminfoKb(meminfo, "MemTotal:");
            ActivityManager manager = activity.getSystemService(
                ActivityManager.class
            );
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            if (manager != null) {
                manager.getMemoryInfo(memory);
            }
            long procTotalBytes = memTotal * 1024L;
            long frameworkBytes = memory.totalMem;
            boolean close = procTotalBytes > 0 && frameworkBytes > 0 &&
                Math.abs(procTotalBytes - frameworkBytes) <=
                    Math.max(procTotalBytes, frameworkBytes) / 20L;
            add(
                rows,
                "通用 meminfo/ActivityManager 内存",
                "procTotal=" + procTotalBytes + " frameworkTotal="
                    + frameworkBytes + " avail=" + memory.availMem,
                close && !containsHostMarker(meminfo)
            );
        } catch (Throwable error) {
            add(
                rows,
                "通用 meminfo/ActivityManager 内存",
                describe(error),
                false
            );
        }
    }

    private static void appendFdInfo(List<String> rows) {
        try {
            File root = new File("/proc/self/fdinfo");
            String[] names = root.list();
            int count = 0;
            Set<String> leaks = new LinkedHashSet<>();
            if (names != null) {
                for (String name : names) {
                    if (!name.matches("[0-9]+")) continue;
                    ++count;
                    String content = read(new File(root, name).getAbsolutePath());
                    boolean closedDuringScan =
                        content.contains("ENOENT") ||
                            content.contains("No such file or directory");
                    if ((!closedDuringScan &&
                        content.startsWith("error=")) ||
                        (!closedDuringScan && !content.contains("pos:")) ||
                        containsHostMarker(content)) {
                        leaks.add(name + ":" + compact(content));
                    }
                }
            }
            add(
                rows,
                "通用 proc/self/fdinfo",
                "fds=" + count + (leaks.isEmpty()
                    ? ""
                    : " leaks=" + leaks),
                count > 0 && leaks.isEmpty()
            );
        } catch (Throwable error) {
            add(rows, "通用 proc/self/fdinfo", describe(error), false);
        }
    }

    private static void appendSmapsRollup(List<String> rows) {
        try {
            String smaps = read("/proc/self/smaps_rollup");
            boolean hasPss = smaps.contains("Pss:");
            boolean hasRss = smaps.contains("Rss:");
            add(
                rows,
                "通用 smaps_rollup",
                "Pss=" + hasPss + " Rss=" + hasRss,
                hasPss && hasRss && !containsHostMarker(smaps)
            );
        } catch (Throwable error) {
            add(rows, "通用 smaps_rollup", describe(error), false);
        }
    }

    private static void appendRuntime(List<String> rows) {
        try {
            Runtime runtime = Runtime.getRuntime();
            String detail = "processors=" + runtime.availableProcessors()
                + " maxMemory=" + runtime.maxMemory()
                + " tmpdir=" + System.getProperty("java.io.tmpdir")
                + " libraryPath=" + System.getProperty("java.library.path")
                + " vm=" + System.getProperty("java.vm.version");
            add(
                rows,
                "通用 Java Runtime 视图",
                detail,
                runtime.availableProcessors() > 0 &&
                    runtime.maxMemory() > 0 &&
                    !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用 Java Runtime 视图", describe(error), false);
        }
    }

    private static void appendRandomDevice(List<String> rows) {
        FileInputStream input = null;
        try {
            input = new FileInputStream("/dev/urandom");
            byte[] value = new byte[16];
            int count = input.read(value);
            add(
                rows,
                "通用 /dev/urandom",
                "read=" + count,
                count == value.length
            );
        } catch (Throwable error) {
            add(rows, "通用 /dev/urandom", describe(error), false);
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Throwable ignored) {
                    // Best effort close.
                }
            }
        }
    }

    private static int countLinesStartingWith(String content, String prefix) {
        int count = 0;
        for (String line : content.split("\n")) {
            if (line.startsWith(prefix)) ++count;
        }
        return count;
    }

    private static long parseMeminfoKb(String content, String key) {
        for (String line : content.split("\n")) {
            if (!line.startsWith(key)) continue;
            String[] values = line.substring(key.length()).trim().split("\\s+");
            if (values.length == 0) return -1;
            try {
                return Long.parseLong(values[0]);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean containsHostMarker(String value) {
        if (value == null) return false;
        for (String marker : HOST_MARKERS) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private static String compact(String value) {
        return value.replace('\n', '|').trim();
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
