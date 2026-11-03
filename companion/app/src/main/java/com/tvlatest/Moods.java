package com.tvlatest;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Explore's rows: moods instead of genres. Each mood is a set of TMDB genre ids (movie and TV ids together; TV
 * folds some genres into one, like 10759 Action & Adventure) and a neon accent. A title can sit in several moods.
 * Rows come out in this order, newest first within each, preferred languages first (the order Store.rank gives).
 */
final class Moods {
    static final class Row {
        final String name;
        final int accent;
        final List<Item> items = new ArrayList<>();

        Row(String name, int accent) {
            this.name = name;
            this.accent = accent;
        }
    }

    private static final class Mood {
        final String name;
        final int accent;
        final Set<Integer> genres;

        Mood(String name, int accent, Integer... genres) {
            this.name = name;
            this.accent = accent;
            this.genres = new HashSet<>(Arrays.asList(genres));
        }
    }

    static final int FRESH = 0xFF00E5FF;
    private static final List<Mood> MOODS = Arrays.asList(
            new Mood("Edge of your seat", 0xFFFF3D71, 53, 80, 9648, 28, 10759, 10752, 10768),
            new Mood("Feel-good", 0xFFFFD60A, 35, 10402, 10764),
            new Mood("Mind-bending", 0xFFA06CFF, 878, 14, 10765),
            new Mood("Heart strings", 0xFFFF6EC7, 18, 10749, 10766),
            new Mood("Lights off", 0xFF39FF88, 27),
            new Mood("Family couch", 0xFF00F5A0, 10751, 16, 10762),
            new Mood("Real stories", 0xFF4DA3FF, 99, 36, 10763, 10767),
            new Mood("Epic journeys", 0xFFFF9F1C, 12, 37));
    /**
     * Genre rows after the moods, the classic way to browse. TV folds some genres together on TMDB (10759 Action &
     * Adventure, 10765 Sci-Fi & Fantasy, 9648 Mystery for thrillers), so those ids count for both rows they cover.
     */
    private static final List<Mood> GENRES = Arrays.asList(
            new Mood("Action", 0xFFFF5252, 28, 10759),
            new Mood("Adventure", 0xFFFFB300, 12, 10759),
            new Mood("Comedy", 0xFFFFD60A, 35),
            new Mood("Romance", 0xFFFF69B4, 10749),
            new Mood("Drama", 0xFF9966CC, 18),
            new Mood("Horror", 0xFF39FF88, 27),
            new Mood("Thriller", 0xFFFF3D71, 53, 9648),
            new Mood("Sci-Fi", 0xFF00E5FF, 878, 10765),
            new Mood("Fantasy", 0xFFA06CFF, 14, 10765));
    /** A row longer than this is cut: Explore is for browsing, the full list is still in the data. */
    private static final int MAX_PER_ROW = 30;
    private static final long FRESH_DAYS = 10;

    private Moods() {
    }

    /** The rows that have anything in them; an empty mood is left out, so Explore never shows a blank row. */
    static List<Row> rows(List<Item> ranked) {
        List<Row> out = new ArrayList<>();
        Row fresh = new Row("Fresh this week", FRESH);
        String since = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .format(new Date(System.currentTimeMillis() - FRESH_DAYS * 24 * 60 * 60 * 1000));
        for (Item i : ranked) {
            if (i.date != null && i.date.compareTo(since) >= 0 && fresh.items.size() < MAX_PER_ROW) fresh.items.add(i);
        }
        if (!fresh.items.isEmpty()) out.add(fresh);
        List<Mood> all = new ArrayList<>(MOODS);
        all.addAll(GENRES);
        for (Mood m : all) {
            Row r = new Row(m.name, m.accent);
            for (Item i : ranked) {
                if (r.items.size() >= MAX_PER_ROW) break;
                for (int g : i.genres) {
                    if (m.genres.contains(g)) {
                        r.items.add(i);
                        break;
                    }
                }
            }
            if (!r.items.isEmpty()) out.add(r);
        }
        // Titles TMDB gave no genre (common for brand-new regional releases) still get a home.
        Row more = new Row("More to discover", 0xFF9AA0A6);
        for (Item i : ranked) {
            if (i.genres.isEmpty() && more.items.size() < MAX_PER_ROW) more.items.add(i);
        }
        if (!more.items.isEmpty()) out.add(more);
        return out;
    }
}
