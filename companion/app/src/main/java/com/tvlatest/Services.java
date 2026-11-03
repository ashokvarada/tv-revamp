package com.tvlatest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The streaming apps on this TV and their TMDB watch-provider ids (JustWatch data). Several ids are joined
 * with "|", which TMDB reads as OR; an id that does not exist in a region simply matches nothing there.
 * Look ids up at https://api.themoviedb.org/3/watch/providers/tv?watch_region=US
 */
final class Services {
    static final class Service {
        final String pkg, name, providerIds;

        Service(String pkg, String name, String providerIds) {
            this.pkg = pkg;
            this.name = name;
            this.providerIds = providerIds;
        }
    }

    static final Map<String, Service> ALL = new LinkedHashMap<>();

    private static void add(String pkg, String name, String ids) {
        ALL.put(pkg, new Service(pkg, name, ids));
    }

    static {
        add("com.netflix.ninja", "Netflix", "8");
        add("com.amazon.amazonvideo.livingroom", "Prime Video", "9|119");
        add("com.disney.disneyplus", "Disney+", "337|122|2336");
        add("com.hulu.livingroomplus", "Hulu", "15");
        add("com.apple.atve.androidtv.appletv", "Apple TV+", "350");
        add("com.wbd.stream", "Max", "1899|384");
        add("com.crunchyroll.crunchyroid", "Crunchyroll", "283");
        add("com.graymatrix.did", "ZEE5", "232");
        add("com.tubitv", "Tubi", "73");
        add("com.plexapp.android", "Plex", "538");
        add("com.google.android.youtube.tv", "YouTube", "192|188");
    }

    private Services() {
    }

    static String name(String pkg) {
        Service s = ALL.get(pkg);
        return s == null ? pkg : s.name;
    }
}
