package com.tvlatest;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Map;

/**
 * Read-only data for the launcher, so the launcher itself never needs network access:
 * content://com.tvlatest.latest/top                  per streaming app, its titles (rank 0 = newest preferred)
 * content://com.tvlatest.latest/backdrop/PKG/RANK    that title's backdrop, already downloaded by the daily job
 * content://com.tvlatest.latest/backdrop/PKG         rank 0's backdrop
 * content://com.tvlatest.latest/watching             Android's Continue watching rows, newest first
 * content://com.tvlatest.latest/watching_image/ID    that row's picture, downloaded on first use
 */
public class LatestProvider extends ContentProvider {
    static final String AUTHORITY = "com.tvlatest.latest";
    static final Uri TOP = Uri.parse("content://" + AUTHORITY + "/top");
    private static final String[] COLUMNS = {"package", "title", "language", "year", "type", "rank", "overview",
            "rating", "key", "services"};
    private static final String[] WATCHING = {"id", "package", "title", "subtitle", "progress", "intent", "key",
            "engaged"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        List<String> seg = uri.getPathSegments();
        if (!seg.isEmpty() && seg.get(0).equals("probe")) return WatchNext.probe(getContext());
        if (!seg.isEmpty() && seg.get(0).equals("watching")) {
            MatrixCursor w = new MatrixCursor(WATCHING);
            for (WatchNext.Entry e : WatchNext.read(getContext())) {
                w.addRow(new Object[]{e.id, e.pkg, e.title, e.subtitle, e.progress, e.intentUri, WatchNext.key(e),
                        e.engaged});
            }
            return w;
        }
        if (seg.isEmpty() || !seg.get(0).equals("top")) return null;
        FetchJob.ensure(getContext());   // the launcher asks often: a cheap place to keep the daily job alive
        MatrixCursor c = new MatrixCursor(COLUMNS);
        for (Map.Entry<String, List<Item>> e : rows().entrySet()) {
            List<Item> row = e.getValue();
            for (int rank = 0; rank < row.size(); rank++) {
                Item i = row.get(rank);
                c.addRow(new Object[]{e.getKey(), i.title, i.languageName(), i.year(), i.type, rank, i.overview,
                        i.ratingText(), i.key(), i.services()});
            }
        }
        c.setNotificationUri(getContext().getContentResolver(), TOP);
        return c;
    }

    /** Per app, only the titles whose backdrop is on disk, so the ranks match what openFile can serve. */
    private Map<String, List<Item>> rows() {
        java.util.Set<String> hidden = Store.hidden(getContext());
        List<Item> all = Store.load(getContext());
        all.removeIf(i -> hidden.contains(i.key()));
        Map<String, List<Item>> rows = Store.perService(all, Store.PER_SERVICE);
        for (List<Item> row : rows.values()) {
            row.removeIf(i -> !Images.file(getContext(), Images.BACKDROP, i.backdrop).exists());
        }
        rows.values().removeIf(List::isEmpty);
        return rows;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if ("r".equals(mode) && seg.size() == 2 && seg.get(0).equals("watching_image")) {
            for (WatchNext.Entry e : WatchNext.read(getContext())) {
                if (!String.valueOf(e.id).equals(seg.get(1))) continue;
                File f = Images.fetchUrl(getContext(), e.poster);
                if (f == null) break;
                return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            throw new FileNotFoundException(seg.get(1));
        }
        if (!"r".equals(mode) || seg.size() < 2 || seg.size() > 3 || !seg.get(0).equals("backdrop")) {
            throw new FileNotFoundException();
        }
        int rank = 0;
        if (seg.size() == 3) {
            try {
                rank = Integer.parseInt(seg.get(2));
            } catch (NumberFormatException e) {
                throw new FileNotFoundException(seg.get(2));
            }
        }
        List<Item> row = rows().get(seg.get(1));
        if (row == null || rank < 0 || rank >= row.size()) throw new FileNotFoundException(seg.get(1));
        File f = Images.file(getContext(), Images.BACKDROP, row.get(rank).backdrop);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * The launcher's writes: "hide" (arg: a title's key or a Continue watching key), "refresh" (fetch now) and "prefs"
     * (extras name and accent from Personalize). Only the launcher and this app may call them.
     */
    @Override
    public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        String caller = getCallingPackage();
        if (!"com.gathin.tvhome".equals(caller) && !getContext().getPackageName().equals(caller)) {
            throw new SecurityException("not allowed");
        }
        android.os.Bundle out = new android.os.Bundle();
        switch (method) {
            case "hide":
                if (arg != null && !arg.isEmpty()) Store.hide(getContext(), arg);
                getContext().getContentResolver().notifyChange(TOP, null);
                break;
            case "refresh":
                out.putBoolean("started", FetchJob.refreshNow(getContext()));
                break;
            case "refreshing":
                out.putBoolean("refreshing", FetchJob.refreshing());
                break;
            case "prefs":
                if (extras != null) {
                    android.content.SharedPreferences.Editor e = Store.prefs(getContext()).edit();
                    if (extras.containsKey("name")) e.putString("name", extras.getString("name", ""));
                    if (extras.containsKey("accent")) e.putInt("accent", extras.getInt("accent"));
                    e.apply();
                }
                break;
            default:
                return null;
        }
        return out;
    }

    @Override
    public String getType(Uri uri) {
        return uri.getPathSegments().size() >= 2 ? "image/jpeg" : "vnd.android.cursor.dir/vnd.tvlatest.top";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
