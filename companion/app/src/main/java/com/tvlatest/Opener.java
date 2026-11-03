package com.tvlatest;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.Collection;

/**
 * Watch now, the fast way when there is one, before Google TV's search (which goes through Gemini and is slow):
 * 1. the title is in Continue watching: that row's own link, which opens it in its app;
 * 2. the app has a search link that takes the words. Tested on the Google TV Streamer (November 2026): YouTube and
 *    Prime Video do (Prime asks "Who's watching?" first when it was closed). Netflix, Hulu, Crunchyroll and Max open
 *    their home or an empty search instead, so they still go through Google TV.
 */
final class Opener {
    private Opener() {
    }

    /** True when the title was opened in its app. */
    static boolean direct(Context c, String title, Collection<String> packages) {
        String want = Store.norm(title);
        for (WatchNext.Entry e : WatchNext.read(c)) {
            if (!Store.norm(e.title).equals(want) || e.intentUri == null) continue;
            if (!packages.isEmpty() && !packages.contains(e.pkg)) continue;
            try {
                Intent i = Intent.parseUri(e.intentUri, Intent.URI_INTENT_SCHEME);
                if (i.getPackage() == null && i.getComponent() == null) i.setPackage(e.pkg);
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception ignored) {
                // A link the app no longer takes: try the next way.
            }
        }
        for (String pkg : packages) {
            Intent search = searchLink(pkg, title);
            if (search == null) continue;
            try {
                c.startActivity(search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Not installed after all.
            }
        }
        return false;
    }

    /** The app's own search for these words, or null when its links take none. */
    static Intent searchLink(String pkg, String title) {
        if ("com.google.android.youtube.tv".equals(pkg)) {
            return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query="
                    + Uri.encode(title))).setPackage(pkg);
        }
        if ("com.amazon.amazonvideo.livingroom".equals(pkg)) {
            return new Intent(Intent.ACTION_VIEW, Uri.parse("https://app.primevideo.com/search?phrase="
                    + Uri.encode(title))).setPackage(pkg);
        }
        return null;
    }
}
