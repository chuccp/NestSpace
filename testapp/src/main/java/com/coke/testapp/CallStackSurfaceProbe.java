package com.coke.testapp;

import android.app.Activity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scans complete Java stacks for runtime and hook-framework signatures. */
final class CallStackSurfaceProbe {
    private static final String[] HOST_MARKERS = {
        "com.coke.nestspace",
        "libnestspace",
        "NestSpace"
    };

    private static final String[] HOOK_MARKERS = {
        "de.robv.android.xposed",
        "com.saurik.substrate",
        "com.frida",
        "re.frida",
        "org.lsposed",
        "io.github.lsposed",
        "com.bytedance.bytehook",
        "com.bytedance.shadowhook"
    };

    private CallStackSurfaceProbe() {}

    static void appendRows(Activity activity, List<String> rows) {
        StackTraceElement[] current = new Throwable().getStackTrace();
        Set<String> hostFrames = matching(current, HOST_MARKERS);
        add(
            rows,
            "通用完整 Java 栈宿主帧",
            "frames=" + current.length
                + " leaks=" + hostFrames.size()
                + (hostFrames.isEmpty()
                    ? ""
                    : " samples=" + hostFrames),
            hostFrames.isEmpty()
        );

        Set<String> currentHooks = matching(current, HOOK_MARKERS);
        Set<String> threadHooks = new LinkedHashSet<>();
        int threadCount = 0;
        for (Map.Entry<Thread, StackTraceElement[]> entry :
            Thread.getAllStackTraces().entrySet()) {
            ++threadCount;
            for (String item : matching(
                entry.getValue(),
                HOOK_MARKERS
            )) {
                threadHooks.add(entry.getKey().getName() + ":" + item);
            }
        }
        add(
            rows,
            "通用 Java 栈 Hook 框架",
            "current=" + (currentHooks.isEmpty()
                    ? "0"
                    : currentHooks)
                + " threads=" + threadCount
                + " allThreads=" + (threadHooks.isEmpty()
                    ? "0"
                    : threadHooks),
            currentHooks.isEmpty() && threadHooks.isEmpty()
        );
    }

    private static Set<String> matching(
        StackTraceElement[] stack,
        String[] markers
    ) {
        Set<String> result = new LinkedHashSet<>();
        for (StackTraceElement frame : stack) {
            String value = frame.getClassName() + "#"
                + frame.getMethodName();
            for (String marker : markers) {
                if (value.contains(marker)) {
                    result.add(value);
                    if (result.size() >= 6) return result;
                }
            }
        }
        return result;
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
