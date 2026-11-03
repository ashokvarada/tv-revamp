package com.gathin.tvhome;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local addition (not upstream): the newest titles on each streaming app, read from the companion "Latest" app
 * (com.tvlatest), which does the daily TMDB fetch. This launcher still has no network access: it only reads that
 * app's read-only provider. Without the companion app installed, nothing happens.
 */
final class LatestShows {
    static final class Show {
        final String pkg, title, language, year, type;
        final int rank;
        /** TMDB's summary, rating ("7.8" or ""), its key for removing it, and every app it is on ("Netflix, Prime Video"). */
        String overview, rating, key, services;

        Show(String pkg, int rank, String title, String language, String year, String type) {
            this.pkg = pkg;
            this.rank = rank;
            this.title = title;
            this.language = language;
            this.year = year;
            this.type = type;
        }
    }

    /** A row of Android's Continue watching list (Watch Next), as the companion app hands it on. */
    static final class Watching {
        final long id;
        final String pkg, title, subtitle, intentUri;
        final float progress;   // 0..1, or below 0 when the app gave no position
        /** What removing it is remembered by (a series as a whole). */
        String key;
        /** When it was last watched (ms since 1970), 0 when the app didn't say. */
        long engaged;

        Watching(long id, String pkg, String title, String subtitle, float progress, String intentUri) {
            this.id = id;
            this.pkg = pkg;
            this.title = title;
            this.subtitle = subtitle;
            this.progress = progress;
            this.intentUri = intentUri;
        }
    }

    interface Listener {
        void onBitmap(Show show, Bitmap bitmap);
    }

    interface WatchingListener {
        void onBitmap(Watching item, Bitmap bitmap);
    }

    private static final String AUTHORITY = "content://com.tvlatest.latest";
    private static final Uri TOP = Uri.parse(AUTHORITY + "/top");
    private static final Uri WATCHING = Uri.parse(AUTHORITY + "/watching");
    /** Full backdrops (1280 px wide, RGB_565, about 1.8 MB each): the one on screen and the next few. */
    private static final int MAX_BACKDROPS = 6;
    /** Card thumbnails (about 320 px wide, 115 KB each): a few apps' rows. */
    private static final int MAX_THUMBS = 40;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Map<String, List<Show>> shows = new HashMap<>();
    private volatile Map<String, List<Watching>> watching = new HashMap<>();
    /** Every app's rows together, newest first: the Continue watching row under the apps. */
    private volatile List<Watching> allWatching = new ArrayList<>();
    private final Map<String, Bitmap> backdrops = lru(MAX_BACKDROPS);
    private final Map<String, Bitmap> thumbs = lru(MAX_THUMBS);
    private final ContentObserver observer;

