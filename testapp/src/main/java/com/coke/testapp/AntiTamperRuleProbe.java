package com.coke.testapp;

import android.app.Activity;
import android.os.Process;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Expanded checks reconstructed from the observable NPTH and Metasec
 * anti-tamper inputs. It deliberately reports implementation-level facts so
 * failures identify the violating Android rule rather than a vendor name.
 */
final class AntiTamperRuleProbe {
    private static final String[] SANDBOX_MODULES = {
        "libsandbox_64.so",
        "libsandbox_32.so",
        "libsandhook-native.so",
        "libsandhook.so",
        "libartshadowhook.so"
    };

    private static final String[] VIRTUAL_PATH_RULES = {
        "/virtual/data/user",
        "/virtual/data/data",
        "/.nestspace/",
        "/vspace/",
        "/sandbox/",
        "/container/",
        "com.coke.nestspace",
        "/nestspace"
    };

    private AntiTamperRuleProbe() {}

    static void appendRows(
        List<String> rows,
        Activity activity,
        String nativeReport
    ) {
        int uid = Process.myUid();
        int pid = Process.myPid();
        String maps = read("/proc/self/maps");
        String processMaps = read("/proc/" + pid + "/maps");
        String smaps = read("/proc/self/smaps");
        String mountinfo = read("/proc/self/mountinfo");
        String status = read("/proc/self/status");
        String cgroup = compact(read("/proc/self/cgroup"));
        String cpuset = compact(read("/proc/self/cpuset"));
        String env = environment();

        add(
            rows,
            "NPTH self/process maps 一致",
            "self=" + digestShape(maps)
                + " process=" + digestShape(processMaps),
            maps.equals(processMaps) && !maps.startsWith("error=")
        );
        Set<String> sandboxModules = matchingTerms(
            maps + "\n" + smaps,
            SANDBOX_MODULES
        );
        add(
            rows,
            "NPTH 沙箱模块名称",
            sandboxModules.isEmpty()
                ? "0"
                : String.join("; ", sandboxModules),
            sandboxModules.isEmpty()
        );
        Set<String> virtualPaths = matchingTerms(
            maps + "\n" + smaps + "\n" + mountinfo + "\n" + env,
            VIRTUAL_PATH_RULES
        );
        add(
            rows,
            "NPTH 虚拟路径规则",
            virtualPaths.isEmpty()
                ? "0"
                : String.join("; ", virtualPaths),
            virtualPaths.isEmpty()
        );
        Set<String> smapsLeaks = matchingTerms(
            smaps,
            new String[]{
                "com.coke.nestspace",
                "libnestspace",
                "/.nestspace/",
                "/vspace/"
            }
        );
        add(
            rows,
            "NPTH smaps 运行时泄漏",
            smapsLeaks.isEmpty() ? "0" : String.join("; ", smapsLeaks),
            !smaps.startsWith("error=") && smapsLeaks.isEmpty()
        );

        String threadsValue = field(status, "Threads:");
        int threads = parseInt(threadsValue);
        int taskCount = taskIds().size();
        add(
            rows,
            "NPTH 线程目录/status 一致",
            "tasks=" + taskCount + " status=" + threads,
            threads > 0 && taskCount == threads
        );
        Set<String> invalidThreadNames = invalidTaskNames();
        add(
            rows,
            "NPTH task/comm 运行时线程",
            invalidThreadNames.isEmpty()
                ? "0"
                : String.join("; ", invalidThreadNames),
            invalidThreadNames.isEmpty()
        );

        add(
            rows,
            "NPTH cgroup UID",
            cgroup,
            cgroup.contains("/uid_" + uid + "/")
                && cgroup.contains("/pid_" + pid)
        );
        add(
            rows,
            "NPTH cpuset",
            cpuset,
            cpuset.startsWith("/") && !cpuset.startsWith("error=")
        );
        add(
            rows,
            "NPTH process status UID/GID",
            field(status, "Uid:") + " | " + field(status, "Gid:"),
            field(status, "Uid:").equals(
                uid + "\t" + uid + "\t" + uid + "\t" + uid
            ) && field(status, "Gid:").equals(
                uid + "\t" + uid + "\t" + uid + "\t" + uid
            )
        );
        Set<Integer> groups = groups(field(status, "Groups:"));
        add(
            rows,
            "NPTH 派生 supplementary groups",
            groups.toString(),
            groups.contains(9_997)
                && groups.contains(uid + 10_000)
                && groups.contains(uid + 40_000)
        );

        Set<String> fdLeaks = fdLeaks();
        add(
            rows,
            "NPTH fd 危险目标",
            fdLeaks.isEmpty() ? "0" : String.join("; ", fdLeaks),
            fdLeaks.isEmpty()
        );
        add(
            rows,
            "NPTH oom_score_adj 可读",
            compact(read("/proc/self/oom_score_adj")),
            true
        );

        String nativeHostModules = field(
            nativeReport,
            "native.elf.hostModules="
        );
        String nativeTaskLeaks = field(
            nativeReport,
            "native.task.hostNameLeaks="
        );
        String nativeFdLeaks = field(
            nativeReport,
            "native.fd.hostLeaks="
        );
        String childUid = field(nativeReport, "child-proc.uid=");
        String childRawUid = field(nativeReport, "child-proc.raw_uid=");
        add(
            rows,
            "NPTH 原生扫描汇总",
            "modules=" + nativeHostModules
                + " taskNames=" + nativeTaskLeaks
                + " fds=" + nativeFdLeaks
                + " childUid=" + childUid
                + " childRawUid=" + childRawUid,
            "0".equals(nativeHostModules)
                && "0".equals(nativeTaskLeaks)
                && "0".equals(nativeFdLeaks)
                && String.valueOf(uid).equals(childUid)
                && String.valueOf(uid).equals(childRawUid)
        );
    }

