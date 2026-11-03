package com.tvlatest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** One title from TMDB, merged across the services and regions it was found on. */
final class Item {
    String type;          // "tv" or "movie"
    long id;
    String title, originalTitle, lang, date, overview, backdrop, poster;
    double popularity;
    /** TMDB's average vote, 0..10; 0 when nobody has rated it yet. */
    double rating;
    final Set<String> packages = new LinkedHashSet<>();
    /** TMDB genre ids (movie and TV ids differ; Moods knows both). */
    final Set<Integer> genres = new LinkedHashSet<>();

    String key() {
        return type + ":" + id;
    }

    boolean preferred(Set<String> langs) {
        return langs.contains(lang);
    }

    String languageName() {
        if (lang == null || lang.isEmpty()) return "";
        String n = new Locale(lang).getDisplayLanguage(Locale.ENGLISH);
        return n.isEmpty() || n.equals(lang) ? lang.toUpperCase(Locale.ROOT) : n;
    }

    String year() {
        return date != null && date.length() >= 4 ? date.substring(0, 4) : "";
    }

    /** "7.8", or "" when unrated. */
    String ratingText() {
        return rating <= 0 ? "" : String.format(Locale.ROOT, "%.1f", rating);
    }

    String services() {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Services.name(p));
        }
        return sb.toString();
    }

    /** From a TMDB discover result. */
    static Item fromTmdb(String type, JSONObject o) {
        Item i = new Item();
        i.type = type;
        i.id = o.optLong("id");
        boolean tv = type.equals("tv");
        i.title = o.optString(tv ? "name" : "title");
        i.originalTitle = o.optString(tv ? "original_name" : "original_title");
        i.lang = o.optString("original_language");
        i.date = o.optString(tv ? "first_air_date" : "release_date");
        i.overview = o.optString("overview");
        i.backdrop = o.isNull("backdrop_path") ? null : o.optString("backdrop_path", null);
        i.poster = o.isNull("poster_path") ? null : o.optString("poster_path", null);
        i.popularity = o.optDouble("popularity", 0);
        i.rating = o.optInt("vote_count", 0) >= 3 ? o.optDouble("vote_average", 0) : 0;
        JSONArray g = o.optJSONArray("genre_ids");
        if (g != null) for (int k = 0; k < g.length(); k++) i.genres.add(g.optInt(k));
        return i;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type).put("id", id).put("title", title).put("originalTitle", originalTitle)
                .put("lang", lang).put("date", date).put("overview", overview)
                .put("backdrop", backdrop).put("poster", poster).put("popularity", popularity).put("rating", rating);
        JSONArray p = new JSONArray();
        for (String s : packages) p.put(s);
        o.put("packages", p);
        JSONArray g = new JSONArray();
        for (int id : genres) g.put(id);
        o.put("genres", g);
        return o;
    }

    static Item fromJson(JSONObject o) {
        Item i = new Item();
        i.type = o.optString("type");
        i.id = o.optLong("id");
        i.title = o.optString("title");
        i.originalTitle = o.optString("originalTitle");
        i.lang = o.optString("lang");
        i.date = o.optString("date");
        i.overview = o.optString("overview");
        i.backdrop = o.optString("backdrop", null);
        i.poster = o.optString("poster", null);
        i.popularity = o.optDouble("popularity", 0);
        i.rating = o.optDouble("rating", 0);
        JSONArray p = o.optJSONArray("packages");
        if (p != null) for (int k = 0; k < p.length(); k++) i.packages.add(p.optString(k));
        JSONArray g = o.optJSONArray("genres");
        if (g != null) for (int k = 0; k < g.length(); k++) i.genres.add(g.optInt(k));
        return i;
    }
}
