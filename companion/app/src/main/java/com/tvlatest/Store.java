package com.tvlatest;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Settings (TMDB key, regions, preferred languages) and the last fetch, ranked. */
final class Store {
    static final String DEFAULT_LANGS = "ta,te,hi,en";
    private static final String DATA = "latest.json";

    private Store() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static String key(Context c) {
        return prefs(c).getString("key", "");
    }

    static List<String> regions(Context c) {
        List<String> out = new ArrayList<>();
        for (String r : prefs(c).getString("regions", "US,IN").split(",")) {
            if (!r.trim().isEmpty()) out.add(r.trim().toUpperCase());
        }
        return out;
    }

    static Set<String> langs(Context c) {
        return new HashSet<>(Arrays.asList(prefs(c).getString("langs", DEFAULT_LANGS).split(",")));
    }

    /** Titles and Continue watching rows the person removed with a long press: never shown again. */
    static Set<String> hidden(Context c) {
        return new HashSet<>(prefs(c).getStringSet("hidden", new HashSet<>()));
    }

    static void hide(Context c, String key) {
        Set<String> h = hidden(c);
        h.add(key);
        prefs(c).edit().putStringSet("hidden", h).commit();
    }

    /** The colour picked in the launcher's Personalize screen (pushed here), #BA0021 until then. */
    static int accent(Context c) {
        return prefs(c).getInt("accent", 0xFFBA0021);
    }

    static String name(Context c) {
        return prefs(c).getString("name", "");
    }

    /** A title as a key: lower case, letters and digits only, so "Black Clover" and "black clover" match. */
    static String norm(String title) {
        return title == null ? "" : title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static long updated(Context c) {
        return prefs(c).getLong("updated", 0);
    }

    /** Preferred languages first, then newest first; ties go to the more popular title. */
    static List<Item> rank(Collection<Item> items, Set<String> langs) {
        List<Item> out = new ArrayList<>(items);
        out.sort((a, b) -> {
            boolean pa = a.preferred(langs), pb = b.preferred(langs);
            if (pa != pb) return pa ? -1 : 1;
            int d = b.date.compareTo(a.date);
            return d != 0 ? d : Double.compare(b.popularity, a.popularity);
        });
        return out;
    }

    /** Titles the launcher shows per app: its recommendation row and the backdrops it cycles through. */
    static final int PER_SERVICE = 10;

    /** The first ranked title on each service that has a backdrop: what the launcher shows behind the app. */
    static Map<String, Item> topPerService(List<Item> ranked) {
        Map<String, Item> top = new LinkedHashMap<>();
        for (Map.Entry<String, List<Item>> e : perService(ranked, 1).entrySet()) top.put(e.getKey(), e.getValue().get(0));
        return top;
    }

    /** The first n ranked titles with a backdrop on each service, in rank order. */
    static Map<String, List<Item>> perService(List<Item> ranked, int n) {
        Map<String, List<Item>> out = new LinkedHashMap<>();
        for (Item i : ranked) {
            if (i.backdrop == null) continue;
            for (String p : i.packages) {
                List<Item> l = out.get(p);
                if (l == null) out.put(p, l = new ArrayList<>());
                if (l.size() < n) l.add(i);
            }
        }
        return out;
    }

    static synchronized void save(Context c, List<Item> ranked) throws Exception {
        JSONArray a = new JSONArray();
        for (Item i : ranked) a.put(i.toJson());
        File tmp = new File(c.getFilesDir(), DATA + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(new JSONObject().put("items", a).toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(c.getFilesDir(), DATA))) throw new IOException("rename failed");
        prefs(c).edit().putLong("updated", System.currentTimeMillis()).apply();
    }

    static synchronized List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        File f = new File(c.getFilesDir(), DATA);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONArray a = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8)).getJSONArray("items");
            for (int k = 0; k < a.length(); k++) out.add(Item.fromJson(a.getJSONObject(k)));
        } catch (Exception ignored) {
            // A damaged file reads as empty; the next fetch writes it again.
        }
        return out;
    }
}
