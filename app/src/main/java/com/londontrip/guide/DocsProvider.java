package com.londontrip.guide;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves saved confirmations (screenshots and PDFs kept in the app's private storage)
 * read-only, so the page can show them and a PDF or photo viewer can open them.
 */
public class DocsProvider extends ContentProvider {
    static final String AUTHORITY = "com.londontrip.guide.docs";

    static Uri uriFor(String id) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(id).build();
    }

    /** The saved file for a content URI or id, or null if it isn't one of ours. */
    static File fileFor(File dir, String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("\\") || id.startsWith(".")) {
            return null;
        }
        File f = new File(dir, id);
        return f.isFile() ? f : null;
    }

    static File docsDir(android.content.Context ctx) {
        File dir = new File(ctx.getFilesDir(), "docs");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** Saved files are named "<millis>_<original name>"; this returns the original name. */
    static String displayName(String id) {
        int i = id.indexOf('_');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    static String typeOf(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".heic") || n.endsWith(".heif")) return "image/heic";
        return "image/jpeg";
    }

    private File lookup(Uri uri) {
        return getContext() == null ? null : fileFor(docsDir(getContext()), uri.getLastPathSegment());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = lookup(uri);
        if (f == null) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        File f = lookup(uri);
        return f == null ? null : typeOf(f.getName());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = lookup(uri);
        String[] cols = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        if (f != null) {
            c.addRow(new Object[]{displayName(f.getName()), f.length()});
        }
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
