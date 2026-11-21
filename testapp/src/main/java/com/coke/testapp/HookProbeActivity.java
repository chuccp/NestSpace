package com.coke.testapp;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import android.system.StructStat;
import android.system.StructStatVfs;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Focused identity facade probe. Run it once as a directly installed app and
 * once through NestSpace, then compare the same report.
 */
public class HookProbeActivity extends Activity {
    private static final String TAG = "HookProbe";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NativeProbe.ensureLoaded();
        String report = buildReport();
        TextView text = new TextView(this);
        text.setText(report);
        text.setTextIsSelectable(true);
        text.setTextSize(12);
        text.setPadding(24, 24, 24, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
        Log.i(TAG, report);
        writeReport(report);
    }

    private String buildReport() {
        List<String> lines = new ArrayList<>();
        add(lines, "package", getPackageName());
        add(lines, "process.myUid", Process.myUid());
        add(lines, "process.myPid", Process.myPid());
        add(lines, "process.myTid", Process.myTid());
        add(lines, "process.processName", safe(Process::myProcessName));
        add(lines, "system.userDir", System.getProperty("user.dir"));
        add(lines, "context.opPackage", safe(this::getOpPackageName));
        add(lines, "activityThread.package", activityThreadValue("currentPackageName"));
        add(lines, "activityThread.opPackage", activityThreadValue("currentOpPackageName"));
        add(lines, "binder.callingUid", Binder.getCallingUid());
        add(lines, "binder.callingUidOrThrow", safe(Binder::getCallingUidOrThrow));
        add(lines, "binder.callingPid", Binder.getCallingPid());
        add(lines, "binder.workSourceUid", Binder.getCallingWorkSourceUid());
        add(lines, "binder.callingUser", safe(() -> Binder.getCallingUserHandle().toString()));
        add(lines, "binder.clearRestore", safe(() -> {
            long token = Binder.clearCallingIdentity();
            Binder.restoreCallingIdentity(token);
            return token != 0;
        }));

        ApplicationInfo info = getApplicationInfo();
        add(lines, "appInfo.uid", info.uid);
        add(lines, "appInfo.flags", "0x" + Long.toHexString(info.flags));
        add(lines, "appInfo.dataDir", info.dataDir);
        add(lines, "appInfo.sourceDir", info.sourceDir);
        add(lines, "appInfo.nativeLibraryDir", info.nativeLibraryDir);
        add(lines, "appInfo.deviceProtectedDataDir", info.deviceProtectedDataDir);
        add(lines, "packageInfo.firstInstallTime", info.packageName == null
            ? -1
            : safe(() -> getPackageManager().getPackageInfo(getPackageName(), 0).firstInstallTime));
        add(lines, "packageInfo.lastUpdateTime", safe(
            () -> getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime
        ));
        add(lines, "packageInfo.version", safe(() -> {
            android.content.pm.PackageInfo value =
                getPackageManager().getPackageInfo(getPackageName(), 0);
            return value.versionName + "/" + value.getLongVersionCode();
        }));
        add(lines, "packageInfo.uid", safe(() -> {
            android.content.pm.PackageInfo value =
                getPackageManager().getPackageInfo(getPackageName(), 0);
            return value.applicationInfo == null ? -1 : value.applicationInfo.uid;
        }));
        add(lines, "packageInfo.sourceDir", safe(
            () -> getPackageManager().getPackageInfo(getPackageName(), 0)
                .applicationInfo.sourceDir
        ));
        add(lines, "packageInfo.nativeLibraryDir", safe(
            () -> getPackageManager().getPackageInfo(getPackageName(), 0)
                .applicationInfo.nativeLibraryDir
        ));
        add(lines, "packageInfo.dataDir", safe(
            () -> getPackageManager().getPackageInfo(getPackageName(), 0)
                .applicationInfo.dataDir
        ));
        add(lines, "packageManager.gids", packageManagerGids());
        add(lines, "packageManager.packagesForUid", safe(
            () -> Arrays.toString(getPackageManager().getPackagesForUid(Process.myUid()))
        ));
        add(lines, "context.dataDir", getDataDir().getAbsolutePath());
        File external = getExternalFilesDir("hook-probe");
        if (external != null) {
            add(lines, "context.externalFiles", external.getAbsolutePath());
        }

        add(lines, "os.getuid", safe(Os::getuid));
        add(lines, "os.geteuid", safe(Os::geteuid));
        add(lines, "os.getgid", safe(Os::getgid));
        add(lines, "os.getegid", safe(Os::getegid));
        add(lines, "os.stat.procSelf", safe(() -> Os.lstat("/proc/self").toString()));
        add(lines, "os.stat.dataDir", safe(() -> Os.lstat(getDataDir().getAbsolutePath()).toString()));
        add(lines, "os.stat.apk", safe(() -> Os.lstat(info.sourceDir).toString()));
        add(lines, "os.xattr.dataDir", safe(() -> new String(Os.getxattr(
            getDataDir().getAbsolutePath(),
            "security.selinux"
        ), StandardCharsets.UTF_8)));
        add(lines, "os.xattr.apk", safe(() -> new String(Os.getxattr(
            info.sourceDir,
            "security.selinux"
        ), StandardCharsets.UTF_8)));

        String databaseDir = new File(getDataDir(), "databases").getAbsolutePath();
        String sharedPrefsDir = new File(getDataDir(), "shared_prefs").getAbsolutePath();
        String noBackupDir = getNoBackupFilesDir().getAbsolutePath();
        String externalFiles = external == null ? "" : external.getAbsolutePath();
        String externalCache = getExternalCacheDir() == null
            ? ""
            : getExternalCacheDir().getAbsolutePath();
        String dataAlias = "/data/data/" + getPackageName();
        String filesAlias = dataAlias + "/files";
        String externalSdcardAlias = externalFiles.replace(
            "/storage/emulated/0/",
            "/sdcard/"
        );
        String procSelfTask = "/proc/self/task/" + Process.myTid();
        Object[][] paths = {
            {"dataDir", getDataDir().getAbsolutePath()},
            {"dataAlias", dataAlias},
            {"filesDir", getFilesDir().getAbsolutePath()},
            {"filesAlias", filesAlias},
            {"cacheDir", getCacheDir().getAbsolutePath()},
            {"codeCacheDir", getCodeCacheDir().getAbsolutePath()},
            {"noBackupDir", noBackupDir},
            {"databaseDir", databaseDir},
            {"sharedPrefsDir", sharedPrefsDir},
            {"externalFiles", externalFiles},
            {"externalSdcardAlias", externalSdcardAlias},
            {"externalCache", externalCache},
            {"apk", info.sourceDir},
            {"nativeLibraryDir", info.nativeLibraryDir == null ? "" : info.nativeLibraryDir},
            {"procSelf", "/proc/self"},
            {"procStatus", "/proc/self/status"},
            {"procCmdline", "/proc/self/cmdline"},
            {"procAttrCurrent", "/proc/self/attr/current"},
            {"procThreadSelfStatus", "/proc/thread-self/status"},
            {"procThreadSelfAttr", "/proc/thread-self/attr/current"},
            {"procSelfTaskRoot", "/proc/self/task"},
            {"procSelfTask", procSelfTask},
            {"procSelfTaskComm", procSelfTask + "/comm"},
            {"procSelfTaskStatus", procSelfTask + "/status"},
            {"procCwd", "/proc/self/cwd"},
            {"procExe", "/proc/self/exe"},
            {"procRoot", "/proc/self/root"},
            {"procUserNs", "/proc/self/ns/user"},
        };
        for (Object[] entry : paths) {
            addPathProbe(lines, String.valueOf(entry[0]), String.valueOf(entry[1]));
        }

        String nativeReport = NativeProbe.nativeHookProbe(
            getDataDir().getAbsolutePath(),
            info.sourceDir,
            external == null ? "" : external.getAbsolutePath()
        );
        lines.add("---- native ----");
        lines.add(nativeReport == null ? "native=null" : nativeReport);
        return String.join("\n", lines);
    }

