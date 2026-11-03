package com.tvlatest;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The daily fetch: for every installed streaming app, region and type (series, film), TMDB's newest titles,
 * once restricted to the preferred languages and once in any language, merged and ranked.
 */
final class Fetcher {
    private static final String TAG = "TvLatest";
    private static final String API = "https://api.themoviedb.org/3/discover/";

    private Fetcher() {
    }

    /** Returns false when nothing could be fetched (no key, no network, a refused key). */
    static synchronized boolean run(Context c) {
        String key = Store.key(c);
        if (key.isEmpty()) {
            Log.w(TAG, "no TMDB key set");
            return false;
        }
        Set<String> langs = Store.langs(c);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        Map<String, Item> merged = new LinkedHashMap<>();
        int ok = 0, failed = 0;
        for (Services.Service s : Services.ALL.values()) {
            if (!installed(c, s.pkg)) continue;
            for (String region : Store.regions(c)) {
                for (String type : new String[]{"tv", "movie"}) {
                    for (boolean preferredOnly : new boolean[]{true, false}) {
                        JSONArray results = discover(key, type, region, s.providerIds, today,
                                preferredOnly ? String.join("|", langs) : null);
                        if (results == null) {
                            failed++;
                            continue;
                        }
                        ok++;
                        for (int k = 0; k < results.length(); k++) {
                            Item i = Item.fromTmdb(type, results.optJSONObject(k));
                            // Freshly listed titles often have no artwork yet; a list of blank cards helps nobody.
                            if (i.poster == null || i.date == null || i.date.isEmpty()) continue;
                            Item have = merged.get(i.key());
                            if (have == null) merged.put(i.key(), have = i);
                            have.packages.add(s.pkg);
                        }
                    }
                }
            }
        }
        Log.i(TAG, "fetch: " + ok + " requests ok, " + failed + " failed, " + merged.size() + " titles");
        if (ok == 0) return false;
        List<Item> ranked = Store.rank(merged.values(), langs);
        // The launcher reads these straight from disk, so download them before saying the data is new.
        for (List<Item> row : Store.perService(ranked, Store.PER_SERVICE).values()) {
            for (Item i : row) Images.fetch(c, Images.BACKDROP, i.backdrop);
        }
        try {
            Store.save(c, ranked);
        } catch (Exception e) {
            Log.w(TAG, "save failed", e);
            return false;
        }
        Images.prune(c);
        return true;
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static JSONArray discover(String key, String type, String region, String providers, String today,
                                      String languages) {
        boolean tv = type.equals("tv");
        StringBuilder url = new StringBuilder(API).append(type)
                .append("?include_adult=false&language=en-US&page=1")
                .append("&watch_region=").append(region)
                .append("&with_watch_providers=").append(enc(providers))
                .append("&sort_by=").append(tv ? "first_air_date.desc" : "primary_release_date.desc")
                .append(tv ? "&first_air_date.lte=" : "&primary_release_date.lte=").append(today);
        if (languages != null) url.append("&with_original_language=").append(enc(languages));
        // A v4 read access token is a long JWT and goes in the header; a v3 key is short and goes in the URL.
        boolean bearer = key.length() > 40;
        if (!bearer) url.append("&api_key=").append(enc(key));
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/json");
            if (bearer) conn.setRequestProperty("Authorization", "Bearer " + key);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, type + " " + region + " " + providers + ": HTTP " + code);
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                return new JSONObject(buf.toString("UTF-8")).optJSONArray("results");
            }
        } catch (Exception e) {
            Log.w(TAG, type + " " + region + ": " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
