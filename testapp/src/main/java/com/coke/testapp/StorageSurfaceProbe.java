package com.coke.testapp;

import android.app.Activity;
import android.content.Context;
import android.os.Process;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Cross-checks Android storage roots, file aliases and actual writes. */
final class StorageSurfaceProbe {
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

    private StorageSurfaceProbe() {}

    static void appendRows(Activity activity, List<String> rows) {
        appendCredentialStorage(activity, rows);
        appendDeviceStorage(activity, rows);
        appendExternalStorage(activity, rows);
    }

    private static void appendCredentialStorage(
        Activity activity,
        List<String> rows
    ) {
        String packageName = activity.getPackageName();
        File dataDir = activity.getDataDir();
        List<NamedDirectory> directories = new ArrayList<>();
        directories.add(new NamedDirectory("files", activity.getFilesDir()));
        directories.add(new NamedDirectory("cache", activity.getCacheDir()));
        directories.add(new NamedDirectory(
            "codeCache",
            activity.getCodeCacheDir()
        ));
        directories.add(new NamedDirectory(
            "noBackup",
            activity.getNoBackupFilesDir()
        ));
        directories.add(new NamedDirectory(
            "databases",
            activity.getDatabasePath("surface.db").getParentFile()
        ));

        String expectedRoot = "/data/user/0/" + packageName;
        StringBuilder detail = new StringBuilder();
        boolean passed = normalized(dataDir.getAbsolutePath())
            .equals(expectedRoot);
        for (NamedDirectory item : directories) {
            File directory = item.directory;
            if (detail.length() != 0) detail.append(' ');
            detail.append(item.name).append('=')
                .append(directory == null ? "null" : directory.getAbsolutePath());
            passed = passed &&
                directory != null &&
                directory.getAbsolutePath().startsWith(
                    dataDir.getAbsolutePath() + "/"
                ) &&
                !containsHostMarker(directory.getAbsolutePath()) &&
                canRoundTrip(directory, "credential-" + item.name);
        }
        add(
            rows,
            "通用凭据保护存储根",
            "dataDir=" + dataDir.getAbsolutePath() + " " + detail,
            passed
        );

        appendCanonicalAndStat(
            rows,
            "通用凭据保护存储 canonical/stat",
            dataDir,
            activity.getFilesDir(),
            expectedRoot
        );
    }

    private static void appendDeviceStorage(
        Activity activity,
        List<String> rows
    ) {
        try {
            String packageName = activity.getPackageName();
            Context device = activity.createDeviceProtectedStorageContext();
            File dataDir = device.getDataDir();
            File filesDir = device.getFilesDir();
            File cacheDir = device.getCacheDir();
            String expected = "/data/user_de/0/" + packageName;
            String detail = "device=" + device.isDeviceProtectedStorage()
                + " data=" + dataDir.getAbsolutePath()
                + " files=" + filesDir.getAbsolutePath()
                + " cache=" + cacheDir.getAbsolutePath();
            add(
                rows,
                "通用设备保护存储根",
                detail,
                device.isDeviceProtectedStorage() &&
                    normalized(dataDir.getAbsolutePath()).equals(expected) &&
                    filesDir.getAbsolutePath().startsWith(
                        dataDir.getAbsolutePath() + "/"
                    ) &&
                    cacheDir.getAbsolutePath().startsWith(
                        dataDir.getAbsolutePath() + "/"
                    ) &&
                    canRoundTrip(filesDir, "device-files") &&
                    canRoundTrip(cacheDir, "device-cache") &&
                    !containsHostMarker(detail)
            );
            appendCanonicalAndStat(
                rows,
                "通用设备保护存储 canonical/stat",
                dataDir,
                filesDir,
                expected
            );
        } catch (Throwable error) {
            add(rows, "通用设备保护存储根", describe(error), false);
        }
    }

    private static void appendExternalStorage(
        Activity activity,
        List<String> rows
    ) {
        try {
            File files = activity.getExternalFilesDir(null);
            File cache = activity.getExternalCacheDir();
            String packageName = activity.getPackageName();
            String detail = "files=" + (files == null
                    ? "null"
                    : files.getAbsolutePath())
                + " cache=" + (cache == null
                    ? "null"
                    : cache.getAbsolutePath());
            add(
                rows,
                "通用外部存储目录",
                detail,
                files != null && cache != null &&
                    files.getAbsolutePath().contains(
                        "/Android/data/" + packageName + "/"
                    ) &&
                    cache.getAbsolutePath().contains(
                        "/Android/data/" + packageName + "/"
                    ) &&
                    canRoundTrip(files, "external-files") &&
                    canRoundTrip(cache, "external-cache") &&
                    !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用外部存储目录", describe(error), false);
        }
    }

    private static void appendCanonicalAndStat(
        List<String> rows,
        String label,
        File dataDir,
        File filesDir,
        String expectedRoot
    ) {
        try {
            String dataCanonical = dataDir.getCanonicalPath();
            String filesCanonical = filesDir.getCanonicalPath();
            long uid = Os.stat(dataDir.getAbsolutePath()).st_uid;
            add(
                rows,
                label,
                "dataCanonical=" + dataCanonical
                    + " filesCanonical=" + filesCanonical
                    + " uid=" + uid,
                normalized(dataCanonical).equals(expectedRoot) &&
                    normalized(filesCanonical).startsWith(
                        expectedRoot + "/"
                    ) &&
                    uid == Process.myUid() &&
                    !containsHostMarker(dataCanonical) &&
                    !containsHostMarker(filesCanonical)
            );
        } catch (Throwable error) {
            add(rows, label, describe(error), false);
        }
    }

    private static boolean canRoundTrip(File directory, String name) {
        if (directory == null) return false;
        File probe = new File(
            directory,
            ".surface-" + name + "-" + System.nanoTime()
        );
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return false;
            byte[] expected = ("probe-" + name).getBytes();
            try (FileOutputStream output = new FileOutputStream(probe)) {
                output.write(expected);
            }
            if (!probe.isFile() || probe.length() != expected.length) {
                return false;
            }
            return probe.getCanonicalPath().startsWith(
                directory.getCanonicalPath() + "/"
            );
        } catch (Throwable ignored) {
            return false;
        } finally {
            probe.delete();
        }
    }

    private static String normalized(String value) {
        return value == null
            ? ""
            : value.replace("/data/data/", "/data/user/0/");
    }

    private static boolean containsHostMarker(String value) {
        if (value == null) return false;
        for (String marker : HOST_MARKERS) {
            if (value.contains(marker)) return true;
        }
        return false;
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

    private static final class NamedDirectory {
        final String name;
        final File directory;

        NamedDirectory(String name, File directory) {
            this.name = name;
            this.directory = directory;
        }
    }
}
