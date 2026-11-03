package com.tvlatest;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explore: the latest titles from TMDB in mood rows ("Edge of your seat", "Feel-good" ...) instead of genres, each a
 * carousel of whole, uncropped posters with its own neon accent, on charcoal. The focused title fills a compact hero.
 * The left rail is shared with Home (the launcher): Home goes back there.
 *
 * OK asks Google TV for "Watch <title> on <service>" (its title page, that service's button focused); holding OK
 * opens the service's app.
 *
 * Setup over adb (also how the regions and languages are changed):
 *   am start -n com.tvlatest/.LatestActivity --es key YOUR_TMDB_KEY --es regions US,IN --es langs ta,te,hi,en
 */
public class LatestActivity extends Activity {
    private static final String BACKDROP_SIZE = "w1280";
    /** Card art: the 16:9 backdrop at a card's size. */
    private static final String CARD_SIZE = "w300";
    /** Posters in high resolution: w500 is sharp at a card's size on a 4K panel. */
    private static final String POSTER_SIZE = "w500";
    private static final String LAUNCHER = "com.gathin.tvhome";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(40 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final ContentObserver refreshed = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            reload();
        }
    };

    private final List<Moods.Row> rows = new ArrayList<>();
    private final RecyclerView.RecycledViewPool cardPool = new RecyclerView.RecycledViewPool();
    private ImageView backdrop;
    private TextView label, title, meta, overview, status, railHome, railExplore, railPersonalize, btnWatch, btnHide;
    private View header, gistButtons;
    private android.widget.ImageView btnRefresh;
    /** The colour picked in Personalize (#BA0021 until then). */
    private int accent;
    /** The gist is open over the rows for this card. */
    private View gistCard;
    private android.animation.ObjectAnimator spin;
    private boolean refreshPending;
    private final Runnable refreshTimeout = this::stopSpin;
    private RecyclerView rowList;
    private RowsAdapter rowsAdapter;
    private Item heroItem;
    private Runnable pendingBackdrop;
    /** The card focus left for the rail, so Right comes back to it. */
    private View lastCard;
    private int posterW, posterH;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_latest);
        density = getResources().getDisplayMetrics().density;
        backdrop = findViewById(R.id.backdrop);
        label = findViewById(R.id.label);
        title = findViewById(R.id.title);
        meta = findViewById(R.id.meta);
        overview = findViewById(R.id.overview);
        status = findViewById(R.id.status);
        rowList = findViewById(R.id.rows);
        header = findViewById(R.id.header);
        gistButtons = findViewById(R.id.gistButtons);
        btnWatch = findViewById(R.id.btnWatch);
        btnHide = findViewById(R.id.btnHide);
        btnRefresh = findViewById(R.id.btnRefresh);
        accent = Store.accent(this);
        findViewById(R.id.scrim).setBackground(scrim());
        setUpGist();
        setUpRefresh();

        posterH = (int) (210 * density);
        posterW = posterH * 2 / 3;   // TMDB posters are 2:3: the card is the poster's own shape, so nothing is cropped
        cardPool.setMaxRecycledViews(0, 40);

        applySetup(getIntent());
        FetchJob.ensure(this);

        // Rows never scroll themselves to show a focused poster: the focus listener parks the focused row right under
        // the header, the same place every time, so moving along a row moves nothing up or down.
        rowList.setLayoutManager(new LinearLayoutManager(this) {
            @Override
            public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, android.graphics.Rect rect,
                                                         boolean immediate) {
                return false;
            }

            @Override
            public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, android.graphics.Rect rect,
                                                         boolean immediate, boolean focusedChildVisible) {
                return false;
            }
        });
        rowList.setItemAnimator(null);
        rowsAdapter = new RowsAdapter();
        rowList.setAdapter(rowsAdapter);
        setUpRail();
        getContentResolver().registerContentObserver(LatestProvider.TOP, false, refreshed);
        reload();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (applySetup(intent)) reload();
    }

    @Override
    protected void onDestroy() {
        getContentResolver().unregisterContentObserver(refreshed);
        io.shutdownNow();
        super.onDestroy();
    }

    /** The adb setup extras: saved, and a fetch started at once. */
    private boolean applySetup(Intent i) {
        if (i == null || i.getExtras() == null) return false;
        android.content.SharedPreferences.Editor e = Store.prefs(this).edit();
        boolean changed = false;
        for (String k : new String[]{"key", "regions", "langs"}) {
            String v = i.getStringExtra(k);
            if (v != null) {
                e.putString(k, v.trim());
                changed = true;
            }
        }
        if (!changed) return false;
        e.commit();   // on disk before the job reads it
        try {
            FetchJob.now(this);
        } catch (RuntimeException ex) {
            Toast.makeText(this, "Could not schedule the fetch: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            return true;
        }
        Toast.makeText(this, "Saved. Fetching from TMDB…", Toast.LENGTH_SHORT).show();
        return true;
    }

    // ---- rail -------------------------------------------------------------------------------------

    /** Home and Explore, Prime Video style. */
    private void setUpRail() {
        railHome = findViewById(R.id.railHome);
        railExplore = findViewById(R.id.railExplore);
        railPersonalize = findViewById(R.id.railPersonalize);
        View.OnFocusChangeListener names = (v, has) -> main.post(this::updateRailNames);
        railHome.setOnFocusChangeListener(names);
        railExplore.setOnFocusChangeListener(names);
        railPersonalize.setOnFocusChangeListener(names);
        railHome.setOnClickListener(v -> goHome());
        railExplore.setOnClickListener(v -> backToCards());
        railPersonalize.setOnClickListener(v -> openPersonalize());
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                backToCards();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return v == railHome;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return v == railPersonalize;
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
        };
        railHome.setOnKeyListener(keys);
        railExplore.setOnKeyListener(keys);
        railPersonalize.setOnKeyListener(keys);
        // Personalize lives in the launcher: without it, no item.
        railPersonalize.setVisibility(getPackageManager().resolveActivity(personalizeIntent(), 0) != null
                ? View.VISIBLE : View.GONE);
        updateRailNames();
    }

    /** Prime Video style: icons alone on the edge; on focus a navy shade slides in and the names appear. */
    private void updateRailNames() {
        boolean open = railHome.hasFocus() || railExplore.hasFocus() || railPersonalize.hasFocus();
        findViewById(R.id.railShade).animate().alpha(open ? 1f : 0f).setDuration(180).start();
        setRailItem(railHome, open ? "Home" : "", false);
        setRailItem(railExplore, open ? "Explore" : "", true);
        setRailItem(railPersonalize, open ? "Personalize" : "", false);
    }

    /** No boxes: a focused item's icon and name turn #BA0021; this screen's item is white, others grey. */
    private void setRailItem(TextView v, String name, boolean current) {
        v.setText(name);
        int color = v.isFocused() ? accent : current ? 0xFFFFFFFF : 0x80FFFFFF;
        v.setTextColor(color);
        v.setCompoundDrawableTintList(ColorStateList.valueOf(color));
    }

    private void focusRail() {
        View f = getCurrentFocus();
        if (f != null && f != railHome && f != railExplore && f != railPersonalize) lastCard = f;
        railExplore.requestFocus();
    }

    private static final String LAUNCHER_PERSONALIZE = LAUNCHER + ".PersonalizeActivity";

    private Intent personalizeIntent() {
        return new Intent(Intent.ACTION_MAIN).setClassName(LAUNCHER, LAUNCHER_PERSONALIZE);
    }

    private void openPersonalize() {
        try {
            startActivity(personalizeIntent());
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A colour picked in Personalize meanwhile.
        int now = Store.accent(this);
        if (now != accent) {
            accent = now;
            updateRailNames();
            styleButton(btnWatch, btnWatch.isFocused());
            styleButton(btnHide, btnHide.isFocused());
            rowsAdapter.notifyDataSetChanged();
        }
    }

    private void backToCards() {
        if (gistCard != null) {
            closeGist(true);
            return;
        }
        if (lastCard != null && lastCard.isAttachedToWindow()) {
            lastCard.requestFocus();
            return;
        }
        focusFirstCard();
    }

    /** Back to the launcher's Home. Explore is its own app, so this is a plain start of the launcher. */
    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).setClassName(LAUNCHER, LAUNCHER + ".MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // No launcher: Back is still there.
        }
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (gistCard != null) closeGist(true);
            else goHome();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- data -------------------------------------------------------------------------------------

    private void reload() {
        io.execute(() -> {
            List<Item> all = Store.load(this);
            java.util.Set<String> hidden = Store.hidden(this);
            all.removeIf(i -> hidden.contains(i.key()));
            List<Moods.Row> next = Moods.rows(all);
            main.post(() -> {
                if (refreshPending && !rows.isEmpty()) {
                    // Refresh: the screen goes out one way and the new titles come in, a different way each time.
                    refreshPending = false;
                    stopSpin();
                    heroItem = null;
                    wipe(() -> apply(next));
                    return;
                }
                if (refreshPending) stopSpin();
                apply(next);
            });
        });
    }

    private void apply(List<Moods.Row> next) {
        // Where focus was: a poster (or the one a removal came from). The same row and place get it back afterwards,
        // now holding the poster that slid into it, instead of jumping to the first one.
        View focused = pendingPlace != null ? pendingPlace : getCurrentFocus();
        pendingPlace = null;
        int[] place = focused != null && focused.getId() == R.id.card ? placeOf(focused) : null;
        rows.clear();
        rows.addAll(next);
        rowsAdapter.notifyDataSetChanged();
        if (place != null) {
            lastCard = null;
            rowList.post(() -> focusCardAt(place[0], place[1]));
        }
        long t = Store.updated(this);
        label.setText(("Explore  ·  " + rows.size() + " rows"
                + (t == 0 ? "" : "  ·  updated " + DateUtils.getRelativeTimeSpanString(t,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))).toUpperCase(Locale.ROOT));
        if (rows.isEmpty()) {
            status.setVisibility(View.VISIBLE);
            status.setText(Store.key(this).isEmpty()
                    ? "No TMDB key yet.\nRun tv-latest\\set-tmdb-key.ps1 on the computer."
                    : "Fetching the latest titles from TMDB…");
        } else {
            status.setVisibility(View.GONE);
            if (heroItem == null) showHero(rows.get(0).items.get(0), rows.get(0).accent);
            if (getCurrentFocus() == null) rowList.post(this::focusFirstCard);
        }
    }

    /**
     * Up and Down between rows: the poster straight above or below, not whichever the focus finder thinks is nearest
     * (rows scroll on their own, so that was often a different column).
     */
    private void focusRowNear(int row, int screenX) {
        if (row < 0 || row >= rows.size()) return;
        RecyclerView.ViewHolder vh = rowList.findViewHolderForAdapterPosition(row);
        if (vh == null) {
            rowList.scrollToPosition(row);
            rowList.post(() -> {
                if (rowList.findViewHolderForAdapterPosition(row) != null) focusRowNear(row, screenX);
            });
            return;
        }
        RecyclerView inner = vh.itemView.findViewById(R.id.rowList);
        View best = null;
        int bestDx = Integer.MAX_VALUE;
        int[] at = new int[2];
        for (int k = 0; k < inner.getChildCount(); k++) {
            View c = inner.getChildAt(k);
            c.getLocationOnScreen(at);
            int dx = Math.abs(at[0] + c.getWidth() / 2 - screenX);
            if (dx < bestDx) {
                bestDx = dx;
                best = c;
            }
        }
        if (best != null) best.requestFocus();
    }

    /** A poster removed with Not interested or hold OK: where focus goes back to once the rows are rebuilt. */
    private View pendingPlace;

    /** {row, position in the row} of a poster on screen, or null. */
    private int[] placeOf(View card) {
        View rowView = (View) card.getParent().getParent();
        RecyclerView inner = (RecyclerView) card.getParent();
        int row = rowList.getChildAdapterPosition(rowView), pos = inner.getChildAdapterPosition(card);
        return row < 0 || pos < 0 ? null : new int[]{row, pos};
    }

    /** Focuses the poster at that place, or the nearest one left (a row that got shorter, or fewer rows). */
    private void focusCardAt(int row, int pos) {
        if (rows.isEmpty()) return;
        int r = Math.min(row, rows.size() - 1);
        RecyclerView.ViewHolder vh = rowList.findViewHolderForAdapterPosition(r);
        if (vh == null) {
            rowList.scrollToPosition(r);
            rowList.post(() -> {
                if (rowList.findViewHolderForAdapterPosition(r) != null) focusCardAt(r, pos);
            });
            return;
        }
        RecyclerView inner = vh.itemView.findViewById(R.id.rowList);
        int count = inner.getAdapter() == null ? 0 : inner.getAdapter().getItemCount();
        if (count == 0) return;
        int p = Math.min(pos, count - 1);
        RecyclerView.ViewHolder card = inner.findViewHolderForAdapterPosition(p);
        if (card != null) {
            card.itemView.requestFocus();
            return;
        }
        inner.scrollToPosition(p);
        inner.post(() -> {
            RecyclerView.ViewHolder c = inner.findViewHolderForAdapterPosition(p);
            if (c != null) c.itemView.requestFocus();
        });
    }

    private void focusFirstCard() {
        RecyclerView.ViewHolder vh = rowList.findViewHolderForAdapterPosition(0);
        if (vh == null) return;
        RecyclerView inner = vh.itemView.findViewById(R.id.rowList);
        RecyclerView.ViewHolder card = inner.findViewHolderForAdapterPosition(0);
        if (card != null) card.itemView.requestFocus();
    }

    // ---- hero -------------------------------------------------------------------------------------

    private void showHero(Item i, int accent) {
        heroItem = i;
        title.setText(i.title);
        meta.setText(metaLine(i));
        overview.setText(i.overview);
        String path = i.backdrop;
        if (pendingBackdrop != null) main.removeCallbacks(pendingBackdrop);
        // Wait a moment so moving across a row doesn't download every backdrop on the way.
        pendingBackdrop = () -> io.execute(() -> {
            Bitmap b = Images.decode(Images.fetch(this, BACKDROP_SIZE, path), 1280);
            main.post(() -> {
                if (heroItem != i) return;
                if (b == null) {
                    backdrop.animate().alpha(0f).setDuration(200).start();
                    return;
                }
                backdrop.animate().cancel();
                backdrop.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                    backdrop.setImageBitmap(b);
                    backdrop.animate().alpha(1f).setDuration(380).start();
                }).start();
            });
        });
        main.postDelayed(pendingBackdrop, 260);
    }

    /** "Tamil  ·  Netflix  ·  ★ 7.8": the language, where to watch it (white) and TMDB's rating. */
    private CharSequence metaLine(Item i) {
        StringBuilder m = new StringBuilder();
        String lang = i.languageName();
        if (!lang.isEmpty()) m.append(lang).append("  ·  ");
        int start = m.length();
        m.append(i.services());
        int end = m.length();
        String r = i.ratingText();
        if (!r.isEmpty()) m.append("  ·  ★ ").append(r);
        android.text.SpannableString styled = new android.text.SpannableString(m);
        styled.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFFFFF), start, end, 0);
        styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, 0);
        return styled;
    }

    // ---- gist ---------------------------------------------------------------------------------------

    private static final int HEADER_DP = 250, GIST_DP = 380;
    private static final int OVERVIEW_LINES = 3, GIST_OVERVIEW_LINES = 7;

    /** OK on a poster: the hero grows in place to show the summary, Watch now and Not interested. */
    private void setUpGist() {
        View.OnFocusChangeListener style = (v, has) -> styleButton((TextView) v, has);
        btnWatch.setOnFocusChangeListener(style);
        btnHide.setOnFocusChangeListener(style);
        styleButton(btnWatch, false);
        styleButton(btnHide, false);
        btnWatch.setOnClickListener(v -> {
            if (heroItem != null) open(heroItem, false);
        });
        btnHide.setOnClickListener(v -> {
            if (heroItem != null) hide(heroItem);
        });
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_UP:
                    closeGist(true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (v == btnHide) btnWatch.requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (v == btnWatch) btnHide.requestFocus();
                    return true;
                default:
                    return false;
            }
        };
        btnWatch.setOnKeyListener(keys);
        btnHide.setOnKeyListener(keys);
    }

    private void styleButton(TextView b, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(4 * density);   // highlights are rectangles
        bg.setColor(focused ? accent : 0x33FFFFFF);
        b.setBackground(bg);
        b.setTextColor(focused && luminance(accent) > 0.6 ? 0xFF000000 : 0xFFFFFFFF);
        b.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(140).start();
    }

    /** Light colours (white, ice blue, lemon) take black text. */
    private static double luminance(int c) {
        return (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF)) / 255.0;
    }

    private void openGist(View card) {
        gistCard = card;
        overview.setMaxLines(GIST_OVERVIEW_LINES);   // the whole description in the gist
        gistButtons.setVisibility(View.VISIBLE);
        gistButtons.setAlpha(0f);
        animateHeader(GIST_DP);
        gistButtons.animate().alpha(1f).setStartDelay(160).setDuration(260).start();
        rowList.animate().alpha(0.25f).setDuration(240).start();
        btnWatch.requestFocus();
    }

    private void closeGist(boolean refocus) {
        View card = gistCard;
        gistCard = null;
        animateHeader(HEADER_DP);
        gistButtons.animate().cancel();
        overview.setMaxLines(OVERVIEW_LINES);
        gistButtons.setVisibility(View.GONE);
        rowList.animate().alpha(1f).setDuration(240).start();
        if (refocus && card != null && card.isAttachedToWindow()) card.requestFocus();
        else if (refocus) focusFirstCard();
    }

    private void animateHeader(int dp) {
        ViewGroup.LayoutParams lp = header.getLayoutParams();
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(lp.height, (int) (dp * density));
        a.setDuration(260);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator());
        a.addUpdateListener(v -> {
            lp.height = (int) v.getAnimatedValue();
            header.setLayoutParams(lp);
        });
        a.start();
    }

    /** Long press, or Not interested: this title never shows again, here or behind Home. */
    private void hide(Item i) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Not interested in “" + i.title + "”?")
                .setMessage("It won't show in Explore or on Home again.")
                .setPositiveButton("Remove", (d, w) -> {
                    pendingPlace = gistCard != null ? gistCard : getCurrentFocus();
                    Store.hide(this, i.key());
                    if (gistCard != null) closeGist(false);
                    lastCard = null;
                    getContentResolver().notifyChange(LatestProvider.TOP, null);
                })
                .setNegativeButton("Cancel", null)
                .show()
                // Cancel first: a held OK that lands here must not remove anything.
                .getButton(android.content.DialogInterface.BUTTON_NEGATIVE).requestFocus();
    }

    // ---- refresh ------------------------------------------------------------------------------------

    private void setUpRefresh() {
        btnRefresh.setOnFocusChangeListener((v, has) -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(4 * density);   // a rectangle, like every highlight: only its border lights up
            bg.setColor(0x00000000);
            bg.setStroke(Math.round(2 * density), has ? accent : 0x00000000);
            v.setBackground(bg);
        });
        btnRefresh.setOnClickListener(v -> refresh());
        btnRefresh.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                backToCards();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                railExplore.requestFocus();
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
    }

    private void refresh() {
        if (Store.key(this).isEmpty()) {
            Toast.makeText(this, "No TMDB key yet", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshPending = true;
        FetchJob.refreshNow(this);   // false while one is already running: its result comes the same way
        if (spin == null) {
            spin = android.animation.ObjectAnimator.ofFloat(btnRefresh, "rotation", 0f, 360f);
            spin.setDuration(900);
            spin.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            spin.setInterpolator(new android.view.animation.LinearInterpolator());
        }
        if (!spin.isRunning()) spin.start();
        main.removeCallbacks(refreshTimeout);
        main.postDelayed(refreshTimeout, 60_000);
    }

    private View wipePanel;
    /** The wipe panel's width in screens: its black middle (7/11 of it) must be wider than the screen. */
    private static final float WIPE_WIDTH = 1.65f;

    /**
     * Refresh's hand-over, the same as on Home: a black panel with a soft edge in the theme colour sweeps in from the
     * left until it covers the screen, the new titles go in behind it, and it carries on off to the right.
     */
    private void wipe(Runnable swap) {
        ViewGroup root = findViewById(android.R.id.content);
        int w = root.getWidth();
        if (wipePanel == null) {
            wipePanel = new View(this);
            int glow = (accent & 0x00FFFFFF) | 0x99000000, k = 0xFF000000;
            wipePanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{
                    0x00000000, glow, k, k, k, k, k, k, k, k, glow, 0x00000000}));
            wipePanel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(wipePanel, new android.widget.FrameLayout.LayoutParams((int) (w * WIPE_WIDTH),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        wipePanel.bringToFront();
        wipePanel.animate().cancel();
        wipePanel.setVisibility(View.VISIBLE);
        float panel = w * WIPE_WIDTH;
        wipePanel.setTranslationX(-panel);
        wipePanel.animate().translationX(-panel * 2f / 11f).setDuration(420).setStartDelay(0)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    swap.run();
                    wipePanel.animate().translationX(w).setStartDelay(260).setDuration(560)
                            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                            .withEndAction(() -> wipePanel.setVisibility(View.GONE)).start();
                }).start();
    }

    /** A focused poster's ring: the theme colour, the poster's own 6dp corners. */
    private android.graphics.drawable.Drawable ring() {
        GradientDrawable r = new GradientDrawable();
        r.setCornerRadius(6 * density);
        r.setStroke(Math.round(2.5f * density), accent);
        return r;
    }

    private void stopSpin() {
        main.removeCallbacks(refreshTimeout);
        refreshPending = false;
        if (spin != null) spin.cancel();
        btnRefresh.animate().rotation(0f).setDuration(200).start();
    }

    private static String prettyDate(String iso) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso);
            return d == null ? "" : new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(d);
        } catch (Exception e) {
            return "";
        }
    }

    /** Prime Video style: the hero art fades into navy on the text side and towards the rows. */
    private BitmapDrawable scrim() {
        int w = 480, h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, w, 0, new int[]{0xFF000000, 0xE6000000, 0x00000000},
                new float[]{0f, 0.38f, 0.7f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x00000000, 0xF2000000, 0xFF000000},
                new float[]{0.25f, 0.55f, 0.7f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        BitmapDrawable d = new BitmapDrawable(getResources(), bmp);
        d.setGravity(Gravity.FILL);
        return d;
    }

    // ---- opening ----------------------------------------------------------------------------------

    /** OK: Google TV's search for "<title> <service>". Hold OK (appOnly): the service's app. */
    private void open(Item i, boolean appOnly) {
        if (!appOnly && Opener.direct(this, i.title, i.packages)) return;
        if (!appOnly) {
            Intent search = new Intent("android.search.action.GLOBAL_SEARCH")
                    .putExtra(SearchManager.QUERY, i.packages.isEmpty() ? i.title
                            : i.title + " " + Services.name(i.packages.iterator().next()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(search);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // No search on this TV: open the app instead.
            }
        }
        for (String pkg : i.packages) {
            Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (app == null) app = getPackageManager().getLaunchIntentForPackage(pkg);
            if (app != null) {
                startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        }
        Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show();
    }

    // ---- rows and cards ---------------------------------------------------------------------------

    private final class RowsAdapter extends RecyclerView.Adapter<RowsAdapter.Holder> {
        final class Holder extends RecyclerView.ViewHolder {
            final View dot;
            final TextView name;
            final RecyclerView list;
            final CardsAdapter cards = new CardsAdapter();

            Holder(View v) {
                super(v);
                dot = v.findViewById(R.id.rowDot);
                name = v.findViewById(R.id.rowName);
                list = v.findViewById(R.id.rowList);
                list.setLayoutManager(new LinearLayoutManager(v.getContext(), RecyclerView.HORIZONTAL, false));
                list.setRecycledViewPool(cardPool);
                list.setItemAnimator(null);
                list.setAdapter(cards);
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false));
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            Moods.Row r = rows.get(position);
            h.name.setText(r.name);
            h.dot.setVisibility(View.GONE);   // Prime Video style: a plain bold row title
            h.cards.setRow(r);
            h.list.scrollToPosition(0);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private final class CardsAdapter extends RecyclerView.Adapter<CardsAdapter.Holder> {
        private Moods.Row row;

        void setRow(Moods.Row r) {
            row = r;
            notifyDataSetChanged();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView poster;
            final TextView fallback;
            Item item;
            int accent;


            final View bar;
            final View frame;

            Holder(View v) {
                super(v);
                poster = v.findViewById(R.id.poster);
                fallback = v.findViewById(R.id.fallback);
                bar = v.findViewById(R.id.bar);
                frame = v.findViewById(R.id.frame);
                frame.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 6 * density);
                    }
                });
                frame.setClipToOutline(true);
                v.setAlpha(0.65f);
                v.setOnFocusChangeListener((view, has) -> {
                    // No outline: the focused poster grows and brightens, with a short #BA0021 bar under it.
                    view.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).alpha(has ? 1f : 0.65f)
                            .setDuration(160).start();
                    // The highlight: a rectangle in the theme colour round the poster (no bar), as on Home.
                    frame.setForeground(has ? ring() : null);
                    view.setElevation(has ? 6 * density : 0);
                    if (has && item != null) {
                        showHero(item, accent);
                        // Keep the focused row just under the hero.
                        View rowView = (View) view.getParent().getParent();
                        int dy = rowView.getTop() - rowList.getPaddingTop();
                        if (dy != 0) rowList.smoothScrollBy(0, dy);
                    }
                });
                v.setOnClickListener(view -> {
                    if (item == null) return;
                    showHero(item, accent);
                    openGist(view);
                });
                v.setOnLongClickListener(view -> {
                    if (item != null) hide(item);
                    return true;
                });
                v.setOnKeyListener((view, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    int pos = getBindingAdapterPosition();
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0) {
                        focusRail();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        View rowView = (View) view.getParent().getParent();
                        int row = rowList.getChildAdapterPosition(rowView);
                        boolean up = keyCode == KeyEvent.KEYCODE_DPAD_UP;
                        if (up && row == 0) {
                            lastCard = view;
                            btnRefresh.requestFocus();
                            return true;
                        }
                        int[] at = new int[2];
                        view.getLocationOnScreen(at);
                        focusRowNear(row + (up ? -1 : 1), at[0] + view.getWidth() / 2);
                        return true;
                    }
                    return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pos == getItemCount() - 1;
                });
            }
        }


        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_poster, parent, false);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.width = posterW;
            View frame = v.findViewById(R.id.frame);
            frame.getLayoutParams().width = posterW;
            frame.getLayoutParams().height = posterH;
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            Item i = row.items.get(position);
            h.item = i;
            h.accent = row.accent;
            h.frame.setForeground(h.itemView.isFocused() ? ring() : null);
            h.itemView.setAlpha(h.itemView.isFocused() ? 1f : 0.65f);
            h.itemView.setContentDescription(i.title);
            h.fallback.setText(i.title);
            final boolean wide = i.poster == null;   // a poster when there is one; the 16:9 art otherwise
            String path = wide ? i.backdrop : i.poster;
            Bitmap cached = path == null ? null : posters.get(path);
            h.poster.setImageBitmap(cached);
            h.fallback.setVisibility(cached == null ? View.VISIBLE : View.GONE);
            if (cached != null || path == null) return;
            io.execute(() -> {
                File f = Images.fetch(LatestActivity.this, wide ? CARD_SIZE : POSTER_SIZE, path);
                Bitmap b = Images.decode(f, posterW);
                if (b == null) return;
                posters.put(path, b);
                main.post(() -> {
                    if (h.item != i) return;
                    h.poster.setImageBitmap(b);
                    h.fallback.setVisibility(View.GONE);
                });
            });
        }

        @Override
        public int getItemCount() {
            return row == null ? 0 : row.items.size();
        }
    }
}
