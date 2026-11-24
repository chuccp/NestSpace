package com.coke.testapp;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;

import java.util.List;

/** Compares the main process with an explicit target secondary process. */
final class MultiProcessSurfaceProbe {
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

    private MultiProcessSurfaceProbe() {}

    static void appendRows(Activity activity, List<String> rows) {
        try {
            Bundle result = activity.getContentResolver().call(
                Uri.parse("content://com.coke.testapp.workerprovider"),
                "multi-process-integrity",
                null,
                null
            );
            if (result == null) {
                add(rows, "通用多进程身份", "provider result=null", false);
                return;
            }
            String packageName = activity.getPackageName();
            ApplicationInfo main = activity.getApplicationInfo();
            int workerUid = result.getInt("workerUid", -1);
            int workerPid = result.getInt("workerPid", -1);
            int packageUid = result.getInt("workerPackageUid", -1);
            int applicationUid = result.getInt(
                "workerApplicationUid",
                -1
            );
            String process = result.getString("workerProcess", "");
            String cmdline = result.getString("workerCmdline", "");
            String statusUid = result.getString("workerStatusUid", "");
            long dataUid = result.getLong("workerDataDirUid", -1);
            String detail = "pid=" + workerPid
                + " process=" + process
                + " uid=" + workerUid
                + " packageUid=" + packageUid
                + " applicationUid=" + applicationUid
                + " statusUid=" + statusUid
                + " dataUid=" + dataUid
                + " cmdline=" + cmdline;
            add(
                rows,
                "通用多进程身份",
                detail,
                workerPid > 0 &&
                    workerPid != Process.myPid() &&
                    process.startsWith(packageName + ":") &&
                    cmdline.startsWith(process) &&
                    workerUid == Process.myUid() &&
                    packageUid == workerUid &&
                    applicationUid == workerUid &&
                    statusUid.contains(String.valueOf(workerUid)) &&
                    dataUid == workerUid &&
                    !containsHostMarker(detail)
            );

            String source = result.getString("workerSourceDir", "");
            String publicSource = result.getString(
                "workerPublicSourceDir",
                ""
            );
            String nativeDir = result.getString(
                "workerNativeLibraryDir",
                ""
            );
            String dataDir = result.getString("workerDataDir", "");
            String deviceDir = result.getString(
                "workerDeviceDataDir",
                ""
            );
            String packageView = "package="
                + result.getString("workerPackage", "")
                + " source=" + source
                + " public=" + publicSource
                + " native=" + nativeDir
                + " data=" + dataDir
                + " device=" + deviceDir;
            add(
                rows,
                "通用多进程包与存储视图",
                packageView,
                packageName.equals(result.getString("workerPackage", "")) &&
                    source.equals(main.sourceDir) &&
                    source.equals(publicSource) &&
                    nativeDir.equals(main.nativeLibraryDir) &&
                    dataDir.equals("/data/user/0/" + packageName) &&
                    deviceDir.equals("/data/user_de/0/" + packageName) &&
                    !containsHostMarker(packageView)
            );

            String loader = result.getString("workerClassLoader", "");
            String loaderParent = result.getString(
                "workerClassLoaderParent",
                ""
            );
            String threadLoader = result.getString(
                "workerThreadClassLoader",
                ""
            );
            String loaderDetail = "loader=" + loader
                + " parent=" + loaderParent
                + " thread=" + threadLoader;
            add(
                rows,
                "通用多进程 ClassLoader",
                loaderDetail,
                "dalvik.system.PathClassLoader".equals(loader) &&
                    "java.lang.BootClassLoader".equals(loaderParent) &&
                    ("null".equals(threadLoader) ||
                        loader.equals(threadLoader)) &&
                    !containsHostMarker(loaderDetail)
            );

            PackageManager manager = activity.getPackageManager();
            PackageInfo direct = manager.getPackageInfo(packageName, 0);
            add(
                rows,
                "通用多进程 PackageManager 身份",
                "pmClass=" + result.getString("workerPmClass", "")
                    + " signatureRelation="
                    + manager.checkSignatures(packageName, packageName),
                direct.applicationInfo != null &&
                    direct.applicationInfo.uid == workerUid &&
                    manager.checkSignatures(packageName, packageName) ==
                        PackageManager.SIGNATURE_MATCH &&
                    !containsHostMarker(
                        result.getString("workerPmClass", "")
                    )
            );
        } catch (Throwable error) {
            add(rows, "通用多进程身份", describe(error), false);
        }
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
}
