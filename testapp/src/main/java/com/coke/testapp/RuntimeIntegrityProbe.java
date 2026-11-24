package com.coke.testapp;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runtime checks used by Metasec/BDMS after the package checks have passed.
 * These probes intentionally exercise both the Java and kernel views so a
 * virtualization failure points at the inconsistent Android semantic.
 */
final class RuntimeIntegrityProbe {
    private static final Pattern UID_OUTPUT = Pattern.compile(
        "(?:^|\\|)uid=(\\d+)"
    );
    private static final Pattern STATUS_UID_OUTPUT = Pattern.compile(
        "Uid:\\s*\\|?\\s*(\\d+)"
    );
    private static final String[] HOOK_MODULES = {
        "frida",
        "gum-js-loop",
        "gadget",
        "xposed",
        "substrate",
        "sandhook",
        "shadowhook",
        "libnestspace",
        "libsandbox"
    };
    private static final String[] NAMESPACES = {
        "cgroup",
        "ipc",
        "mnt",
        "net",
        "pid",
        "time",
        "user",
        "uts"
    };

    private RuntimeIntegrityProbe() {}

    static void appendRows(
        List<String> rows,
        Activity activity,
        String nativeReport
    ) {
        int expectedUid = Process.myUid();
        int pid = Process.myPid();
        appendProcessViewRows(rows, expectedUid, pid, nativeReport);
        appendNamespaceRows(rows);
        appendDebugRows(rows);
        appendAuxiliaryExecRows(rows, nativeReport, expectedUid);
        appendAuxvRows(rows, nativeReport, expectedUid);
        appendBinderCallerRows(rows, activity, expectedUid, pid);
        appendApkRows(rows, activity, nativeReport);
        appendWebViewPackageRows(rows, activity);
    }

