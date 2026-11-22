package com.coke.testapp;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.usage.UsageStatsManager;
import android.accounts.AccountManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
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
import java.security.MessageDigest;
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
    private Throwable nativeLoadError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            NativeProbe.ensureLoaded();
        } catch (Throwable error) {
            nativeLoadError = error;
            Log.w(TAG, "native probe unavailable", error);
        }
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
        appendCrossProcessProbes(lines);
        add(lines, "context.dataDir", getDataDir().getAbsolutePath());
        File external = getExternalFilesDir("hook-probe");
        if (external != null) {
            add(lines, "context.externalFiles", external.getAbsolutePath());
        }
        appendFileSystemProbe(lines);

        add(lines, "os.getuid", safe(Os::getuid));
        add(lines, "os.geteuid", safe(Os::geteuid));
        add(lines, "os.getgid", safe(Os::getgid));
        add(lines, "os.getegid", safe(Os::getegid));
        add(lines, "os.readlink.symlink", readlinkSymlinkProbe());
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

        add(lines, "native.load", nativeLoadError == null
            ? "ok"
            : describe(nativeLoadError));
        if (nativeLoadError != null) {
            lines.add("---- native unavailable ----");
            return String.join("\n", lines);
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

    private void appendFileSystemProbe(List<String> lines) {
        File root = new File(
            getFilesDir(),
            "filesystem-probe-" + System.nanoTime()
        );
        File source = new File(root, "source.txt");
        File renamed = new File(root, "renamed.txt");
        try {
            add(lines, "fs.mkdirs", root.mkdirs());
            try (FileWriter writer = new FileWriter(source)) {
                writer.write("filesystem-probe");
            }
            add(lines, "fs.exists", source.exists());
            add(lines, "fs.isFile", source.isFile());
            add(lines, "fs.isDirectory", root.isDirectory());
            add(lines, "fs.length", source.length());
            add(lines, "fs.lastModified", source.lastModified());
            add(lines, "fs.canRead", source.canRead());
            add(lines, "fs.canWrite", source.canWrite());
            add(lines, "fs.usableSpace", root.getUsableSpace());
            add(lines, "fs.totalSpace", root.getTotalSpace());
            add(lines, "fs.canonical", root.getCanonicalPath());
            String[] listed = root.list();
            add(lines, "fs.list", listed == null ? "null" : Arrays.toString(listed));
            add(lines, "fs.rename", source.renameTo(renamed));
            add(lines, "fs.renamedExists", renamed.isFile());
            add(lines, "fs.deleteFile", renamed.delete());
            add(lines, "fs.deleteDirectory", root.delete());
        } catch (Throwable error) {
            add(lines, "fs.error", describe(error));
        } finally {
            renamed.delete();
            source.delete();
            root.delete();
        }
    }

    private void appendCrossProcessProbes(List<String> lines) {
        String packageName = getPackageName();
        PackageManager pm = getPackageManager();
        int uid = Process.myUid();
        add(lines, "xp.pm.packageUid", safe(() -> pm.getPackageUid(packageName, 0)));
        add(lines, "xp.pm.nameForUid", safe(() -> pm.getNameForUid(uid)));
        add(lines, "xp.pm.packageGids", packageManagerGids());
        add(lines, "xp.pm.installedPackages", safe(() -> {
            List<PackageInfo> values = pm.getInstalledPackages(0);
            return packageListSummary(values, packageName);
        }));
        add(lines, "xp.pm.installedPackageNames", safe(() -> {
            List<String> values = new ArrayList<>();
            for (PackageInfo value : pm.getInstalledPackages(0)) {
                values.add(value.packageName);
            }
            values.sort(String::compareTo);
            return String.join(",", values);
        }));
        add(lines, "xp.pm.installedApplications", safe(() -> {
            List<ApplicationInfo> values = pm.getInstalledApplications(0);
            long selfCount = values.stream()
                .filter(value -> packageName.equals(value.packageName))
                .count();
            return "count=" + values.size() + ",self=" + selfCount;
        }));
        add(lines, "xp.pm.cachedPackage", safe(() -> {
            PackageInfo value = pm.getPackageInfo(packageName, 0);
            return "uid=" + value.applicationInfo.uid
                + ",version=" + value.getLongVersionCode()
                + ",source=" + value.applicationInfo.sourceDir;
        }));
        add(lines, "xp.pm.matchUninstalled", safe(() -> {
            int flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.MATCH_DISABLED_COMPONENTS;
            PackageInfo value = pm.getPackageInfo(packageName, flags);
            return "uid=" + value.applicationInfo.uid
                + ",source=" + value.applicationInfo.sourceDir;
        }));
        add(lines, "xp.signature.selfDigest", signatureDigest(packageName));
        add(lines, "xp.signature.selfCheck", safe(
            () -> pm.checkSignatures(packageName, packageName)
        ));
        add(lines, "xp.signature.androidCheck", safe(
            () -> pm.checkSignatures(packageName, "android")
        ));

        Intent launcher = new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName);
        Intent activityIntent = new Intent(this, HookProbeActivity.class)
            .setPackage(packageName);
        Intent serviceIntent = new Intent("com.coke.testapp.PING")
            .setPackage(packageName);
        Intent receiverIntent = new Intent("com.coke.testapp.PING_RECEIVER")
            .setPackage(packageName);
        add(lines, "xp.resolve.launcher", resolveActivity(launcher));
        add(lines, "xp.resolve.explicitActivity", resolveActivity(activityIntent));
        add(lines, "xp.query.launcher", queryActivityCount(launcher));
        add(lines, "xp.resolve.service", resolveService(serviceIntent));
        add(lines, "xp.query.receivers", queryReceiverCount(receiverIntent));
        add(lines, "xp.resolve.provider", resolveProvider(packageName + ".testprovider"));
        add(lines, "xp.visibility.webIntent", queryActivityCount(
            new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        ));

        add(lines, "xp.permission.camera.self",
            permissionProbe(Manifest.permission.CAMERA));
        add(lines, "xp.permission.notifications.self",
            permissionProbe(Manifest.permission.POST_NOTIFICATIONS));
        add(lines, "xp.permission.camera.uid", safe(
            () -> checkPermission(Manifest.permission.CAMERA, Process.myPid(), uid)
        ));
        add(lines, "xp.permission.calling", safe(
            () -> checkCallingPermission(Manifest.permission.CAMERA)
        ));

        AppOpsManager appOps = getSystemService(AppOpsManager.class);
        add(lines, "xp.appops.camera", checkAppOp(
            appOps, AppOpsManager.OPSTR_CAMERA, uid, packageName
        ));
        add(lines, "xp.appops.usage", checkAppOp(
            appOps, AppOpsManager.OPSTR_GET_USAGE_STATS, uid, packageName
        ));

        add(lines, "xp.binder.beforePackageCall", Binder.getCallingUid());
        add(lines, "xp.pm.callResult", safe(() -> pm.getPackageUid(packageName, 0)));
        add(lines, "xp.binder.afterPackageCall", Binder.getCallingUid());
        add(lines, "xp.binder.clearedPackageResult", safe(() -> {
            long token = Binder.clearCallingIdentity();
            try {
                return pm.getPackageUid(packageName, 0);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }));
        add(lines, "xp.binder.afterClearRestore", Binder.getCallingUid());

        add(lines, "xp.provider.query", providerQuery());
        add(lines, "xp.provider.call", safe(() -> getContentResolver().call(
            Uri.parse("content://" + packageName + ".testprovider"),
            "probe",
            null,
            null
        )));
        add(lines, "xp.provider.type", safe(() -> getContentResolver().getType(
            Uri.parse("content://" + packageName + ".testprovider")
        )));

        ActivityManager am = getSystemService(ActivityManager.class);
        add(lines, "xp.activity.running", safe(() -> {
            ActivityManager.RunningAppProcessInfo self =
                new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(self);
            return "pid=" + self.pid + ",uid=" + self.uid
                + ",importance=" + self.importance
                + ",process=" + self.processName
                + ",reason=" + self.importanceReasonCode;
        }));
        add(lines, "xp.activity.processes", safe(() -> {
            List<ActivityManager.RunningAppProcessInfo> values =
                am.getRunningAppProcesses();
            if (values == null) return "null";
            long self = values.stream()
                .filter(value -> value.uid == uid || packageName.equals(value.processName))
                .count();
            return "count=" + values.size() + ",self=" + self;
        }));

        NotificationManager notifications = getSystemService(NotificationManager.class);
        add(lines, "xp.notifications.enabled", safe(notifications::areNotificationsEnabled));
        AccountManager accounts = getSystemService(AccountManager.class);
        add(lines, "xp.accounts.google", safe(
            () -> accounts.getAccountsByType("com.google").length
        ));

        UsageStatsManager usage = getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        add(lines, "xp.usageStats.query", safe(
            () -> usage.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 60_000L,
                now
            ) == null ? "null" : "ok"
        ));

        add(lines, "xp.telephony.imei", safe(() ->
            getSystemService(android.telephony.TelephonyManager.class)
                .getImei()
        ));
        add(lines, "xp.telephony.subscriber", safe(() ->
            getSystemService(android.telephony.TelephonyManager.class)
                .getSubscriberId()
        ));
    }

    private static String packageListSummary(
        List<PackageInfo> values,
        String packageName
    ) {
        long self = values.stream()
            .filter(value -> packageName.equals(value.packageName))
            .count();
        return "count=" + values.size() + ",self=" + self;
    }

    private String resolveActivity(Intent intent) {
        ResolveInfo value = getPackageManager().resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        );
        if (value == null || value.activityInfo == null) return "null";
        return value.activityInfo.packageName + "/" + value.activityInfo.name
            + ",exported=" + value.activityInfo.exported;
    }

    private String queryActivityCount(Intent intent) {
        List<ResolveInfo> values = getPackageManager().queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        );
        long self = values.stream()
            .filter(value -> value.activityInfo != null &&
                getPackageName().equals(value.activityInfo.packageName))
            .count();
        return "count=" + values.size() + ",self=" + self;
    }

    private String resolveService(Intent intent) {
        ResolveInfo value = getPackageManager().resolveService(intent, 0);
        if (value == null || value.serviceInfo == null) return "null";
        return value.serviceInfo.packageName + "/" + value.serviceInfo.name
            + ",exported=" + value.serviceInfo.exported;
    }

    private String queryReceiverCount(Intent intent) {
        List<ResolveInfo> values = getPackageManager().queryBroadcastReceivers(intent, 0);
        long self = values.stream()
            .filter(value -> value.activityInfo != null &&
                getPackageName().equals(value.activityInfo.packageName))
            .count();
        return "count=" + values.size() + ",self=" + self;
    }

    private String resolveProvider(String authority) {
        ProviderInfo value = getPackageManager().resolveContentProvider(authority, 0);
        if (value == null) return "null";
        return value.packageName + "/" + value.name
            + ",exported=" + value.exported;
    }

    private String permissionProbe(String permission) {
        return safe(() -> checkSelfPermission(permission)
            + ",packageManager=" + getPackageManager().checkPermission(
                permission,
                getPackageName()
            ));
    }

    private String checkAppOp(
        AppOpsManager manager,
        String operation,
        int uid,
        String packageName
    ) {
        return safe(() -> manager.checkOp(operation, uid, packageName)
            + ",noThrow=" + manager.checkOpNoThrow(operation, uid, packageName));
    }

    private String providerQuery() {
        try (Cursor cursor = getContentResolver().query(
            Uri.parse("content://" + getPackageName() + ".testprovider"),
            null,
            null,
            null,
            null
        )) {
            if (cursor == null) return "null";
            if (!cursor.moveToFirst()) return "empty";
            return cursor.getString(0);
        } catch (Throwable error) {
            return describe(error);
        }
    }

    private String signatureDigest(String packageName) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            );
            SigningInfo signing = info.signingInfo;
            Signature[] signatures = signing == null
                ? null
                : (Build.VERSION.SDK_INT >= 28
                    ? signing.getApkContentsSigners()
                    : signing.getSigningCertificateHistory());
            if (signatures == null || signatures.length == 0) return "none";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder result = new StringBuilder();
            for (Signature signature : signatures) {
                if (result.length() > 0) result.append(',');
                for (byte value : digest.digest(signature.toByteArray())) {
                    result.append(String.format(Locale.US, "%02x", value));
                }
            }
            return result.toString();
        } catch (Throwable error) {
            return describe(error);
        }
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

    private String readlinkSymlinkProbe() {
        File target = new File(getFilesDir(), "readlink-probe-target");
        File link = new File(
            getFilesDir(),
            "readlink-probe-link-" + System.nanoTime()
        );
        String stage = "start";
        try {
            stage = "delete";
            link.delete();
            stage = "symlink";
            Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
            stage = "readlink";
            return Os.readlink(link.getAbsolutePath());
        } catch (Throwable error) {
            return stage + ": " + describe(error);
        } finally {
            link.delete();
            target.delete();
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

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getName() + ": " + String.valueOf(cause.getMessage());
    }

    private interface Supplier {
        Object get() throws Throwable;
    }
}
