package com.coke.testapp;

import android.app.Activity;
import android.system.Os;

import dalvik.system.PathClassLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recreates the platform inputs used by common virtual-box and native
 * integrity checks. It deliberately reports the raw values first so a failure
 * identifies which Android semantic is inconsistent.
 */
final class PlatformEnvironmentProbe {
    private static final String[] GENERIC_VIRTUAL_MARKERS = {
        "com.coke.nestspace",
        "/nestspace",
        "libnestspace",
        "NestSpace",
        "/virtual/",
        "/sandbox/",
        "/vspace/",
        "/container/",
        "libsandbox_64.so",
        "libsandbox_32.so",
        "libsandhook-native.so"
    };

    private PlatformEnvironmentProbe() {}

    static void appendRows(List<String> rows, Activity activity) {
        appendVirtualBoxProbe(rows);
        appendNativeEnvironmentProbe(rows, activity);
    }

    /**
     * Mirrors the package-service fingerprint used by VirtualBoxDetectManager.
     * Some SDKs stop at BinderProxy, while others reach a PathClassLoader's
     * nativeLibraryDirectories and derive a virtual-box identifier from the
     * install path. Both inputs must look like a normal installed Android app.
     */
    private static void appendVirtualBoxProbe(List<String> rows) {
        Object service = null;
        String serviceClass;
        try {
            Class<?> serviceManager = Class.forName(
                "android.os.ServiceManager"
            );
            Method getService = serviceManager.getDeclaredMethod(
                "getService",
                String.class
            );
            getService.setAccessible(true);
            service = getService.invoke(null, "package");
            if (service == null) {
                add(rows, "VirtualBox package service", "null", false);
                return;
            }
            serviceClass = service.getClass().getName();
            add(
                rows,
                "VirtualBox package service class",
                serviceClass,
                !startsWith(serviceClass, "android.os.BinderProxy")
                    || serviceClass.equals("android.os.BinderProxy")
            );
        } catch (Throwable error) {
            add(rows, "VirtualBox package service", describe(error), false);
            return;
        }

        String identifier = null;
        String identifierSource = "unavailable";
        try {
            ClassLoader loader = service.getClass().getClassLoader();
            add(
                rows,
                "VirtualBox package service loader",
                loader == null
                    ? "null"
                    : loader.getClass().getName() + " " + loader,
                loader != null
            );
            if (loader == null) return;
            if (!(loader instanceof PathClassLoader)) {
                add(
                    rows,
                    "VirtualBox class-loader cast",
                    "not PathClassLoader; original detector catches the cast "
                        + "failure and returns null",
                    true
                );
                return;
            }

            Object pathList = null;
            for (Class<?> type = loader.getClass();
                 type != null;
                 type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField("pathList");
                    field.setAccessible(true);
                    pathList = field.get(loader);
                    break;
                } catch (NoSuchFieldException ignored) {
                    // Continue through BaseDexClassLoader's hierarchy.
                }
            }
            if (pathList == null) {
                add(
                    rows,
                    "VirtualBox package service pathList",
                    "missing; original detector returns null",
                    true
                );
                return;
            }

            Field directoriesField = pathList.getClass()
                .getDeclaredField("nativeLibraryDirectories");
            directoriesField.setAccessible(true);
            Object rawDirectories = directoriesField.get(pathList);
            if (!(rawDirectories instanceof Iterable)) {
                add(
                    rows,
                    "VirtualBox nativeLibraryDirectories",
                    String.valueOf(rawDirectories),
                    false
                );
                return;
            }

            List<String> directories = new ArrayList<>();
            for (Object item : (Iterable<?>) rawDirectories) {
                if (!(item instanceof File)) continue;
                String path = ((File) item).getAbsolutePath();
                directories.add(path);
                if (identifier == null &&
                    path.startsWith("/data/app/")) {
                    int separator = path.indexOf('-');
                    if (separator > 0 && separator > 10) {
                        identifier = path.substring(10, separator);
                        identifierSource = path;
                    }
                }
            }
            String joined = String.join(" | ", directories);
            add(
                rows,
                "VirtualBox nativeLibraryDirectories",
                joined.isEmpty() ? "<empty>" : joined,
                !containsMarker(joined)
            );
            add(
                rows,
                "VirtualBox native directory host marker",
                containsMarker(joined) ? "LEAK" : "not present",
                !containsMarker(joined)
            );
        } catch (Throwable error) {
            File direct = new File(
                System.getProperty("java.library.path", "")
            );
            add(
                rows,
                "VirtualBox class-loader inspection",
                describe(error) + " java.library.path=" + direct,
                !containsMarker(direct.getPath())
            );
        }

