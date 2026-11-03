package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;

/**
 * Local addition: what the Personalize screen saves, the name in the greeting and the one highlight colour every
 * focused thing uses (top buttons, the bar under an app, the side bar, the dots, progress lines). Each change is
 * handed to the companion app (com.tvlatest) too, so Explore wears the same colour.
 */
final class Personal {
    static final int DEFAULT_ACCENT = 0xFFBA0021;

    /** The palette, by family: names and colours line up. */
    static final String[] FAMILIES = {"Reds & warm tones", "Purples & pinks", "Blues & cool tones",
            "Greens & yellows", "Minimal neutrals"};
    static final String[][] NAMES = {
            {"Classic Crimson", "Neon Cyber-Red", "Cinematic Ruby", "Soft Coral", "Blood Orange", "Candy Apple", "Rosewood"},
            {"Vibrant Violet", "Neon Magenta", "Deep Plum", "Cyber Grape", "Amethyst", "Twilight Indigo", "Hot Pink"},
            {"Electric Cyan", "Neon Sapphire", "Deep Ocean", "Ice Blue", "Royal Azure", "Cobalt Glow"},
            {"Luminous Emerald", "Matrix Green", "Mint Glow", "Neon Lime", "Cinematic Amber", "Goldenrod", "Neon Lemon"},
            {"Stark White", "Liquid Silver", "Gunmetal Gray"},
    };
    static final int[][] COLORS = {
            {0xFFE50914, 0xFFFF003C, 0xFFBA0021, 0xFFFF5252, 0xFFFF4500, 0xFFFF0800, 0xFF65000B},
            {0xFF8A2BE2, 0xFFFF00FF, 0xFF5D3954, 0xFF58427C, 0xFF9966CC, 0xFF4B0082, 0xFFFF69B4},
            {0xFF00E5FF, 0xFF0F52BA, 0xFF00509E, 0xFFA0E6FF, 0xFF0038A8, 0xFF0047AB},
            {0xFF00EA77, 0xFF00FF41, 0xFF98FF98, 0xFF39FF14, 0xFFFFB300, 0xFFDAA520, 0xFFFFFF00},
            {0xFFFFFFFF, 0xFFC0C0C0, 0xFF8D918D},
    };

    private Personal() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("personal", Context.MODE_PRIVATE);
    }

    static String name(Context c) {
        return prefs(c).getString("name", "");
    }

    static int accent(Context c) {
        return prefs(c).getInt("accent", DEFAULT_ACCENT);
    }

    /** Continue watching shows titles watched in the last this many days; 0 shows them all. */
    static final int[] WATCH_DAYS = {7, 14, 30, 0};

    static int watchDays(Context c) {
        return prefs(c).getInt("watch_days", 7);
    }

    static void setWatchDays(Context c, int days) {
        prefs(c).edit().putInt("watch_days", days).apply();
    }

    static void setName(Context c, String name) {
        prefs(c).edit().putString("name", name.trim()).apply();
        push(c);
    }

    static void setAccent(Context c, int accent) {
        prefs(c).edit().putInt("accent", accent).apply();
        push(c);
    }

    /** Explore reads the same name and colour from the companion app; nothing happens without it. */
    static void push(Context c) {
        Bundle b = new Bundle();
        b.putString("name", name(c));
        b.putInt("accent", accent(c));
        try {
            c.getContentResolver().call(Uri.parse("content://com.tvlatest.latest"), "prefs", null, b);
        } catch (RuntimeException ignored) {
            // Companion app not installed.
        }
    }

    /** White, ice blue, mint, lemon: black text and icons on them. */
    static boolean light(int color) {
        return (0.299 * ((color >> 16) & 0xFF) + 0.587 * ((color >> 8) & 0xFF) + 0.114 * (color & 0xFF)) / 255.0 > 0.6;
    }

    /** The top buttons: nothing until focused, then only a border in the colour (a rectangle, never filled). */
    static Drawable pill(int accent, float density) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, border(accent, density));
        s.addState(new int[]{android.R.attr.state_activated}, border((accent & 0x00FFFFFF) | 0x80000000, density));
        s.addState(new int[]{}, border(0x00000000, density));
        return s;
    }

    /** A highlight: a 2dp border in the colour round a rectangle with slightly rounded corners, nothing inside. */
    static GradientDrawable border(int color, float density) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(4 * density);
        g.setStroke(Math.round(2 * density), color);
        return g;
    }

    /** Ink on the top buttons: always white, since the highlight is only a border. */
    static ColorStateList ink(int accent) {
        return ColorStateList.valueOf(0xFFFFFFFF);
    }
}