    private static void appendProcessViewRows(
        List<String> rows,
        int expectedUid,
        int pid,
        String nativeReport
    ) {
        String selfStatus = read("/proc/self/status");
        String pidStatus = read("/proc/" + pid + "/status");
        String selfIdentity = statusIdentity(selfStatus);
        String pidIdentity = statusIdentity(pidStatus);
        add(
            rows,
            "Metasec self/pid status 身份",
            "self=" + selfIdentity + " pid=" + pidIdentity,
            !selfIdentity.startsWith("error=") &&
                selfIdentity.equals(pidIdentity) &&
                selfIdentity.contains("uid=" + expectedUid) &&
                selfIdentity.contains("pid=" + pid)
        );

        String selfStat = processStatSummary("/proc/self/stat");
        String pidStat = processStatSummary("/proc/" + pid + "/stat");
        add(
            rows,
            "Metasec self/pid stat 身份",
            "self=" + selfStat + " pid=" + pidStat,
            !selfStat.startsWith("error=") &&
                selfStat.equals(pidStat) &&
                selfStat.startsWith("pid=" + pid + " ")
        );

        String selfComm = compact(read("/proc/self/comm"));
        String pidComm = compact(read("/proc/" + pid + "/comm"));
        add(
            rows,
            "Metasec self/pid comm",
            "self=" + selfComm + " pid=" + pidComm,
            !selfComm.startsWith("error=") &&
                selfComm.equals(pidComm)
        );

        String selfCmdline = compact(read("/proc/self/cmdline").replace('\0', '|'));
        String pidCmdline = compact(
            read("/proc/" + pid + "/cmdline").replace('\0', '|')
        );
        add(
            rows,
            "Metasec self/pid cmdline",
            "self=" + selfCmdline + " pid=" + pidCmdline,
            !selfCmdline.startsWith("error=") &&
                selfCmdline.equals(pidCmdline)
        );

        String selfAttr = compact(read("/proc/self/attr/current"));
        String pidAttr = compact(read("/proc/" + pid + "/attr/current"));
        add(
            rows,
            "Metasec self/pid SELinux",
            "self=" + selfAttr + " pid=" + pidAttr,
            !selfAttr.startsWith("error=") &&
                selfAttr.equals(pidAttr) &&
                containsUidCategory(selfAttr, expectedUid)
        );

        String selfExe = readLink("/proc/self/exe");
        String pidExe = readLink("/proc/" + pid + "/exe");
        add(
            rows,
            "Metasec self/pid exe",
            "self=" + selfExe + " pid=" + pidExe,
            appProcessExecutable(selfExe) &&
                selfExe.equals(pidExe)
        );

        String selfCwd = readLink("/proc/self/cwd");
        String pidCwd = readLink("/proc/" + pid + "/cwd");
        add(
            rows,
            "Metasec self/pid cwd",
            "self=" + selfCwd + " pid=" + pidCwd,
            "/".equals(selfCwd) && selfCwd.equals(pidCwd)
        );

        String selfRoot = readLink("/proc/self/root");
        String pidRoot = readLink("/proc/" + pid + "/root");
        add(
            rows,
            "Metasec self/pid root",
            "self=" + selfRoot + " pid=" + pidRoot,
            "/".equals(selfRoot) && selfRoot.equals(pidRoot)
        );

        String nspid = field(selfStatus, "NSpid:");
        String[] nspidParts = nspid.trim().split("\\s+");
        String visiblePid = nspidParts.length == 0
            ? ""
            : nspidParts[nspidParts.length - 1];
        add(
            rows,
            "Metasec NSpid",
            nspid,
            nspid.isEmpty() || String.valueOf(pid).equals(visiblePid)
        );

        String seccomp = field(selfStatus, "Seccomp:");
        String filters = field(selfStatus, "Seccomp_filters:");
        add(
            rows,
            "Metasec seccomp status",
            "mode=" + seccomp + " filters=" + filters,
            ("1".equals(seccomp) || "2".equals(seccomp)) &&
                parseNonNegative(filters) >= 0
        );

        String capabilities = "inh=" + field(selfStatus, "CapInh:")
            + " prm=" + field(selfStatus, "CapPrm:")
            + " eff=" + field(selfStatus, "CapEff:")
            + " bnd=" + field(selfStatus, "CapBnd:");
        add(
            rows,
            "Metasec capability 集合",
            capabilities,
            isZeroHex(field(selfStatus, "CapEff:"))
        );

        String selfSyscall = compact(read("/proc/self/syscall"));
        String pidSyscall = compact(read("/proc/" + pid + "/syscall"));
        add(
            rows,
            "Metasec syscall 伪文件",
            "self=" + selfSyscall + " pid=" + pidSyscall,
            !containsHostMarker(selfSyscall) &&
                !containsHostMarker(pidSyscall) &&
                !selfSyscall.startsWith("error=PermissionDenied")
        );

        String stat = compact(read("/proc/self/stat"));
        String parent = statParentPid(stat);
        String actualParent = field(nativeReport, "ppid=");
        add(
            rows,
            "Metasec ppid/stat 一致",
            "stat=" + parent + " getppid=" + actualParent,
            parent.equals(actualParent)
        );
    }

    private static void appendNamespaceRows(List<String> rows) {
        Set<String> failures = new LinkedHashSet<>();
        StringBuilder detail = new StringBuilder();
        for (String namespace : NAMESPACES) {
            String path = "/proc/self/ns/" + namespace;
            String value = readLink(path);
            if (detail.length() != 0) detail.append(' ');
            detail.append(namespace).append('=').append(value);
            boolean permittedNamespace =
                value.matches("error=ErrnoException:\\d+") &&
                    ("ipc".equals(namespace) ||
                        "pid".equals(namespace) ||
                        "user".equals(namespace));
            if (!(value.startsWith(namespace + ":[") &&
                value.endsWith("]") &&
                !containsHostMarker(value)) &&
                !permittedNamespace) {
                failures.add(path);
            }
        }
        add(
            rows,
            "Metasec namespace 链接",
            detail + (failures.isEmpty() ? "" : " bad=" + failures),
            failures.isEmpty()
        );
    }

    private static void appendDebugRows(List<String> rows) {
        boolean debugger = Debug.isDebuggerConnected() ||
            Debug.waitingForDebugger();
        String tracer = field(read("/proc/self/status"), "TracerPid:");
        String maps = read("/proc/self/maps");
        Set<String> hooks = new LinkedHashSet<>();
        if (!maps.startsWith("error=")) {
            for (String line : maps.split("\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                for (String marker : HOOK_MODULES) {
                    if (lower.contains(marker)) {
                        hooks.add(line);
                        break;
                    }
                }
                if (hooks.size() >= 4) break;
            }
        }
        add(
            rows,
            "Metasec 动态调试与 Hook",
            "debugger=" + debugger + " tracer=" + tracer
                + " maps=" + (hooks.isEmpty() ? "0" : hooks),
            !debugger && "0".equals(tracer) && hooks.isEmpty()
        );
    }

