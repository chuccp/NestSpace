package com.coke.testapp;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

import java.io.File;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;

/** Probes the platform surfaces commonly inspected by native risk engines. */
final class SystemSurfaceProbe {
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

    private SystemSurfaceProbe() {}

    static void appendRows(Activity activity, List<String> rows) {
        appendPackageMetadata(activity, rows);
        appendSettingsProvider(activity, rows);
        appendPreferences(activity, rows);
        appendActivityMetadata(activity, rows);
        appendEgl(rows);
        appendConnectivity(activity, rows);
        appendKeystore(rows);
        appendRunningProcess(activity, rows);
    }

    private static void appendPackageMetadata(
        Activity activity,
        List<String> rows
    ) {
        try {
            PackageManager pm = activity.getPackageManager();
            String packageName = activity.getPackageName();
            PackageInfo info = pm.getPackageInfo(
                packageName,
                PackageManager.GET_META_DATA |
                    PackageManager.GET_SIGNING_CERTIFICATES
            );
            ApplicationInfo app = info.applicationInfo;
            String version = info.versionName + "/" + info.getLongVersionCode();
            String metadata = app == null || app.metaData == null
                ? "null"
                : String.valueOf(
                    app.metaData.get("REQUIRE_SECURE_ENV")
                );
            add(
                rows,
                "通用 PackageManager 元数据",
                "version=" + version + " uid=" + (app == null ? -1 : app.uid)
                    + " source=" + (app == null ? "" : app.sourceDir)
                    + " metadata=" + metadata,
                app != null &&
                    info.versionName != null &&
                    info.getLongVersionCode() > 0 &&
                    app.uid == Process.myUid() &&
                    !containsHostMarker(app.sourceDir)
            );
        } catch (Throwable error) {
            add(rows, "通用 PackageManager 元数据", describe(error), false);
        }
    }

