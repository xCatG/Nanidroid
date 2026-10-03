package com.cattailsw.nanidroid.install;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/** Test APK only: supplies exact archive bytes through a content URI and a pipe. */
public final class GhostImportTestProvider extends ContentProvider {
    private static final String AUTHORITY = "com.cattailsw.nanidroid.test.documents";
    private static final class Gate {
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean waiting;
    }
    private static final ConcurrentHashMap<String, Gate> GATES = new ConcurrentHashMap<>();

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("release-read".equals(method)) {
            Gate gate = GATES.get(arg);
            if (gate != null) gate.release.countDown();
        } else if ("gate-status".equals(method)) {
            Bundle result = new Bundle();
            Gate gate = GATES.get(arg);
            result.putBoolean("blocked", gate != null && gate.waiting);
            return result;
        }
        return Bundle.EMPTY;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        String[] columns = projection != null ? projection
                : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "test.nar";
            // SIZE deliberately remains null.
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String fault = uri.getQueryParameter("mode");
        if (!"r".equals(mode) || "open-fail".equals(fault)) {
            throw new FileNotFoundException("Injected provider refusal");
        }
        final byte[] bytes;
        try {
            bytes = Base64.decode(uri.getQueryParameter("data"), Base64.URL_SAFE | Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new FileNotFoundException("Invalid test bytes");
        }
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        final String gateId = uri.getQueryParameter("gate");
        final Gate gate = "gated".equals(fault) ? new Gate() : null;
        if (gate != null) GATES.put(gateId, gate);
        new Thread(() -> {
            ParcelFileDescriptor.AutoCloseOutputStream out =
                    new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
            try {
                for (int offset = 0; offset < bytes.length; offset += 64) {
                    if ("read-fail".equals(fault) && offset > 0) {
                        throw new IOException("Injected read failure");
                    }
                    out.write(bytes, offset, Math.min(64, bytes.length - offset));
                    if (gate != null && offset == 0) {
                        gate.waiting = true;
                        gate.release.await();
                    }
                    if ("slow".equals(fault)) Thread.sleep(25);
                }
                out.close();
            } catch (IOException | InterruptedException e) {
                try { pipe[1].closeWithError(e.getMessage()); } catch (IOException ignored) { }
            } finally {
                if (gate != null) GATES.remove(gateId, gate);
            }
        }, "ghost-import-test-provider").start();
        return pipe[0];
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    public static Uri uri(byte[] bytes, String mode) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("archive")
                .appendQueryParameter("mode", mode)
                .appendQueryParameter("data", Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP))
                .build();
    }

    public static Uri gatedUri(byte[] bytes, String gateId) {
        return uri(bytes, "gated").buildUpon().appendQueryParameter("gate", gateId).build();
    }
}
