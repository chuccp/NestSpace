package com.coke.testapp;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.content.pm.PackageInfo;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class TestWorkerProvider extends ContentProvider {
    private static final AtomicInteger ROWS = new AtomicInteger();
    private static final AtomicInteger UPDATES = new AtomicInteger();
    private static final AtomicInteger DELETES = new AtomicInteger();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"value"});
        cursor.addRow(new Object[]{
            "worker-provider pid=" + android.os.Process.myPid()
                + " process=" + ProcessInfo.name()
                + " rows=" + ROWS.get()
                + " updates=" + UPDATES.get()
                + " deletes=" + DELETES.get()
        });
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.test.worker";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        ROWS.incrementAndGet();
        getContext().getContentResolver().notifyChange(uri, null);
        return uri.buildUpon().appendPath("inserted").build();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        DELETES.incrementAndGet();
        getContext().getContentResolver().notifyChange(uri, null);
        return 1;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        UPDATES.incrementAndGet();
        getContext().getContentResolver().notifyChange(uri, null);
        return 1;
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        int count = values == null ? 0 : values.length;
        ROWS.addAndGet(count);
        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        result.putInt("callerUid", Binder.getCallingUid());
        result.putInt("callerPid", Binder.getCallingPid());
        result.putInt(
            "callerWorkSourceUid",
            Binder.getCallingWorkSourceUid()
        );
        result.putInt(
            "callerUser",
            callingUser()
        );
        result.putString("callerSid", callingSid());
        result.putInt(
            "callerNetworkPermission",
            getContext().checkCallingPermission(
                Manifest.permission.ACCESS_NETWORK_STATE
            )
        );
        try {
            String[] packages = getContext().getPackageManager()
                .getPackagesForUid(Binder.getCallingUid());
            result.putString(
                "callerPackages",
                java.util.Arrays.toString(packages)
            );
        } catch (Throwable error) {
            result.putString("callerPackages", "");
        }
        result.putInt("workerUid", Process.myUid());
        result.putInt("workerPid", Process.myPid());
        result.putString("workerProcess", ProcessInfo.name());
        try {
            PackageInfo self = getContext().getPackageManager()
                .getPackageInfo(getContext().getPackageName(), 0);
            result.putBoolean(
                "workerPackageApplicationInfo",
                self.applicationInfo != null
            );
        } catch (Throwable error) {
            result.putBoolean("workerPackageApplicationInfo", false);
            result.putString("workerPackageError", error.toString());
        }
        result.putString(
            "value",
            "worker-call pid=" + android.os.Process.myPid()
                + " method=" + method
                + " arg=" + arg
        );
        return result;
    }

    private static String callingSid() {
        try {
            java.lang.reflect.Method method =
                Binder.class.getDeclaredMethod("getCallingSid");
            method.setAccessible(true);
            return String.valueOf(method.invoke(null));
        } catch (Throwable error) {
            return "unavailable";
        }
    }

    private static int callingUser() {
        try {
            java.lang.reflect.Method method =
                android.os.UserHandle.class.getDeclaredMethod(
                    "getIdentifier"
                );
            method.setAccessible(true);
            return ((Number) method.invoke(
                Binder.getCallingUserHandle()
            )).intValue();
        } catch (Throwable error) {
            return Binder.getCallingUserHandle().hashCode();
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
        throws FileNotFoundException {
        File file = new File(getContext().getCacheDir(), "worker-provider.txt");
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("worker-provider-file");
        } catch (IOException error) {
            throw new FileNotFoundException(error.toString());
        }
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_ONLY
        );
    }
}