    private static void appendAuxiliaryExecRows(
        List<String> rows,
        String nativeReport,
        int expectedUid
    ) {
        String output = field(nativeReport, "helper.sh.output=");
        String uid = match(UID_OUTPUT, output);
        String statusUid = match(STATUS_UID_OUTPUT, output);
        String exe = outputValue(output, "exe=");
        add(
            rows,
            "Metasec sh execve UID",
            "uid=" + uid + " statusUid=" + statusUid
                + " exe=" + exe + " output=" + output,
            String.valueOf(expectedUid).equals(uid) &&
                String.valueOf(expectedUid).equals(statusUid) &&
                (exe.equals("/system/bin/sh") ||
                    exe.startsWith("/system/bin/toybox"))
        );

        String getpropExit = field(nativeReport, "helper.getprop.exit=");
        String getpropOutput = field(nativeReport, "helper.getprop.output=");
        add(
            rows,
            "Metasec getprop 辅助命令",
            "exit=" + getpropExit + " output=" + getpropOutput,
            "0".equals(getpropExit) &&
                getpropOutput.matches(".*\\d+.*") &&
                !containsHostMarker(getpropOutput)
        );

        String logcatExit = field(nativeReport, "helper.logcat.exit=");
        String logcatOutput = field(nativeReport, "helper.logcat.output=");
        add(
            rows,
            "Metasec logcat 辅助命令",
            "exit=" + logcatExit + " output=" + logcatOutput,
            "0".equals(logcatExit) &&
                !containsLogPathLeak(logcatOutput)
        );
    }

    private static void appendAuxvRows(
        List<String> rows,
        String nativeReport,
        int expectedUid
    ) {
        String execfn = field(nativeReport, "auxv.execfn=");
        String uid = field(nativeReport, "auxv.uid=");
        String euid = field(nativeReport, "auxv.euid=");
        String secure = field(nativeReport, "auxv.secure=");
        add(
            rows,
            "Metasec auxv 进程凭据",
            "execfn=" + execfn + " uid=" + uid + " euid=" + euid
                + " secure=" + secure,
            appProcessExecutable(execfn)
        );
    }

    private static void appendBinderCallerRows(
        List<String> rows,
        Activity activity,
        int expectedUid,
        int expectedPid
    ) {
        try {
            Bundle result = activity.getContentResolver().call(
                Uri.parse("content://com.coke.testapp.workerprovider"),
                "identity-probe",
                null,
                null
            );
            if (result == null) {
                add(rows, "Metasec Binder 对端调用者", "null", false);
                return;
            }
            int callerUid = result.getInt("callerUid", -1);
            int callerPid = result.getInt("callerPid", -1);
            int callerWorkSourceUid =
                result.getInt("callerWorkSourceUid", -1);
            int callerUser = result.getInt("callerUser", -1);
            String callerSid = result.getString("callerSid", "");
            int callerNetworkPermission =
                result.getInt("callerNetworkPermission", -1);
            String callerPackages =
                result.getString("callerPackages", "");
            int workerUid = result.getInt("workerUid", -1);
            int workerPid = result.getInt("workerPid", -1);
            String workerProcess = result.getString("workerProcess", "");
            boolean workerPackageApplicationInfo =
                result.getBoolean("workerPackageApplicationInfo", false);
            String workerPackageError =
                result.getString("workerPackageError", "");
            add(
                rows,
                "Metasec Binder 对端调用者",
                "callerUid=" + callerUid + " callerPid=" + callerPid
                    + " workSource=" + callerWorkSourceUid
                    + " user=" + callerUser
                    + " sid=" + callerSid
                    + " networkPermission=" + callerNetworkPermission
                    + " packages=" + callerPackages
                    + " workerUid=" + workerUid + " workerPid=" + workerPid
                    + " workerProcess=" + workerProcess
                    + " packageApplicationInfo="
                    + workerPackageApplicationInfo
                    + (workerPackageError.isEmpty()
                        ? ""
                        : " error=" + workerPackageError),
                callerUid == expectedUid &&
                    callerPid == expectedPid &&
                    (callerWorkSourceUid == -1 ||
                        callerWorkSourceUid == expectedUid) &&
                    callerUser == 0 &&
                    callerNetworkPermission ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    callerPackages.contains(activity.getPackageName()) &&
                    workerUid == expectedUid &&
                    workerPid > 0 &&
                    workerPackageApplicationInfo
            );
        } catch (Throwable error) {
            add(
                rows,
                "Metasec Binder 对端调用者",
                describe(error),
                false
            );
        }
    }

