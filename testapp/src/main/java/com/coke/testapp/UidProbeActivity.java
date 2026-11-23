package com.coke.testapp;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.system.Os;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dedicated UID consistency view. Every row is an app-visible identity source;
 * any row containing a UID other than Process.myUid() is marked MISMATCH.
 */
public final class UidProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int expectedUid = Process.myUid();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 16);

        TextView title = new TextView(this);
        title.setText("UID 对照\n期望虚拟 UID: " + expectedUid);
        title.setTextSize(18);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        List<String> rows = collectRows(this, expectedUid);
        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(
            this,
            android.R.layout.simple_list_item_1,
            rows
        ));
        root.addView(
            list,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );
        setContentView(root);
    }

    static List<String> collectRows(Activity activity, int expectedUid) {
        List<String> rows = new ArrayList<>();
        add(rows, expectedUid, "Process.myUid", expectedUid);
        add(rows, expectedUid, "Os.getuid", safe(Os::getuid));
        add(rows, expectedUid, "Os.geteuid", safe(Os::geteuid));
        add(rows, expectedUid, "Binder.getCallingUid", Binder.getCallingUid());
        add(
            rows,
            expectedUid,
            "ApplicationInfo.uid",
            activity.getApplicationInfo().uid
        );

        PackageManager pm = activity.getPackageManager();
        String packageName = activity.getPackageName();
        add(rows, expectedUid, "PackageManager.getPackageUid", safe(
            () -> pm.getPackageUid(packageName, 0)
        ));
        add(rows, expectedUid, "PackageManager.getPackagesForUid", safe(
            () -> Arrays.toString(pm.getPackagesForUid(expectedUid))
        ));
        add(rows, expectedUid, "PackageManager.getNameForUid", safe(
            () -> pm.getNameForUid(expectedUid)
        ));
        add(rows, expectedUid, "PackageInfo.applicationInfo.uid", safe(() -> {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            return info.applicationInfo == null ? -1 : info.applicationInfo.uid;
        }));
        add(rows, expectedUid, "/proc/self/status Uid", procStatusUid());
        add(rows, expectedUid, "dataDir stat.uid", safe(
            () -> Os.lstat(activity.getDataDir().getAbsolutePath()).st_uid
        ));

        String nativeReport = String.valueOf(safe(() -> {
            NativeProbe.ensureLoaded();
            ApplicationInfo info = activity.getApplicationInfo();
            return NativeProbe.nativeHookProbe(
                activity.getDataDir().getAbsolutePath(),
                info.sourceDir,
                ""
            );
        }));
        add(
            rows,
            expectedUid,
            "native libc.getuid",
            nativeField(nativeReport, "libc.getuid")
        );
        add(
            rows,
            expectedUid,
            "native syscall.getuid",
            nativeField(nativeReport, "syscall.getuid")
        );

        for (String line : RawBinderProbe.report(packageName).split("\n")) {
            if (line.startsWith("service.methods=")) continue;
            int separator = line.indexOf('=');
            String label = separator < 0 ? line : line.substring(0, separator);
            add(rows, expectedUid, "raw " + label, line);
        }
        return rows;
    }

    private static void add(
        List<String> rows,
        int expectedUid,
        String label,
        Object value
    ) {
        String text = String.valueOf(value);
        rows.add(label + "\n" + text + "  " + status(expectedUid, text));
    }

    private static String status(int expectedUid, String value) {
        if (value == null || value.isEmpty()) return "MISMATCH";
        if (value.contains("error=")) return "MISMATCH";
        if (!value.matches(".*\\b\\d{3,}\\b.*")) return "N/A";
        if (value.matches(".*\\b" + expectedUid + "\\b.*")) return "OK";
        return "MISMATCH";
    }

    private static String procStatusUid() {
        try (BufferedReader reader = new BufferedReader(
            new FileReader("/proc/self/status")
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Uid:")) return line;
            }
        } catch (Throwable error) {
            return "error=" + error;
        }
        return "missing";
    }

    private static String nativeField(String report, String key) {
        if (report == null) return "null";
        String prefix = key + "=";
        for (String line : report.split("\n")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length());
        }
        return "missing";
    }

    private static Object safe(Value<?> value) {
        try {
            return value.get();
        } catch (Throwable error) {
            return "error=" + error.getClass().getSimpleName()
                + ":" + error.getMessage();
        }
    }

    private interface Value<T> {
        T get() throws Throwable;
    }
}