    private static Set<String> invalidTaskNames() {
        Set<String> result = new LinkedHashSet<>();
        for (Integer tid : taskIds()) {
            String name = read(
                "/proc/self/task/" + tid + "/comm"
            ).trim();
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("nestspace") ||
                lower.contains("nsseccompnotify")) {
                result.add(tid + ":" + name);
            }
        }
        return result;
    }

    private static List<Integer> taskIds() {
        List<Integer> result = new ArrayList<>();
        String[] names = new File("/proc/self/task").list();
        if (names == null) return result;
        for (String name : names) {
            try {
                result.add(Integer.parseInt(name));
            } catch (NumberFormatException ignored) {
                // Ignore "." and "..".
            }
        }
        result.sort(Integer::compareTo);
        return result;
    }

    private static Set<String> fdLeaks() {
        Set<String> result = new LinkedHashSet<>();
        File[] files = new File("/proc/self/fd").listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (!file.getName().matches("[0-9]+")) continue;
            try {
                String target = Os.readlink(file.getAbsolutePath());
                if (containsAny(target, VIRTUAL_PATH_RULES)) {
                    result.add(file.getName() + "->" + target);
                }
            } catch (Throwable ignored) {
                // Descriptor may close while scanning.
            }
        }
        return result;
    }

    private static Set<Integer> groups(String value) {
        Set<Integer> result = new LinkedHashSet<>();
        for (String item : value.trim().split("\\s+")) {
            try {
                result.add(Integer.parseInt(item));
            } catch (NumberFormatException ignored) {
                // Ignore malformed entries.
            }
        }
        return result;
    }

    private static Set<String> matchingTerms(
        String content,
        String[] terms
    ) {
        Set<String> result = new LinkedHashSet<>();
        if (content == null || content.startsWith("error=")) return result;
        for (String line : content.split("\n")) {
            if (!line.isEmpty() && containsAny(line, terms)) {
                result.add(line);
                if (result.size() >= 6) break;
            }
        }
        return result;
    }

    private static boolean containsAny(String value, String[] terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }

    private static String digestShape(String value) {
        if (value == null || value.startsWith("error=")) return "unavailable";
        int lines = value.isEmpty() ? 0 : value.split("\n").length;
        return "lines=" + lines + " hash=" + Integer.toHexString(value.hashCode());
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String environment() {
        return String.join("\n", System.getenv().values());
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
            return "error=" + error.getClass().getSimpleName()
                + ": " + error.getMessage();
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

    private static void add(
        List<String> rows,
        String label,
        String value,
        boolean passed
    ) {
        rows.add(
            label + ": " + value.replace('\n', ' ') +
                (passed ? " PASS" : " FAIL")
        );
    }
}