    private static void appendApkRows(
        List<String> rows,
        Activity activity,
        String nativeReport
    ) {
        try {
            PackageManager pm = activity.getPackageManager();
            PackageInfo info = pm.getPackageInfo(
                activity.getPackageName(),
                PackageManager.GET_SIGNING_CERTIFICATES
            );
            String source = info.applicationInfo.sourceDir;
            long now = System.currentTimeMillis();
            long day = 24L * 60L * 60L * 1000L;
            add(
                rows,
                "Metasec 安装时间线",
                "first=" + info.firstInstallTime
                    + " lastUpdate=" + info.lastUpdateTime
                    + " now=" + now,
                info.firstInstallTime > 0 &&
                    info.lastUpdateTime >= info.firstInstallTime &&
                    info.lastUpdateTime <= now + day
            );

            File apk = new File(source);
            try (ZipFile zip = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                boolean manifest = false;
                boolean dex = false;
                int count = 0;
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    ++count;
                    if ("AndroidManifest.xml".equals(entry.getName())) {
                        manifest = true;
                    }
                    if (entry.getName().startsWith("classes")
                        && entry.getName().endsWith(".dex")) {
                        dex = true;
                    }
                }
                add(
                    rows,
                    "Metasec APK zip/内容",
                    "path=" + source + " entries=" + count
                        + " manifest=" + manifest + " dex=" + dex
                        + " size=" + apk.length()
                        + " sha256=" + digest(apk),
                    apk.isFile() && apk.canRead() && apk.length() > 0 &&
                        manifest && dex
                );
            }

            String libraryPath = field(
                nativeReport,
                "native.libraryPath="
            );
            add(
                rows,
                "Metasec native library 身份",
                "dir=" + info.applicationInfo.nativeLibraryDir
                    + " loaded=" + libraryPath,
                libraryPath.contains("libtestapp_probe.so") &&
                    libraryPath.startsWith("/data/app/") &&
                    !containsHostMarker(
                        info.applicationInfo.nativeLibraryDir
                    ) &&
                    !containsHostMarker(libraryPath)
            );
            String fdPath = field(nativeReport, "native.fd.apkPath=");
            add(
                rows,
                "Metasec APK fd/readlink",
                fdPath,
                source.equals(fdPath) && !containsHostMarker(fdPath)
            );
        } catch (Throwable error) {
            add(rows, "Metasec APK 完整性", describe(error), false);
        }
    }

    private static void appendWebViewPackageRows(
        List<String> rows,
        Activity activity
    ) {
        try {
            PackageInfo current = WebView.getCurrentWebViewPackage();
            String packageName = current == null
                ? "com.google.android.webview"
                : current.packageName;
            add(
                rows,
                "Metasec current WebView PackageInfo",
                current == null
                    ? "null"
                    : "package=" + current.packageName
                        + " applicationInfo="
                        + (current.applicationInfo != null)
                        + " source=" + (
                            current.applicationInfo == null
                                ? ""
                                : current.applicationInfo.sourceDir
                        ),
                current != null && current.applicationInfo != null
            );
            PackageManager pm = activity.getPackageManager();
            int[] flags = {
                0,
                PackageManager.GET_SHARED_LIBRARY_FILES,
                PackageManager.GET_SHARED_LIBRARY_FILES |
                    PackageManager.GET_SIGNING_CERTIFICATES,
                PackageManager.GET_SHARED_LIBRARY_FILES |
                    PackageManager.MATCH_UNINSTALLED_PACKAGES |
                    PackageManager.GET_SIGNATURES |
                    PackageManager.GET_META_DATA
            };
            for (int flag : flags) {
                PackageInfo info = pm.getPackageInfo(packageName, flag);
                add(
                    rows,
                    "Metasec WebView provider flags=" + flag,
                    "package=" + info.packageName
                        + " applicationInfo=" + (info.applicationInfo != null)
                        + " uid=" + (
                            info.applicationInfo == null
                                ? -1
                                : info.applicationInfo.uid
                        )
                        + " source=" + (
                            info.applicationInfo == null
                                ? ""
                                : info.applicationInfo.sourceDir
                        ),
                    info.applicationInfo != null &&
                        packageName.equals(info.packageName) &&
                        info.applicationInfo.sourceDir != null
                );
            }
        } catch (Throwable error) {
            add(rows, "Metasec WebView provider", describe(error), false);
        }
    }

    private static String statusIdentity(String status) {
        if (status.startsWith("error=")) return status;
        String uid = field(status, "Uid:");
        return "pid=" + field(status, "Pid:")
            + " tgid=" + field(status, "Tgid:")
            + " ppid=" + field(status, "PPid:")
            + " uid=" + firstToken(uid)
            + " gid=" + firstToken(field(status, "Gid:"))
            + " nspid=" + field(status, "NSpid:")
            + " seccomp=" + field(status, "Seccomp:");
    }

    private static String processStatSummary(String path) {
        String value = compact(read(path));
        int close = value.lastIndexOf(')');
        if (value.startsWith("error=") || close < 0) return value;
        String[] fields = value.substring(close + 1).trim().split("\\s+");
        String prefix = value.substring(0, close + 1);
        return "pid=" + prefix.substring(0, prefix.indexOf(' '))
            + " comm=" + prefix.substring(prefix.indexOf('('))
            + " state=" + value(fields, 0)
            + " ppid=" + value(fields, 1)
            + " pgrp=" + value(fields, 2)
            + " session=" + value(fields, 3);
    }

    private static String statParentPid(String stat) {
        int close = stat.lastIndexOf(')');
        if (close < 0) return "";
        String[] fields = stat.substring(close + 1).trim().split("\\s+");
        return value(fields, 1);
    }

    private static String value(String[] values, int index) {
        return index >= 0 && index < values.length ? values[index] : "";
    }

    private static String firstToken(String value) {
        String trimmed = value.trim();
        int separator = trimmed.indexOf('\t');
        if (separator < 0) separator = trimmed.indexOf(' ');
        return separator < 0 ? trimmed : trimmed.substring(0, separator);
    }

    private static int parseNonNegative(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isZeroHex(String value) {
        try {
            return Long.parseUnsignedLong(value, 16) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean appProcessExecutable(String value) {
        return value.startsWith("/system/bin/app_process");
    }

    private static boolean containsUidCategory(String selinux, int uid) {
        int appId = uid >= 10_000 ? uid - 10_000 : uid;
        int low = appId & 0xff;
        int high = 256 + ((appId >> 8) & 0xff);
        return selinux.contains(":c" + low + ",") &&
            selinux.contains(",c" + high + ",") &&
            selinux.contains(",c512,c768");
    }

    private static boolean containsHostMarker(String value) {
        return value.contains("com.coke.nestspace") ||
            value.contains("/.nestspace/") ||
            value.contains("/vspace/") ||
            value.contains("libnestspace") ||
            value.contains("libsandbox") ||
            value.contains("libsandhook") ||
            value.contains("shadowhook") ||
            value.contains("NestSpace");
    }

    private static boolean containsLogPathLeak(String value) {
        return value.contains("com.coke.nestspace") ||
            value.contains("/.nestspace/") ||
            value.contains("/vspace/") ||
            value.contains("native-cache") ||
            value.contains("libnestspace") ||
            value.contains("libsandbox") ||
            value.contains("libsandhook") ||
            value.contains("shadowhook");
    }

    private static String outputValue(String output, String prefix) {
        for (String item : output.split("\\|")) {
            if (item.startsWith(prefix)) return item.substring(prefix.length());
        }
        return "";
    }

    private static String match(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String digest(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            StringBuilder value = new StringBuilder();
            for (byte item : digest.digest()) {
                value.append(String.format(Locale.US, "%02x", item));
            }
            return value.toString();
        } catch (Throwable error) {
            return "error=" + error.getClass().getSimpleName();
        }
    }

    private static String readLink(String path) {
        try {
            return Os.readlink(path);
        } catch (ErrnoException error) {
            return "error=" + error.getClass().getSimpleName()
                + ":" + error.errno;
        } catch (Throwable error) {
            return "error=" + error.getClass().getSimpleName();
        }
    }

    private static String read(String path) {
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                value.append(line).append('\n');
            }
        } catch (Throwable error) {
            return "error=" + error.getClass().getSimpleName();
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

    private static String compact(String value) {
        return value.replace('\n', '|').trim();
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
