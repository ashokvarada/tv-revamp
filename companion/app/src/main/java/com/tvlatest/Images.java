package com.tvlatest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** TMDB images, kept in the app's files so the launcher and the list never wait on the network twice. */
final class Images {
    static final String BACKDROP = "w1280", POSTER = "w342";
    private static final long KEEP_MS = 14L * 24 * 60 * 60 * 1000;

    private Images() {
    }

    static File file(Context c, String size, String path) {
        File dir = new File(c.getFilesDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, size + "_" + path.replace("/", ""));
    }

    /** The cached file, downloaded first if needed; null on failure. Call off the main thread. */
    static File fetch(Context c, String size, String path) {
        if (path == null || path.isEmpty()) return null;
        File f = file(c, size, path);
        if (f.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());   // still in use, so prune keeps it
            return f;
        }
        File tmp = new File(f.getPath() + ".part");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/" + size + path).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return tmp.renameTo(f) ? f : null;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Any image link an app put in its Continue watching row (https, or a content:// or android.resource:// of its
     * own), copied once into the files; null on failure. Call off the main thread.
     */
    static File fetchUrl(Context c, String url) {
        if (url == null || url.isEmpty()) return null;
        File dir = new File(c.getFilesDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "wn_" + Integer.toHexString(url.hashCode()) + ".img");
        if (f.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        File tmp = new File(f.getPath() + ".part");
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            if (url.startsWith("http")) {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                if (conn.getResponseCode() != 200) return null;
                in = conn.getInputStream();
            } else {
                in = c.getContentResolver().openInputStream(android.net.Uri.parse(url));
            }
            if (in == null) return null;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return tmp.renameTo(f) ? f : null;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (conn != null) conn.disconnect();
        }
    }

    /** Decoded at about the given width, in RGB_565 to halve the memory. */
    static Bitmap decode(File f, int width) {
        if (f == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Drops images nobody has needed for two weeks. */
    static void prune(Context c) {
        File[] files = new File(c.getFilesDir(), "img").listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (f.lastModified() < cutoff) f.delete();
        }
    }
}