    LatestShows(Context ctx, Runnable onChange) {
        this.ctx = ctx.getApplicationContext();
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                trim();
                refresh(onChange);
            }
        };
        try {
            this.ctx.getContentResolver().registerContentObserver(TOP, false, observer);
        } catch (SecurityException ignored) {
            // Companion app not installed: no data, no updates.
        }
    }

    private static Map<String, Bitmap> lru(int max) {
        return new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                return size() > max;
            }
        };
    }

    /** Re-reads the lists; cheap, and the companion app uses the call to keep its daily job scheduled. */
    void refresh(Runnable done) {
        io.execute(() -> {
            Map<String, List<Show>> next = new HashMap<>();
            try (Cursor c = ctx.getContentResolver().query(TOP, null, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String pkg = c.getString(0);
                    List<Show> row = next.get(pkg);
                    if (row == null) next.put(pkg, row = new ArrayList<>());
                    int rank = c.getColumnCount() > 5 ? c.getInt(5) : row.size();
                    Show show = new Show(pkg, rank, c.getString(1), c.getString(2), c.getString(3), c.getString(4));
                    if (c.getColumnCount() > 9) {
                        show.overview = c.getString(6);
                        show.rating = c.getString(7);
                        show.key = c.getString(8);
                        show.services = c.getString(9);
                    }
                    row.add(show);
                }
            } catch (RuntimeException ignored) {
                // Not installed, or the provider failed: keep what we had.
                return;
            }
            shows = next;
            Map<String, List<Watching>> nextWatching = new HashMap<>();
            List<Watching> nextAll = new ArrayList<>();
            try (Cursor c = ctx.getContentResolver().query(WATCHING, null, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    Watching w = new Watching(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                            c.getFloat(4), c.getString(5));
                    if (c.getColumnCount() > 6) w.key = c.getString(6);
                    if (c.getColumnCount() > 7) w.engaged = c.getLong(7);
                    nextAll.add(w);
                    List<Watching> row = nextWatching.get(w.pkg);
                    if (row == null) nextWatching.put(w.pkg, row = new ArrayList<>());
                    row.add(w);
                }
            } catch (RuntimeException ignored) {
                // An older companion app without the list: no row.
            }
            watching = nextWatching;
            allWatching = nextAll;
            if (done != null) main.post(done);
        });
    }

    boolean isEmpty() {
        return shows.isEmpty();
    }

    /** The app's titles in rank order; empty when there are none. */
    List<Show> list(String pkg) {
        List<Show> row = pkg == null ? null : shows.get(pkg);
        return row == null ? Collections.emptyList() : row;
    }

    /** The app's unfinished titles, most recently watched first; empty when there are none. */
    List<Watching> watching(String pkg) {
        List<Watching> row = pkg == null ? null : watching.get(pkg);
        return row == null ? Collections.emptyList() : row;
    }

    /** Continue watching across every app, newest first. */
    List<Watching> allWatching() {
        return allWatching;
    }

    /** The companion app is installed (its provider answers). */
    boolean available() {
        return ctx.getPackageManager().resolveContentProvider("com.tvlatest.latest", 0) != null;
    }

    /** Remove a title or a Continue watching row for good; the companion app keeps the list and tells us. */
    void hide(String key) {
        io.execute(() -> {
            try {
                ctx.getContentResolver().call(Uri.parse(AUTHORITY), "hide", key, null);
            } catch (RuntimeException ignored) {
                // Companion app gone.
            }
        });
    }

    /** Refresh: the companion app fetches from TMDB now; the usual change notice says when it is done. */
    boolean requestRefresh() {
        try {
            ctx.getContentResolver().call(Uri.parse(AUTHORITY), "refresh", null, null);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** "Black Clover" and "black clover!" alike: lower case, letters and digits only. */
    static String norm(String title) {
        return title == null ? "" : title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** The row's picture at screen size, for behind the shelf while its card has focus. */
    void watchingBackdrop(Watching item, WatchingListener listener) {
        String key = "wb#" + item.id;
        Bitmap cached;
        synchronized (backdrops) {
            cached = backdrops.get(key);
        }
        if (cached != null) {
            listener.onBitmap(item, cached);
            return;
        }
        io.execute(() -> {
            Bitmap b = decodeScaled(Uri.parse(AUTHORITY + "/watching_image/" + item.id), 1280);
            if (b == null) return;
            synchronized (backdrops) {
                backdrops.put(key, b);
            }
            main.post(() -> listener.onBitmap(item, b));
        });
    }

    /** A card-sized copy of the row's picture, handed to the listener on the main thread. */
    void watchingImage(Watching item, WatchingListener listener) {
        String key = "w#" + item.id;
        Bitmap cached;
        synchronized (thumbs) {
            cached = thumbs.get(key);
        }
        if (cached != null) {
            listener.onBitmap(item, cached);
            return;
        }
        io.execute(() -> {
            Bitmap b = decodeScaled(Uri.parse(AUTHORITY + "/watching_image/" + item.id), 360);
            if (b == null) return;
            synchronized (thumbs) {
                thumbs.put(key, b);
            }
            main.post(() -> listener.onBitmap(item, b));
        });
    }

    /** Decoded at about the given width: the pictures apps publish can be full HD. */
    private Bitmap decodeScaled(Uri uri, int width) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (Exception e) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inSampleSize = 1;
        while (bounds.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            return in == null ? null : BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    /** The full-screen backdrop, handed to the listener on the main thread. */
    void backdrop(Show show, Listener listener) {
        load(show, backdrops, 1, listener);
    }

    /** A small copy for the recommendation cards. */
    void thumb(Show show, Listener listener) {
        load(show, thumbs, 4, listener);
    }

    private void load(Show show, Map<String, Bitmap> cache, int sample, Listener listener) {
        String key = show.pkg + "#" + show.rank;
        Bitmap cached;
        synchronized (cache) {
            cached = cache.get(key);
        }
        if (cached != null) {
            listener.onBitmap(show, cached);
            return;
        }
        io.execute(() -> {
            Bitmap b = decode(show, sample);
            if (b == null) return;
            synchronized (cache) {
                cache.put(key, b);
            }
            main.post(() -> listener.onBitmap(show, b));
        });
    }

    private Bitmap decode(Show show, int sample) {
        Uri uri = Uri.parse(AUTHORITY + "/backdrop/" + show.pkg + "/" + show.rank);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inSampleSize = sample;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            return in == null ? null : BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    void trim() {
        synchronized (backdrops) {
            backdrops.clear();
        }
        synchronized (thumbs) {
            thumbs.clear();
        }
    }

    void close() {
        ctx.getContentResolver().unregisterContentObserver(observer);
        io.shutdownNow();
    }
}