    private static void appendSettingsProvider(
        Activity activity,
        List<String> rows
    ) {
        try {
            String androidId = Settings.Secure.getString(
                activity.getContentResolver(),
                Settings.Secure.ANDROID_ID
            );
            String brightness = Settings.System.getString(
                activity.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE
            );
            String development = Settings.Global.getString(
                activity.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
            );
            String detail = "androidId=" + androidId
                + " brightnessMode=" + brightness
                + " development=" + development;
            add(
                rows,
                "通用 Settings Provider",
                detail,
                androidId != null && androidId.length() > 0 &&
                    !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用 Settings Provider", describe(error), false);
        }
    }

    private static void appendPreferences(
        Activity activity,
        List<String> rows
    ) {
        try {
            String key = "surface_probe";
            String expected = "value-" + Process.myPid();
            SharedPreferences preferences = activity.getSharedPreferences(
                "integrity_probe",
                Activity.MODE_PRIVATE
            );
            boolean wrote = preferences.edit().putString(key, expected).commit();
            String actual = preferences.getString(key, null);
            File file = new File(
                activity.getDataDir(),
                "shared_prefs/integrity_probe.xml"
            );
            String path = file.getAbsolutePath();
            add(
                rows,
                "通用 SharedPreferences 文件",
                "wrote=" + wrote + " read=" + expected.equals(actual)
                    + " path=" + path,
                wrote && expected.equals(actual) && file.isFile() &&
                    path.startsWith(activity.getDataDir().getAbsolutePath() + "/") &&
                    !containsHostMarker(path)
            );
        } catch (Throwable error) {
            add(rows, "通用 SharedPreferences 文件", describe(error), false);
        }
    }

    private static void appendActivityMetadata(
        Activity activity,
        List<String> rows
    ) {
        try {
            String component = activity.getComponentName().getClassName();
            String actual = activity.getClass().getName();
            Method[] methods = activity.getClass().getDeclaredMethods();
            StringBuilder names = new StringBuilder();
            for (int index = 0; index < methods.length && index < 8; ++index) {
                if (index != 0) names.append(',');
                names.append(methods[index].getName());
            }
            add(
                rows,
                "通用 Activity 组件与 dex 元数据",
                "component=" + component + " actual=" + actual
                    + " methods=" + methods.length + " names=" + names,
                component.equals(actual) && methods.length > 0 &&
                    !containsHostMarker(actual)
            );
        } catch (Throwable error) {
            add(rows, "通用 Activity 组件与 dex 元数据", describe(error), false);
        }
    }

    private static void appendEgl(List<String> rows) {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) {
                throw new IllegalStateException("no EGL display");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IllegalStateException("eglInitialize failed");
            }
            int[] configAttributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(
                display,
                configAttributes,
                0,
                configs,
                0,
                1,
                count,
                0
            ) || count[0] == 0) {
                throw new IllegalStateException("no EGL config");
            }
            int[] surfaceAttributes = {
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            };
            surface = EGL14.eglCreatePbufferSurface(
                display,
                configs[0],
                surfaceAttributes,
                0
            );
            int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            };
            context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0
            );
            if (surface == EGL14.EGL_NO_SURFACE ||
                context == EGL14.EGL_NO_CONTEXT ||
                !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IllegalStateException("EGL context unavailable");
            }
            String detail = "version=" + version[0] + "." + version[1]
                + " vendor=" + GLES20.glGetString(GLES20.GL_VENDOR)
                + " renderer=" + GLES20.glGetString(GLES20.GL_RENDERER)
                + " gles=" + GLES20.glGetString(GLES20.GL_VERSION);
            add(
                rows,
                "通用 EGL/GLES 渲染环境",
                detail,
                detail.contains("renderer=") &&
                    !detail.contains("renderer=null") &&
                    !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用 EGL/GLES 渲染环境", describe(error), false);
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                );
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context);
                }
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface);
                }
                EGL14.eglTerminate(display);
            }
        }
    }

    private static void appendConnectivity(
        Activity activity,
        List<String> rows
    ) {
        try {
            ConnectivityManager manager = activity.getSystemService(
                ConnectivityManager.class
            );
            Network network = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null
                ? null
                : manager.getNetworkCapabilities(network);
            String detail = network == null
                ? "no active network"
                : "network=" + network
                    + " wifi=" + hasTransport(
                        capabilities,
                        NetworkCapabilities.TRANSPORT_WIFI
                    )
                    + " cellular=" + hasTransport(
                        capabilities,
                        NetworkCapabilities.TRANSPORT_CELLULAR
                    )
                    + " vpn=" + hasTransport(
                        capabilities,
                        NetworkCapabilities.TRANSPORT_VPN
                    );
            add(
                rows,
                "通用网络能力与传输类型",
                detail,
                network != null && capabilities != null &&
                    !containsHostMarker(detail)
            );
        } catch (Throwable error) {
            add(rows, "通用网络能力与传输类型", describe(error), false);
        }
    }

    private static boolean hasTransport(
        NetworkCapabilities capabilities,
        int transport
    ) {
        return capabilities != null && capabilities.hasTransport(transport);
    }

    private static void appendKeystore(List<String> rows) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            int aliases = keyStore.size();
            add(
                rows,
                "通用 AndroidKeyStore",
                "loaded=true aliases=" + aliases,
                true
            );
        } catch (Throwable error) {
            add(rows, "通用 AndroidKeyStore", describe(error), false);
        }
    }

    private static void appendRunningProcess(
        Activity activity,
        List<String> rows
    ) {
        try {
            ActivityManager manager = activity.getSystemService(
                ActivityManager.class
            );
            List<ActivityManager.RunningAppProcessInfo> processes =
                manager == null ? null : manager.getRunningAppProcesses();
            boolean own = false;
            StringBuilder detail = new StringBuilder();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (detail.length() != 0) detail.append(',');
                    detail.append(process.pid).append(':').append(process.processName)
                        .append(':').append(process.uid);
                    if (process.pid == Process.myPid() &&
                        process.uid == Process.myUid() &&
                        process.processName.startsWith(activity.getPackageName())) {
                        own = true;
                    }
                }
            }
            add(
                rows,
                "通用 ActivityManager 进程视图",
                "own=" + own + " count=" + (
                    processes == null ? -1 : processes.size()
                ) + " processes=" + detail,
                own && !containsHostMarker(detail.toString())
            );
        } catch (Throwable error) {
            add(rows, "通用 ActivityManager 进程视图", describe(error), false);
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
