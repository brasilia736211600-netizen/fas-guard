package com.fas.guard.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/** Read-only content:// bridge so OpenVPN/WireGuard apps can import our config files. */
public class ProfileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.fas.guard.profiles";

    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return null;
        name = name.replace("/", "");
        if (!name.matches("[A-Za-z0-9._-]+")) return null;
        File dir = new File(getContext().getFilesDir(), "profiles");
        File f = new File(dir, name);
        return f.exists() ? f : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File f = resolve(uri);
        if (f == null) throw new java.io.FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null) return "application/octet-stream";
        if (n.endsWith(".ovpn")) return "application/x-openvpn-profile";
        if (n.endsWith(".conf") || n.endsWith(".wg")) return "application/octet-stream";
        return "text/plain";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            row[i] = OpenableColumns.SIZE.equals(cols[i]) ? f.length() : f.getName();
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