        if (identifier == null) {
            identifier = serviceClass;
            identifierSource = "service class fallback";
        }
        add(
            rows,
            "VirtualBox computed identifier",
            identifier + " source=" + identifierSource,
            !containsMarker(identifier)
        );
    }

    /**
     * Covers the observable inputs used by libnpth-style checks: executable
     * path, parent process, cgroup/cpuset, fd links, maps and loaded sandbox
     * modules. It intentionally does not call any vendor-specific detector.
     */
    private static void appendNativeEnvironmentProbe(
        List<String> rows,
        Activity activity
    ) {
        String[] links = {
            "/proc/self/exe",
            "/proc/self/cwd",
            "/proc/self/root"
        };
        for (String link : links) {
            String value;
            try {
                value = Os.readlink(link);
            } catch (Throwable error) {
                value = describe(error);
            }
            add(
                rows,
                "NPTH " + link,
                value,
                !containsMarker(value)
            );
        }

        String status = read("/proc/self/status");
        String ppid = field(status, "PPid:");
        add(
            rows,
            "NPTH parent pid",
            ppid,
            ppid.matches("[0-9]+")
        );
        if (ppid.matches("[0-9]+")) {
            String parentComm = read("/proc/" + ppid + "/comm").trim();
            String parentCmdline = read("/proc/" + ppid + "/cmdline")
                .replace('\0', '|')
                .replace('\n', ' ');
            add(
                rows,
                "NPTH parent process",
                "comm=" + parentComm + " cmdline=" + parentCmdline,
                isZygote(parentComm, parentCmdline)
            );
        }

        for (String path : new String[]{
            "/proc/self/cgroup",
            "/proc/self/cpuset"
        }) {
            String value = compact(read(path));
            add(
                rows,
                "NPTH " + path,
                value,
                !value.startsWith("error=") && !containsMarker(value)
            );
        }

        Set<String> fdLeaks = new LinkedHashSet<>();
        File fdRoot = new File("/proc/self/fd");
        String[] files = fdRoot.list();
        if (files == null) {
            add(rows, "NPTH fd scan", "unavailable", false);
        } else {
            for (String name : files) {
                if (!name.matches("[0-9]+")) continue;
                try {
                    String value = Os.readlink(
                        new File(fdRoot, name).getAbsolutePath()
                    );
                    if (containsMarker(value)) {
                        fdLeaks.add(name + "->" + value);
                    }
                } catch (Throwable ignored) {
                    // The fd may close while the directory is being read.
                }
            }
            add(
                rows,
                "NPTH fd host leaks",
                fdLeaks.isEmpty() ? "0" : String.join("; ", fdLeaks),
                fdLeaks.isEmpty()
            );
        }

        String maps = read("/proc/self/maps");
        Set<String> mapLeaks = matchingLines(maps);
        add(
            rows,
            "NPTH maps virtual-environment lines",
            mapLeaks.isEmpty() ? "0" : String.join("; ", mapLeaks),
            !maps.startsWith("error=") && mapLeaks.isEmpty()
        );

        String environment = environment();
        Set<String> environmentLeaks = matchingLines(environment);
        add(
            rows,
            "NPTH environment host leaks",
            environmentLeaks.isEmpty()
                ? "0"
                : String.join("; ", environmentLeaks),
            environmentLeaks.isEmpty()
        );

        String packageName = activity.getPackageName();
        String dataRoot = activity.getDataDir().getAbsolutePath();
        add(
            rows,
            "NPTH target path consistency",
            "package=" + packageName
                + " data=" + dataRoot
                + " files=" + activity.getFilesDir().getAbsolutePath(),
            dataRoot.endsWith("/" + packageName)
                && activity.getFilesDir().getAbsolutePath()
                    .startsWith(dataRoot + "/")
        );
    }

    private static boolean isZygote(String comm, String cmdline) {
        // Android 15 may hide /proc/<zygote>/comm and cmdline from app UIDs.
        // A directly installed app gets the same ENOENT, so it carries no
        // virtual-container signal on this OS.
        if (comm.startsWith("error=") && cmdline.startsWith("error=")) {
            return true;
        }
        return comm.equals("zygote") ||
            comm.equals("zygote64") ||
            comm.equals("zygote32") ||
            cmdline.startsWith("zygote") ||
            cmdline.startsWith("/system/bin/app_process");
    }

    private static Set<String> matchingLines(String report) {
        Set<String> result = new LinkedHashSet<>();
        if (report == null || report.startsWith("error=")) return result;
        for (String line : report.split("\n")) {
            if (!line.isEmpty() && containsMarker(line)) {
                result.add(line);
                if (result.size() >= 6) break;
            }
        }
        return result;
    }

    private static boolean containsMarker(String value) {
        if (value == null) return false;
        for (String marker : GENERIC_VIRTUAL_MARKERS) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private static String environment() {
        StringBuilder result = new StringBuilder();
        for (String entry : System.getenv().values()) {
            if (result.length() != 0) result.append('\n');
            result.append(entry);
        }
        return result.toString();
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

    private static String compact(String value) {
        return value.replace('\n', '|').trim();
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

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    private static void add(
        List<String> rows,
        String name,
        String value,
        boolean passed
    ) {
        rows.add(
            name + ": " + value.replace('\n', ' ') +
                (passed ? " PASS" : " FAIL")
        );
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null &&
            (cause instanceof java.lang.reflect.InvocationTargetException ||
                cause instanceof java.lang.RuntimeException)) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return String.format(
            Locale.US,
            "%s%s",
            cause.getClass().getName(),
            message == null ? "" : ": " + message
        );
    }
}