    private void addPathProbe(List<String> lines, String label, String path) {
        if (path == null || path.isEmpty()) {
            add(lines, label, "<empty>");
            return;
        }
        add(lines, label + ".path", path);
        add(lines, label + ".canonical", safe(() -> new File(path).getCanonicalPath()));
        add(lines, label + ".lstat", safe(() -> compact(Os.lstat(path))));
        add(lines, label + ".stat", safe(() -> compact(Os.stat(path))));
        add(lines, label + ".access", safe(() -> Os.access(path, OsConstants.R_OK)));
        add(lines, label + ".readlink", safe(() -> Os.readlink(path)));
        add(lines, label + ".xattr.selinux", safe(() -> new String(
            Os.getxattr(path, "security.selinux"),
            StandardCharsets.UTF_8
        ).replace("\0", "")));
        if (label.endsWith("Dir") || label.equals("dataDir") ||
            label.equals("externalFiles") || label.equals("externalCache")) {
            add(lines, label + ".statvfs", safe(() -> compact(Os.statvfs(path))));
        }
    }

    private static String compact(StructStat value) {
        return String.format(
            Locale.US,
            "dev=%d ino=%d mode=%o uid=%d gid=%d nlink=%d size=%d",
            value.st_dev,
            value.st_ino,
            value.st_mode,
            value.st_uid,
            value.st_gid,
            value.st_nlink,
            value.st_size
        );
    }

