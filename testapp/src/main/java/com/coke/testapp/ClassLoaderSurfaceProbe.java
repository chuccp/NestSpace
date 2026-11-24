package com.coke.testapp;

import android.app.Activity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Checks the ClassLoader and dex path surface visible to target code. */
final class ClassLoaderSurfaceProbe {
    private static final String[] HOST_MARKERS = {
        "com.coke.nestspace",
        "/.nestspace/",
        "/vspace/",
        "native-cache",
        "libnestspace"
    };

    private ClassLoaderSurfaceProbe() {}

    static void appendRows(Activity activity, List<String> rows) {
        ClassLoader loader = activity.getClassLoader();
        ClassLoader applicationLoader = activity.getApplication()
            .getClass()
            .getClassLoader();
        ClassLoader contextLoader = Thread.currentThread()
            .getContextClassLoader();

        add(
            rows,
            "通用 Application/Activity ClassLoader 一致",
            "activity=" + describe(loader)
                + " application=" + describe(applicationLoader)
                + " context=" + describe(contextLoader),
            loader != null &&
                loader == contextLoader &&
                (activity.getApplication().getClass() ==
                    android.app.Application.class ||
                    loader == applicationLoader) &&
                !containsHostMarker(describe(loader))
        );

        String className = loader == null ? "" : loader.getClass().getName();
        add(
            rows,
            "通用 ClassLoader 类型",
            className,
            loader != null &&
                dalvik.system.PathClassLoader.class.isAssignableFrom(
                    loader.getClass()
                )
        );

        List<String> hierarchy = new ArrayList<>();
        ClassLoader current = loader;
        for (int depth = 0; current != null && depth < 12; ++depth) {
            hierarchy.add(describe(current));
            current = current.getParent();
        }
        add(
            rows,
            "通用 ClassLoader 父链",
            String.join(" -> ", hierarchy),
            !hierarchy.isEmpty() &&
                !containsHostMarker(String.join(" -> ", hierarchy))
        );

        appendDexElements(loader, rows);
    }

    private static void appendDexElements(
        ClassLoader loader,
        List<String> rows
    ) {
        if (loader == null) {
            add(rows, "通用 ClassLoader dexElements", "null", false);
            return;
        }
        try {
            Object pathList = fieldValue(loader, "pathList");
            if (pathList == null) {
                add(rows, "通用 ClassLoader dexElements", "pathList=null", false);
                return;
            }
            Object elements = fieldValue(pathList, "dexElements");
            if (!(elements instanceof Object[])) {
                add(rows, "通用 ClassLoader dexElements", "elements missing", false);
                return;
            }
            List<String> paths = new ArrayList<>();
            for (Object element : (Object[]) elements) {
                Object path = fieldValue(element, "path");
                Object dexFile = fieldValue(element, "dexFile");
                paths.add(String.valueOf(path) + " dexFile=" + String.valueOf(dexFile));
            }
            String detail = String.join(" | ", paths);
            add(
                rows,
                "通用 ClassLoader dexElements",
                "count=" + paths.size() + " " + detail,
                !paths.isEmpty() && !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用 ClassLoader dexElements", describe(error), false);
        }
    }

    private static Object fieldValue(Object receiver, String name) {
        Class<?> type = receiver.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String describe(ClassLoader loader) {
        return loader == null
            ? "null"
            : loader.getClass().getName() + "@" +
                Integer.toHexString(System.identityHashCode(loader));
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
