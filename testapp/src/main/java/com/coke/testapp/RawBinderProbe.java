package com.coke.testapp;

import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.Process;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Queries the package service through the raw Binder interface. This bypasses
 * ApplicationPackageManager and nested PackageManager wrappers, matching the
 * path used by native integrity SDKs.
 */
final class RawBinderProbe {
    private RawBinderProbe() {}

    static String report(String packageName) {
        List<String> lines = new ArrayList<>();
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager
                .getDeclaredMethod("getService", String.class)
                .invoke(null, "package");
            lines.add("binder.class=" + (binder == null
                ? "null"
                : binder.getClass().getName()));
            if (binder == null) return String.join("\n", lines);

            Class<?> stub = Class.forName(
                "android.content.pm.IPackageManager$Stub"
            );
            Object packageManager = stub
                .getDeclaredMethod("asInterface", IBinder.class)
                .invoke(null, binder);
            lines.add("service.class=" + packageManager.getClass().getName());
            lines.add("service.methods=" + packageMethodSummary(
                packageManager.getClass()
            ));

            append(
                lines,
                "packageInfo",
                invokePackageInfo(packageManager, packageName)
            );
            append(
                lines,
                "uidForPackage",
                invokeSingleString(packageManager, "getPackageUid", packageName)
            );
            append(
                lines,
                "packageForUid",
                invokeSingleInt(packageManager, "getPackagesForUid", Process.myUid())
            );
            append(
                lines,
                "nameForUid",
                invokeSingleInt(packageManager, "getNameForUid", Process.myUid())
            );
        } catch (Throwable error) {
            lines.add("error=" + describe(error));
        }
        return String.join("\n", lines);
    }

    private static String invokePackageInfo(Object service, String packageName)
        throws Exception {
        Method selected = null;
        for (Method method : service.getClass().getMethods()) {
            if (!"getPackageInfo".equals(method.getName()) ||
                method.getReturnType() != PackageInfo.class) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 0 || types[0] != String.class) continue;
            selected = method;
            break;
        }
        if (selected == null) return "missing method";
        Class<?>[] types = selected.getParameterTypes();
        Object[] args = new Object[types.length];
        int userId = Process.myUid() / 100000;
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            if (index == 0) {
                args[index] = packageName;
            } else if (type == long.class) {
                args[index] = 0L;
            } else if (type == int.class) {
                args[index] = index == types.length - 1 ? userId : 0;
            } else if (type == boolean.class) {
                args[index] = false;
            } else {
                return "unsupported signature=" + selected;
            }
        }
        PackageInfo info = (PackageInfo) selected.invoke(service, args);
        if (info == null || info.applicationInfo == null) {
            return "null signature=" + selected;
        }
        return "version=" + info.getLongVersionCode()
            + ",uid=" + info.applicationInfo.uid
            + ",source=" + info.applicationInfo.sourceDir
            + ",signature=" + selected;
    }

    private static String invokeSingleString(
        Object service,
        String name,
        String value
    ) throws Exception {
        for (Method method : service.getClass().getMethods()) {
            if (!name.equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 0 || types[0] != String.class) continue;
            Object[] args = arguments(types, value, 0);
            if (args == null) continue;
            return String.valueOf(method.invoke(service, args));
        }
        return "missing method";
    }

    private static String invokeSingleInt(
        Object service,
        String name,
        int value
    ) throws Exception {
        for (Method method : service.getClass().getMethods()) {
            if (!name.equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 0 || types[0] != int.class) continue;
            Object[] args = arguments(types, null, value);
            if (args == null) continue;
            Object result = method.invoke(service, args);
            return result instanceof Object[]
                ? Arrays.toString((Object[]) result)
                : String.valueOf(result);
        }
        return "missing method";
    }

    private static Object[] arguments(
        Class<?>[] types,
        String stringValue,
        int intValue
    ) {
        Object[] args = new Object[types.length];
        int userId = Process.myUid() / 100000;
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            if (index == 0 && type == String.class && stringValue != null) {
                args[index] = stringValue;
            } else if (index == 0 && type == int.class) {
                args[index] = intValue;
            } else if (type == int.class) {
                args[index] = index == types.length - 1 ? userId : 0;
            } else if (type == long.class) {
                args[index] = 0L;
            } else {
                return null;
            }
        }
        return args;
    }

    private static String packageMethodSummary(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Method method : type.getMethods()) {
            String name = method.getName();
            if (name.equals("getPackageInfo") ||
                name.equals("getPackageUid") ||
                name.equals("getPackagesForUid") ||
                name.equals("getNameForUid")) {
                names.add(method.toString());
            }
        }
        names.sort(String::compareTo);
        return String.join(";", names);
    }

    private static void append(List<String> lines, String name, String value) {
        lines.add(name + "=" + value);
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null &&
            (cause instanceof java.lang.reflect.InvocationTargetException ||
                cause instanceof java.lang.reflect.UndeclaredThrowableException)) {
            cause = cause.getCause();
        }
        return cause.getClass().getName() + ": " + cause.getMessage();
    }
}