    private static String compact(StructStatVfs value) {
        return String.format(
            Locale.US,
            "bsize=%d frsize=%d blocks=%d bfree=%d bavail=%d files=%d ffree=%d fsid=%d",
            value.f_bsize,
            value.f_frsize,
            value.f_blocks,
            value.f_bfree,
            value.f_bavail,
            value.f_files,
            value.f_ffree,
            value.f_fsid
        );
    }

    private String activityThreadValue(String methodName) {
        try {
            Class<?> type = Class.forName("android.app.ActivityThread");
            Method method = type.getMethod(methodName);
            Object value = method.invoke(null);
            return String.valueOf(value);
        } catch (Throwable error) {
            return "error:" + error.getClass().getSimpleName();
        }
    }

    private String packageManagerGids() {
        try {
            Method method = getPackageManager().getClass()
                .getMethod("getPackageGids", String.class);
            int[] values = (int[]) method.invoke(getPackageManager(), getPackageName());
            return values == null ? "null" : Arrays.toString(values);
        } catch (Throwable error) {
            return "error:" + error.getClass().getSimpleName();
        }
    }

    private void writeReport(String report) {
        try (FileWriter writer = new FileWriter(new File(getFilesDir(), "hook-probe.txt"))) {
            writer.write(report);
        } catch (Throwable error) {
            Log.w(TAG, "write data report failed", error);
        }
        File external = getExternalFilesDir("hook-probe");
        if (external == null) return;
        if (!external.isDirectory()) external.mkdirs();
        try (FileWriter writer = new FileWriter(new File(external, "hook-probe.txt"))) {
            writer.write(report);
        } catch (Throwable error) {
            Log.w(TAG, "write external report failed", error);
        }
    }

    private static void add(List<String> lines, String name, Object value) {
        lines.add(name + "=" + String.valueOf(value).replace('\n', ' '));
    }

    private static String safe(Supplier supplier) {
        try {
            return String.valueOf(supplier.get());
        } catch (Throwable error) {
            if (error instanceof ErrnoException) {
                ErrnoException errno = (ErrnoException) error;
                return "error:errno=" + errno.errno + ":" + errno.getMessage();
            }
            return "error:" + error.getClass().getSimpleName();
        }
    }

    private interface Supplier {
        Object get() throws Throwable;
    }
}
