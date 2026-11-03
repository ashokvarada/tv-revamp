package com.tvlatest;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.util.ArrayList;
import java.util.List;

/**
 * Android's "Continue watching" list (Watch Next), which streaming apps publish to the TV provider. Needs
 * READ_TV_LISTINGS (granted over adb); without ACCESS_ALL_EPG_DATA the provider returns the rows apps mark
 * searchable, which is what Google TV's own row shows. docs/watch-next.md in cleanyourtv has the details.
 */
final class WatchNext {
    static final class Entry {
        long id;
        String pkg, title, subtitle, poster, intentUri;
        float progress;     // 0..1, or -1 when the app gives no position
        long engaged;
        /** An episode of a series (it has a season or episode number), not a film. */
        boolean episode;
    }

    /** The key a removed row is remembered by: a series as a whole, a film by itself. */
    static String key(Entry e) {
        return "w:" + Store.norm(e.title);
    }

    /**
     * "S2 E60 · Title". Plex files films as season 0, episode -1, with the film's own name as the episode: those are
     * films, with no line.
     */
    private static void describe(Entry e, String episode, String season, String number) {
        boolean film = (number == null || number.startsWith("-")) && ("0".equals(season) || season == null)
                && (episode == null || episode.equals(e.title));
        if (film) {
            e.subtitle = "";
            return;
        }
        StringBuilder sub = new StringBuilder();
        if (season != null && !season.isEmpty()) sub.append('S').append(season);
        if (number != null && !number.isEmpty()) sub.append(sub.length() > 0 ? " " : "").append('E').append(number);
        if (episode != null && !episode.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(episode);
        e.subtitle = sub.toString();
        e.episode = (season != null && !season.isEmpty()) || (number != null && !number.isEmpty());
    }

    /** Named columns: some builds' default projection names columns their table lacks (watch-next.md). */
    private static final String[] COLUMNS = {
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
    };
    /** Rows nobody touched for this long are stale (apps leave them behind). */
    private static final long MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000;

    private WatchNext() {
    }

    /** Diagnostics: every preview channel visible to this app, with its programme count and a sample title. */
    static android.database.MatrixCursor probe(Context c) {
        android.database.MatrixCursor out = new android.database.MatrixCursor(
                new String[]{"package", "channel", "browsable", "programs", "with_position", "sample"});
        java.util.Map<Long, Object[]> rows = new java.util.LinkedHashMap<>();
        try (Cursor ch = c.getContentResolver().query(TvContract.Channels.CONTENT_URI, new String[]{
                TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_BROWSABLE}, null, null, null)) {
            while (ch != null && ch.moveToNext()) {
                rows.put(ch.getLong(0), new Object[]{ch.getString(1), ch.getString(2), ch.getInt(3), 0, 0, ""});
            }
        } catch (RuntimeException e) {
            out.addRow(new Object[]{"error", e.toString(), 0, 0, 0, ""});
        }
        try (Cursor p = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, new String[]{
                TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS}, null, null, null)) {
            while (p != null && p.moveToNext()) {
                Object[] r = rows.get(p.getLong(0));
                if (r == null) continue;
                r[3] = (Integer) r[3] + 1;
                if (!p.isNull(2) && p.getLong(2) > 0) r[4] = (Integer) r[4] + 1;
                if (((String) r[5]).isEmpty() && p.getString(1) != null) r[5] = p.getString(1);
            }
        } catch (RuntimeException e) {
            out.addRow(new Object[]{"error", e.toString(), 0, 0, 0, ""});
        }
        for (Object[] r : rows.values()) out.addRow(r);
        return out;
    }

    /** Unfinished titles, most recently watched first. Empty without the permission or any rows. */
    static List<Entry> read(Context c) {
        List<Entry> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, COLUMNS,
                null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                Entry e = new Entry();
                e.id = cur.getLong(0);
                e.pkg = cur.getString(1);
                e.title = cur.getString(2);
                describe(e, cur.getString(3), cur.getString(4), cur.getString(5));
                e.poster = cur.getString(6) != null ? cur.getString(6) : cur.getString(7);
                e.intentUri = cur.getString(8);
                long pos = cur.isNull(9) ? 0 : cur.getLong(9), dur = cur.isNull(10) ? 0 : cur.getLong(10);
                e.progress = pos > 0 && dur > 0 ? Math.min(1f, (float) pos / dur) : -1f;
                e.engaged = cur.isNull(11) ? 0 : cur.getLong(11);
                if (e.pkg == null || e.title == null) continue;
                if (e.progress >= 0.95f) continue;                       // finished
                if (e.engaged > 0 && now - e.engaged > MAX_AGE_MS) continue;
                out.add(e);
            }
        } catch (RuntimeException ignored) {
            // No READ_TV_LISTINGS, or a provider without the table.
        }
        // Some apps (Plex) keep part-watched titles in their preview channels instead: a position there means the
        // same thing. Negative ids keep them apart from Watch Next ids for watching_image.
        try (Cursor cur = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, PREVIEW_COLUMNS,
                null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                long pos = cur.isNull(9) ? 0 : cur.getLong(9), dur = cur.isNull(10) ? 0 : cur.getLong(10);
                if (pos <= 0 || dur <= 0) continue;
                Entry e = new Entry();
                e.id = -cur.getLong(0);
                e.pkg = cur.getString(1);
                e.title = cur.getString(2);
                describe(e, cur.getString(3), cur.getString(4), cur.getString(5));
                e.poster = cur.getString(6) != null ? cur.getString(6) : cur.getString(7);
                e.intentUri = cur.getString(8);
                e.progress = Math.min(1f, (float) pos / dur);
                if (e.pkg == null || e.title == null || e.progress >= 0.95f) continue;
                boolean dup = false;
                for (Entry o : out) dup |= o.pkg.equals(e.pkg) && o.title.equals(e.title);
                if (!dup) out.add(e);
            }
        } catch (RuntimeException ignored) {
            // No preview programs table, or no permission.
        }
        out.sort((a, b) -> Long.compare(b.engaged, a.engaged));
        // A series shows once, at its most recent episode (the list is newest first), whichever app it is in; films
        // all stay. Removed rows (long press) never come back.
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> hidden = Store.hidden(c);
        out.removeIf(e -> hidden.contains(key(e)) || e.episode && !seen.add(Store.norm(e.title)));
        return out;
    }

    private static final String[] PREVIEW_COLUMNS = {
            TvContract.PreviewPrograms._ID,
            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
    };
}
