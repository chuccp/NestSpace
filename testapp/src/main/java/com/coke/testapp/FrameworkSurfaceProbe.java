package com.coke.testapp;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Cross-checks framework views that should describe the same installed app. */
final class FrameworkSurfaceProbe {
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

    private FrameworkSurfaceProbe() {}

    static void appendRows(Activity activity, List<String> rows) {
        appendPackageViews(activity, rows);
        appendApplicationInfo(activity, rows);
        appendApkMetadata(activity, rows);
        appendProcessViews(activity, rows);
        appendActivityThreadViews(rows);
    }

    private static void appendApkMetadata(
        Activity activity,
        List<String> rows
    ) {
        try {
            PackageManager manager = activity.getPackageManager();
            String packageName = activity.getPackageName();
            PackageInfo installed = manager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            );
            String source = installed.applicationInfo.sourceDir;
            File apk = new File(source).getCanonicalFile();
            PackageInfo archive = manager.getPackageArchiveInfo(
                apk.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES |
                    PackageManager.GET_META_DATA
            );
            ApplicationInfo installedApp = manager.getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA
            );
            ApplicationInfo archiveApp = archive == null
                ? null
                : archive.applicationInfo;
            boolean metadataMatches =
                installedApp.metaData != null &&
                archiveApp != null &&
                archiveApp.metaData != null &&
                "surface-value".equals(
                    installedApp.metaData.getString(
                        "INTEGRITY_PROBE_STRING"
                    )
                ) &&
                installedApp.metaData.getInt("INTEGRITY_PROBE_INT") == 731 &&
                installedApp.metaData.getBoolean(
                    "INTEGRITY_PROBE_BOOL"
                ) &&
                installedApp.metaData.getString(
                    "INTEGRITY_PROBE_STRING"
                ).equals(
                    archiveApp.metaData.getString(
                        "INTEGRITY_PROBE_STRING"
                    )
                ) &&
                installedApp.metaData.getInt("INTEGRITY_PROBE_INT") ==
                    archiveApp.metaData.getInt("INTEGRITY_PROBE_INT") &&
                installedApp.metaData.getBoolean(
                    "INTEGRITY_PROBE_BOOL"
                ) == archiveApp.metaData.getBoolean(
                    "INTEGRITY_PROBE_BOOL"
                );
            String installedDigest = signerDigest(installed);
            String archiveDigest = signerDigest(archive);
            android.system.StructStat stat = Os.stat(
                apk.getAbsolutePath()
            );
            byte[] xattr = Os.getxattr(
                apk.getAbsolutePath(),
                "security.selinux"
            );
            String seLinux = xattr == null
                ? ""
                : new String(xattr, StandardCharsets.UTF_8).replace("\0", "");
            String detail = "path=" + apk.getAbsolutePath()
                + " installed=" + installed.getLongVersionCode()
                + " archive=" + (archive == null
                    ? "null"
                    : archive.getLongVersionCode())
                + " installedDigest=" + installedDigest
                + " archiveDigest=" + archiveDigest
                + " uid=" + stat.st_uid
                + " gid=" + stat.st_gid
                + " mode=" + Integer.toOctalString(stat.st_mode)
                + " selinux=" + seLinux;
            add(
                rows,
                "通用 APK 归档/文件身份",
                detail,
                archive != null &&
                    packageName.equals(archive.packageName) &&
                    installed.getLongVersionCode() ==
                        archive.getLongVersionCode() &&
                    installedDigest.length() == 64 &&
                    installedDigest.equals(archiveDigest) &&
                    metadataMatches &&
                    stat.st_uid == 1000 &&
                    stat.st_gid == 1000 &&
                    seLinux.contains("apk_data_file") &&
                    !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用 APK 归档/文件身份", describe(error), false);
        }
    }

    private static void appendPackageViews(
        Activity activity,
        List<String> rows
    ) {
        try {
            PackageManager manager = activity.getPackageManager();
            String packageName = activity.getPackageName();
            PackageInfo direct = manager.getPackageInfo(packageName, 0);
            ApplicationInfo directApplication =
                manager.getApplicationInfo(packageName, 0);

            PackageInfo installedPackage = null;
            for (PackageInfo item : manager.getInstalledPackages(0)) {
                if (packageName.equals(item.packageName)) {
                    installedPackage = item;
                    break;
                }
            }
            ApplicationInfo installedApplication = null;
            for (ApplicationInfo item :
                manager.getInstalledApplications(0)) {
                if (packageName.equals(item.packageName)) {
                    installedApplication = item;
                    break;
                }
            }
            boolean packageMatch = installedPackage != null &&
                installedPackage.getLongVersionCode() ==
                    direct.getLongVersionCode();
            boolean applicationMatch = installedApplication != null &&
                installedApplication.uid == directApplication.uid &&
                packageName.equals(installedApplication.packageName);
            add(
                rows,
                "通用 PackageManager 包视图一致",
                "direct=" + direct.getLongVersionCode()
                    + " installed=" + (installedPackage == null
                        ? "missing"
                        : installedPackage.getLongVersionCode())
                    + " appUid=" + directApplication.uid
                    + " listedUid=" + (installedApplication == null
                        ? -1
                        : installedApplication.uid),
                packageMatch && applicationMatch &&
                    directApplication.uid == Process.myUid()
            );

            Intent launch = manager.getLaunchIntentForPackage(packageName);
            String launchPackage = launch == null || launch.getComponent() == null
                ? ""
                : launch.getComponent().getPackageName();
            add(
                rows,
                "通用 PackageManager 启动入口",
                launch == null
                    ? "launch=null"
                    : "launch=" + launch.getComponent(),
                launch != null && packageName.equals(launchPackage)
            );

            int[] gids = manager.getPackageGids(packageName);
            add(
                rows,
                "通用 PackageManager GID 视图",
                java.util.Arrays.toString(gids),
                gids != null &&
                    !containsHostMarker(java.util.Arrays.toString(gids))
            );

            int relationship = manager.checkSignatures(
                packageName,
                packageName
            );
            add(
                rows,
                "通用 PackageManager 签名关系",
                "checkSignatures=" + relationship,
                relationship == PackageManager.SIGNATURE_MATCH
            );

            String installer = manager.getInstallerPackageName(packageName);
            String detail = "installer=" + installer;
            boolean passed = !containsHostMarker(installer);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo source =
                    manager.getInstallSourceInfo(packageName);
                detail += " initiating=" +
                    source.getInitiatingPackageName()
                    + " originating=" +
                    source.getOriginatingPackageName()
                    + " updateOwner=" +
                    source.getUpdateOwnerPackageName();
                passed = passed &&
                    !containsHostMarker(source.getInitiatingPackageName()) &&
                    !containsHostMarker(source.getOriginatingPackageName()) &&
                    !containsHostMarker(source.getUpdateOwnerPackageName());
            }
            add(rows, "通用 PackageManager 安装来源", detail, passed);
        } catch (Throwable error) {
            add(rows, "通用 PackageManager 包视图一致", describe(error), false);
        }
    }

    private static void appendApplicationInfo(
        Activity activity,
        List<String> rows
    ) {
        try {
            String packageName = activity.getPackageName();
            ApplicationInfo info = activity.getApplicationInfo();
            String detail = "package=" + info.packageName
                + " uid=" + info.uid
                + " process=" + info.processName
                + " source=" + info.sourceDir
                + " public=" + info.publicSourceDir
                + " native=" + info.nativeLibraryDir
                + " data=" + info.dataDir
                + " device=" + info.deviceProtectedDataDir
                + " splits=" +
                    java.util.Arrays.toString(info.splitSourceDirs);
            boolean expectedData = info.dataDir != null &&
                normalizedDataDir(info.dataDir).equals(
                    "/data/user/0/" + packageName
                );
            boolean expectedDevice =
                info.deviceProtectedDataDir == null ||
                normalizedDataDir(info.deviceProtectedDataDir).equals(
                    "/data/user_de/0/" + packageName
                );
            add(
                rows,
                "通用 ApplicationInfo 路径语义",
                detail,
                packageName.equals(info.packageName) &&
                    info.uid == Process.myUid() &&
                    info.sourceDir != null &&
                    info.sourceDir.equals(info.publicSourceDir) &&
                    expectedData && expectedDevice &&
                    !containsHostMarker(detail) &&
                    !containsHostMarker(
                        java.util.Arrays.toString(info.splitSourceDirs)
                    )
            );
        } catch (Throwable error) {
            add(rows, "通用 ApplicationInfo 路径语义", describe(error), false);
        }
    }

    private static void appendProcessViews(
        Activity activity,
        List<String> rows
    ) {
        try {
            String packageName = activity.getPackageName();
            Context application = activity.getApplicationContext();
            Context base = activity.getBaseContext();
            String applicationProcess = Build.VERSION.SDK_INT >= 28
                ? activity.getApplication().getProcessName()
                : application.getPackageName();
            String detail = "activity=" + activity.getPackageName()
                + " base=" + base.getPackageName()
                + " application=" + application.getPackageName()
                + " process=" + applicationProcess
                + " op=" + activity.getOpPackageName();
            add(
                rows,
                "通用 Context 包名与进程身份",
                detail,
                packageName.equals(base.getPackageName()) &&
                    packageName.equals(application.getPackageName()) &&
                    packageName.equals(applicationProcess) &&
                    packageName.equals(activity.getOpPackageName()) &&
                    !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用 Context 包名与进程身份", describe(error), false);
        }

        try {
            ActivityManager manager = activity.getSystemService(
                ActivityManager.class
            );
            ActivityManager.RunningAppProcessInfo state =
                new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(state);
            String detail = "pid=" + state.pid
                + " expectedPid=" + Process.myPid()
                + " uid=" + state.uid
                + " expectedUid=" + Process.myUid()
                + " process=" + state.processName
                + " importance=" + state.importance;
            boolean managerOwn = manager != null;
            if (manager != null) {
                managerOwn = false;
                List<ActivityManager.RunningAppProcessInfo> processes =
                    manager.getRunningAppProcesses();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo process :
                        processes) {
                        if (process.pid == Process.myPid()) {
                            managerOwn = process.uid == Process.myUid() &&
                                process.processName.startsWith(
                                    activity.getPackageName()
                                );
                            break;
                        }
                    }
                }
            }
            add(
                rows,
                "通用 ActivityManager 当前进程",
                detail + " listed=" + managerOwn,
                state.pid == Process.myPid() &&
                    state.uid == Process.myUid() &&
                    (state.processName == null ||
                        state.processName.startsWith(
                            activity.getPackageName()
                        )) &&
                    managerOwn && !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用 ActivityManager 当前进程", describe(error), false);
        }

        String cmdline = compact(read("/proc/self/cmdline")).replace('|', ' ');
        String status = read("/proc/self/status");
        String procName = field(status, "Name:");
        add(
            rows,
            "通用 proc 进程名视图",
            "cmdline=" + cmdline.trim()
                + " statusName=" + procName,
            cmdline.trim().startsWith(activity.getPackageName()) &&
                !procName.isEmpty() &&
                cmdline.trim().endsWith(procName) &&
                !containsHostMarker(cmdline) &&
                !containsHostMarker(procName)
        );
    }

    private static void appendActivityThreadViews(List<String> rows) {
        try {
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("currentActivityThread")
                .invoke(null);
            String packageName = invokeString(
                type,
                thread,
                "currentPackageName"
            );
            String processName = invokeString(
                type,
                thread,
                "currentProcessName"
            );
            String opPackageName = invokeString(
                type,
                thread,
                "currentOpPackageName"
            );
            String detail = "package=" + packageName
                + " process=" + processName
                + " op=" + opPackageName;
            add(
                rows,
                "通用 ActivityThread 当前身份",
                detail,
                !packageName.isEmpty() &&
                    packageName.equals(processName) &&
                    (opPackageName.startsWith("error=") ||
                        packageName.equals(opPackageName)) &&
                    !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用 ActivityThread 当前身份", describe(error), false);
        }
    }

    private static String invokeString(
        Class<?> type,
        Object receiver,
        String name
    ) {
        try {
            Method method = type.getMethod(name);
            Object value = method.invoke(receiver);
            return value == null ? "null" : String.valueOf(value);
        } catch (Throwable error) {
            return "error=" + describe(error);
        }
    }

    private static String normalizedDataDir(String value) {
        return value.replace("/data/data/", "/data/user/0/");
    }

    private static String signerDigest(PackageInfo info) {
        try {
            if (info == null ||
                info.signingInfo == null ||
                info.signingInfo.getApkContentsSigners().length == 0) {
                return "missing";
            }
            java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(
                info.signingInfo.getApkContentsSigners()[0].toByteArray()
            );
            StringBuilder result = new StringBuilder(value.length * 2);
            for (byte item : value) {
                result.append(String.format(
                    java.util.Locale.US,
                    "%02x",
                    item
                ));
            }
            return result.toString();
        } catch (Throwable error) {
            return "error=" + describe(error);
        }
    }

    private static boolean containsHostMarker(String value) {
        if (value == null) return false;
        for (String marker : HOST_MARKERS) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private static String read(String path) {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            int value;
            while ((value = reader.read()) != -1) {
                result.append((char) value);
            }
        } catch (Throwable error) {
            return "error=" + describe(error);
        }
        return result.toString();
    }

    private static String field(String report, String prefix) {
        for (String line : report.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String compact(String value) {
        return value.replace('\n', '|').replace('\0', '|').trim();
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null &&
            cause instanceof java.lang.reflect.InvocationTargetException) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() +
            (cause.getMessage() == null ? "" : ": " + cause.getMessage());
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
