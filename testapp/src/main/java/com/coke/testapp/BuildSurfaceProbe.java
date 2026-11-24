package com.coke.testapp;

import android.os.Build;
import android.system.Os;
import android.system.StructUtsname;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compares Java Build fields with Android properties and kernel architecture. */
final class BuildSurfaceProbe {
    private static final Pattern PROPERTY = Pattern.compile(
        "^\\[([^]]+)]:\\s*\\[(.*)]$"
    );

    private BuildSurfaceProbe() {}

    static void appendRows(List<String> rows) {
        Map<String, String> properties = readSystemProperties();
        if (properties.isEmpty()) {
            add(rows, "通用 Build/getprop 属性视图", "unavailable", false);
            return;
        }

        String[] keys = {
            "ro.build.version.sdk",
            "ro.build.version.release",
            "ro.build.version.security_patch",
            "ro.build.fingerprint",
            "ro.product.brand",
            "ro.product.manufacturer",
            "ro.product.model",
            "ro.product.device",
            "ro.product.name",
            "ro.product.cpu.abi",
            "ro.product.cpu.abilist"
        };
        String[] values = {
            String.valueOf(Build.VERSION.SDK_INT),
            value(Build.VERSION.RELEASE),
            value(Build.VERSION.SECURITY_PATCH),
            value(Build.FINGERPRINT),
            value(Build.BRAND),
            value(Build.MANUFACTURER),
            value(Build.MODEL),
            value(Build.DEVICE),
            value(Build.PRODUCT),
            Build.SUPPORTED_ABIS.length == 0
                ? ""
                : Build.SUPPORTED_ABIS[0],
            String.join(",", Build.SUPPORTED_ABIS)
        };
        StringBuilder detail = new StringBuilder();
        boolean passed = true;
        for (int index = 0; index < keys.length; ++index) {
            String actual = properties.get(keys[index]);
            if (detail.length() != 0) detail.append(' ');
            detail.append(keys[index]).append('=')
                .append(values[index])
                .append('/')
                .append(actual);
            passed = passed &&
                values[index] != null &&
                values[index].equals(actual);
        }
        add(rows, "通用 Build/getprop 属性视图", detail.toString(), passed);

        try {
            StructUtsname uname = Os.uname();
            String osArch = System.getProperty("os.arch", "");
            String detailKernel = "machine=" + uname.machine
                + " os.arch=" + osArch
                + " abis=" + String.join(",", Build.SUPPORTED_ABIS);
            add(
                rows,
                "通用 Java/内核架构视图",
                detailKernel,
                ("aarch64".equals(uname.machine) ||
                    "arm64".equals(uname.machine)) &&
                    ("aarch64".equals(osArch) ||
                        "arm64".equals(osArch)) &&
                    Build.SUPPORTED_ABIS.length > 0 &&
                    Build.SUPPORTED_ABIS[0].startsWith("arm64")
            );
        } catch (Throwable error) {
            add(rows, "通用 Java/内核架构视图", describe(error), false);
        }
    }

    private static Map<String, String> readSystemProperties() {
        Map<String, String> result = new LinkedHashMap<>();
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getprop")
                .redirectErrorStream(true)
                .start();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    process.getInputStream(),
                    StandardCharsets.UTF_8
                )
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = PROPERTY.matcher(line);
                    if (matcher.matches()) {
                        result.put(matcher.group(1), matcher.group(2));
                    }
                }
            }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (Throwable ignored) {
            result.clear();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return result;
    }

    private static String value(String value) {
        return value == null ? "" : value;
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
