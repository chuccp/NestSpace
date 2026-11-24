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
import java.nio.charset.StandardCharsets;
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
            add(
                rows,
                "ApplicationInfo sourceDir",
                info.applicationInfo.sourceDir,
                apk.getAbsolutePath().equals(info.applicationInfo.sourceDir) &&
                    apk.getAbsolutePath().equals(
                        activity.getApplicationInfo().sourceDir
                    )
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
            long identity = Binder.clearCallingIdentity();
            int packageUid;
            try {
                packageUid = pm.getPackageUid(packageName, 0);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            add(
                rows,
                "Binder clear/restore transaction",
                "packageUid=" + packageUid +
                    " restoredUid=" + Binder.getCallingUid(),
                packageUid == expectedUid &&
                    Binder.getCallingUid() == expectedUid
            );
        } catch (Throwable error) {
            add(
                rows,
                "Binder clear/restore transaction",
                describe(error),
                false
            );
        }
        try {
            java.lang.reflect.Method getUidForPid =
                Process.class.getDeclaredMethod("getUidForPid", int.class);
            getUidForPid.setAccessible(true);
            int processUid = (Integer) getUidForPid.invoke(null, Process.myPid());
            add(
                rows,
                "Process.getUidForPid",
                String.valueOf(processUid),
                processUid == expectedUid
            );
        } catch (Throwable error) {
            add(rows, "Process.getUidForPid", describe(error), false);
        }
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
        boolean rawUidMatches = rawUid.equals(String.valueOf(expectedUid));
        boolean rawUidUnavailableButPackageOwned =
            rawUid.equals("missing method") &&
                field(raw, "packageForUid=")
                    .contains("[" + packageName + "]") &&
                field(raw, "nameForUid=").equals(packageName);
        add(
            rows,
            "raw IPackageManager UID",
            rawUid + (
                rawUidUnavailableButPackageOwned
                    ? " (UID method hidden; package ownership verified)"
                    : ""
            ),
            rawUidMatches || rawUidUnavailableButPackageOwned
        );

        String nativeReport;
        try {
            NativeProbe.ensureLoaded();
            nativeReport = NativeProbe.nativeHookProbe(
                activity.getDataDir().getAbsolutePath(),
                activity.getApplicationInfo().sourceDir,
                ""
            ) + "\n" + NativeProbe.nativeAuxiliaryProbe();
        } catch (Throwable error) {
            nativeReport = "error=" + describe(error);
        }
        checkNative(rows, nativeReport, "libc.getuid", expectedUid);
        checkNative(rows, nativeReport, "syscall.getuid", expectedUid);
        checkNative(rows, nativeReport, "raw.syscall.getuid", expectedUid);
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
        checkNativeCount(
            rows,
            nativeReport,
            "宿主 fd 目标",
            "native.fd.hostLeaks",
            "native.fd.hostSamples"
        );
        checkNativeCount(
            rows,
            nativeReport,
            "线程名宿主泄漏",
            "native.task.hostNameLeaks",
            "native.task.hostNameSamples"
        );
        checkNativeCount(
            rows,
            nativeReport,
            "线程 UID 不一致",
            "native.task.uidMismatches",
            "native.task.uidSamples"
        );
        checkNativeCount(
            rows,
            nativeReport,
            "已加载 ELF 宿主路径",
            "native.elf.hostModules",
            "native.elf.hostSamples"
        );
        checkNativeCount(
            rows,
            nativeReport,
            "raw maps 宿主泄漏",
            "native.rawMaps.hostLeaks",
            "native.rawMaps.hostSamples"
        );
        checkNativeCount(
            rows,
            nativeReport,
            "raw mountinfo 宿主泄漏",
            "native.rawMountinfo.hostLeaks",
            "native.rawMountinfo.hostSamples"
        );
        String targetElfModules = field(
            nativeReport,
            "native.elf.targetModules="
        );
        String targetBuildIds = field(
            nativeReport,
            "native.elf.buildIds="
        );
        add(
            rows,
            "native target ELF build-id",
            "modules=" + targetElfModules + " buildIds=" + targetBuildIds
                + " " + field(nativeReport, "native.elf.buildIdSamples="),
            Integer.parseInt(targetElfModules.isEmpty()
                    ? "-1"
                    : targetElfModules) > 0 &&
                Integer.parseInt(targetBuildIds.isEmpty()
                    ? "-1"
                    : targetBuildIds) > 0
        );
        checkNativeCount(
            rows,
            nativeReport,
            "native ELF missing build-id",
            "native.elf.missingBuildIds",
            "native.elf.buildIdSamples"
        );
        checkNativeCount(
            rows,
            nativeReport,
            "native ELF writable RELRO",
            "native.elf.relroWritable",
            "native.elf.relroSamples"
        );
        checkNativeCount(
            rows,
            nativeReport,
            "环境变量宿主泄漏",
            "native.environment.hostLeaks",
            "native.environment.hostSamples"
        );
        checkNative(rows, nativeReport, "native.rawMaps.read", "ok");
        checkNative(rows, nativeReport, "native.rawMountinfo.read", "ok");
        checkNative(rows, nativeReport, "child-proc.uid", expectedUid);
        checkNative(rows, nativeReport, "child-proc.raw_uid", expectedUid);
        checkNative(rows, nativeReport, "child-proc.parent_uid", expectedUid);
        checkNative(rows, nativeReport, "raw.syscall.faccessat.files", "ok");
        try {
            long totalRam = Long.parseLong(
                nativeValue(nativeReport, "sysinfo=", "totalram")
            );
            long freeRam = Long.parseLong(
                nativeValue(nativeReport, "sysinfo=", "freeram")
            );
            long processes = Long.parseLong(
                nativeValue(nativeReport, "sysinfo=", "procs")
            );
            long uptime = Long.parseLong(
                nativeValue(nativeReport, "sysinfo=", "uptime")
            );
            add(
                rows,
                "native sysinfo 系统视图",
                "total=" + totalRam
                    + " free=" + freeRam
                    + " procs=" + processes
                    + " uptime=" + uptime,
                totalRam > 0 &&
                    freeRam >= 0 &&
                    freeRam <= totalRam &&
                    processes > 0 &&
                    uptime > 0
            );
        } catch (Throwable error) {
            add(
                rows,
                "native sysinfo 系统视图",
                field(nativeReport, "sysinfo="),
                false
            );
        }
        String signalScan = field(nativeReport, "signal.scan=");
        add(
            rows,
            "native 信号处理链归属",
            signalScan,
            signalScan.contains("hostLeaks=0") &&
                !signalScan.contains("read-error=")
        );
        String popenIdentity = field(nativeReport, "popen.identity=");
        add(
            rows,
            "native popen 子命令身份",
            popenIdentity,
            popenIdentity.contains("status=0 ") &&
                popenIdentity.contains(
                    "output=" + expectedUid
                )
        );
        String socketLoopback = field(
            nativeReport,
            "socket.loopback="
        );
        add(
            rows,
            "native 本地 socket 语义",
            socketLoopback,
            socketLoopback.startsWith("ok port=")
        );
        String fdResource = field(nativeReport, "resource.fd=");
        add(
            rows,
            "native 文件描述符资源",
            fdResource,
            fdResource.contains("opened=32 ") &&
                fdResource.contains("openError=0") &&
                !fdResource.contains("limit-error=")
        );
        String mappingScan = field(nativeReport, "mapping.scan=");
        add(
            rows,
            "native 匿名映射/mprotect",
            mappingScan,
            mappingScan.contains("rwx=0 ") &&
                mappingScan.contains("map=1 ") &&
                mappingScan.contains("mprotect=1 ") &&
                mappingScan.contains("munmap=1 ")
        );
        String rawProcStatus = field(
            nativeReport,
            "raw.syscall.newfstatat.procStatus="
        );
        add(
            rows,
            "raw fstatat /proc/self/status UID",
            rawProcStatus,
            rawProcStatus.contains("uid=" + expectedUid + " ")
        );
        String rawStatxFiles = field(
            nativeReport,
            "raw.syscall.statx.files="
        );
        add(
            rows,
            "raw statx target files",
            rawStatxFiles,
            rawStatxFiles.contains("uid=" + expectedUid + " ")
        );
        add(
            rows,
            "Android app UID range",
            String.valueOf(expectedUid),
            expectedUid >= 10_000 && expectedUid < 20_000
        );
        String selinux = read("/proc/self/attr/current").replace("\0", "").trim();
        int appId = expectedUid >= 10_000 ? expectedUid - 10_000 : expectedUid;
        int lowCategory = appId & 0xff;
        int highCategory = 256 + ((appId >> 8) & 0xff);
        add(
            rows,
            "SELinux UID category",
            selinux + " expected=c" + lowCategory + ",c"
                + highCategory + ",c512,c768",
            selinux.contains(":c" + lowCategory + ",")
                && selinux.contains(",c" + highCategory + ",")
                && selinux.contains(",c512,c768")
        );
        String groups = field(nativeReport, "getgroups=");
        add(
            rows,
            "Android supplementary groups",
            groups,
            validSupplementaryGroups(groups)
        );
        String dataXattr = readXattr(activity.getDataDir().getAbsolutePath());
        add(
            rows,
            "dataDir SELinux xattr",
            dataXattr,
            dataXattr.contains(":c" + lowCategory + ",")
                && dataXattr.contains(",c" + highCategory + ",")
                && dataXattr.contains(",c512,c768")
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
        StackTraceElement[] javaStack = new Throwable().getStackTrace();
        add(
            rows,
            "BDMS 调用栈窗口",
            stackSummary(javaStack) + " window=4",
            !stackWindowHostLeak(javaStack)
        );
        PlatformEnvironmentProbe.appendRows(rows, activity);
        AntiTamperRuleProbe.appendRows(rows, activity, nativeReport);
        RuntimeIntegrityProbe.appendRows(rows, activity, nativeReport);
        AdvancedTamperProbe.appendRows(activity, nativeReport, rows);
        SystemSurfaceProbe.appendRows(activity, rows);
        KernelSurfaceProbe.appendRows(activity, rows);
        ClassLoaderSurfaceProbe.appendRows(activity, rows);
        FrameworkSurfaceProbe.appendRows(activity, rows);
        StorageSurfaceProbe.appendRows(activity, rows);
        BuildSurfaceProbe.appendRows(rows);
        MultiProcessSurfaceProbe.appendRows(activity, rows);
        ProcSurfaceProbe.appendRows(activity, rows);
        CallStackSurfaceProbe.appendRows(activity, rows);

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

    private static void checkNativeCount(
        List<String> rows,
        String report,
        String label,
        String countKey,
        String samplesKey
    ) {
        String countValue = field(report, countKey + "=");
        String samples = field(report, samplesKey + "=");
        int count;
        try {
            count = Integer.parseInt(countValue);
        } catch (Throwable ignored) {
            count = -1;
        }
        add(
            rows,
            label,
            countValue + (samples.isEmpty() ? "" : " " + samples),
            count == 0
        );
    }

    private static boolean hostMarker(String value) {
        return value.contains("com.coke.nestspace") ||
            value.contains("libnestspace") ||
            value.contains("NestSpace");
    }

    private static boolean validSupplementaryGroups(String value) {
        if (value.isEmpty()) return false;
        for (String item : value.trim().split("\\s+")) {
            try {
                int group = Integer.parseInt(item);
                if (group < 0 || group > 99_999) return false;
            } catch (NumberFormatException error) {
                return false;
            }
        }
        return true;
    }

    private static String nativeValue(
        String report,
        String prefix,
        String key
    ) {
        String value = field(report, prefix);
        for (String item : value.split("\\s+")) {
            if (item.startsWith(key + "=")) {
                return item.substring(key.length() + 1);
            }
        }
        throw new IllegalArgumentException("missing " + key);
    }

    private static String stackSummary(StackTraceElement[] stack) {
        StringBuilder value = new StringBuilder();
        int limit = Math.min(stack.length, 8);
        for (int index = 0; index < limit; index++) {
            StackTraceElement element = stack[index];
            if (value.length() != 0) value.append(" <- ");
            value.append(element.getClassName())
                .append('#')
                .append(element.getMethodName());
        }
        return value.toString();
    }

    private static boolean stackWindowHostLeak(StackTraceElement[] stack) {
        int limit = Math.min(stack.length, 4);
        for (int index = 0; index < limit; index++) {
            if (hostMarker(stack[index].getClassName())) return true;
        }
        return false;
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

    private static String readXattr(String path) {
        try {
            return new String(
                Os.getxattr(path, "security.selinux"),
                StandardCharsets.UTF_8
            ).replace("\0", "");
        } catch (Throwable error) {
            return "error=" + describe(error);
        }
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
