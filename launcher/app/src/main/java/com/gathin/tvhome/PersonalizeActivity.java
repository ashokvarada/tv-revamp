package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Local addition: the side bar's Personalize screen. Your name, shown in Home's greeting ("Good morning, Sam"), and
 * the highlight colour, picked from swatches that show the colour itself. Both save at once; Home and Explore pick
 * them up when you go back.
 */
public class PersonalizeActivity extends Activity {
    private float density;
    private int accent;
    private Typeface light, medium;
    private TextView preview, heading;
    private EditText nameField;
    private final List<View> swatches = new ArrayList<>();

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        accent = Personal.accent(this);
        light = UiFont.get(this, R.string.font_light);
        medium = UiFont.get(this, R.string.font_medium);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(72), dp(36), dp(72), dp(48));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        scroll.addView(page);
        root.addView(scroll);

        heading = text("Personalize", 34, 0xFFFFFFFF, light);
        page.addView(heading);
        preview = text("", 13, 0xB3FFFFFF, medium);
        preview.setLetterSpacing(0.2f);
        preview.setAllCaps(true);
        page.addView(preview, margins(0, 10, 0, 0));

        page.addView(label("Your name"), margins(0, 34, 0, 10));
        nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameField.setText(Personal.name(this));
        nameField.setHint("Type your name");
        nameField.setHintTextColor(0x66FFFFFF);
        nameField.setTextColor(0xFFFFFFFF);
        nameField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        nameField.setTypeface(light);
        nameField.setPadding(dp(18), 0, dp(18), 0);
        nameField.setOnFocusChangeListener((v, has) -> {
            styleField(has);
            if (!has) saveName();
        });
        nameField.setOnEditorActionListener((v, actionId, event) -> {
            saveName();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            if (!swatches.isEmpty()) swatches.get(0).requestFocus();
            return true;
        });
        nameField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updatePreview(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        styleField(false);
        page.addView(nameField, new LinearLayout.LayoutParams(dp(380), dp(48)));

        page.addView(label("Continue watching"), margins(0, 34, 0, 4));
        page.addView(text("Show titles watched within", 12, 0x80FFFFFF, light), margins(0, 0, 0, 12));
        LinearLayout ages = new LinearLayout(this);
        ages.setOrientation(LinearLayout.HORIZONTAL);
        ages.setClipChildren(false);
        for (int days : Personal.WATCH_DAYS) ages.addView(ageChoice(days));
        ages.setPadding(dp(4), dp(4), 0, dp(4));   // room for a focused pill to grow
        page.addView(ages);
        refreshAges();

        page.addView(label("Theme colour"), margins(0, 34, 0, 4));
        TextView note = text("Used for every highlight on Home and in Explore.", 12, 0x80FFFFFF, light);
        page.addView(note, margins(0, 0, 0, 6));
        for (int f = 0; f < Personal.FAMILIES.length; f++) {
            TextView family = text(Personal.FAMILIES[f], 11, 0x99FFFFFF, medium);
            family.setLetterSpacing(0.16f);
            family.setAllCaps(true);
            page.addView(family, margins(0, 18, 0, 8));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            for (int k = 0; k < Personal.COLORS[f].length; k++) {
                row.addView(swatch(Personal.COLORS[f][k], Personal.NAMES[f][k]));
            }
            page.addView(row);
        }
        setContentView(root);
        updatePreview(nameField.getText().toString());
        refreshSwatches();
        // Start on the colour in use: the name field opens the keyboard only when pressed.
        View current = null;
        for (View s : swatches) if ((Integer) s.getTag() == accent) current = s;
        View first = current != null ? current : nameField;
        first.post(first::requestFocus);
    }

    @Override
    protected void onPause() {
        saveName();
        super.onPause();
    }

    private void saveName() {
        String n = nameField.getText().toString().trim();
        if (!n.equals(Personal.name(this))) Personal.setName(this, n);
    }

    private void updatePreview(String name) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res = hour < 5 ? R.string.good_night : hour < 12 ? R.string.good_morning
                : hour < 17 ? R.string.good_afternoon : hour < 22 ? R.string.good_evening : R.string.good_night;
        String g = getString(res);
        name = name.trim();
        preview.setText(name.isEmpty() ? g : getString(R.string.greeting_with_name, g, name));
    }

    private void styleField(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0x1FFFFFFF);
        if (focused) bg.setStroke(dp(2), accent);
        nameField.setBackground(bg);
    }

    private final List<TextView> ageChoices = new ArrayList<>();

    /** "7 days", "14 days", "30 days", "All": a pill each, the one in use filled with the theme colour. */
    private TextView ageChoice(int days) {
        TextView t = text(days == 0 ? "All" : days + " days", 15, 0xFFFFFFFF, medium);
        t.setTag(days);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(dp(22), 0, dp(22), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMarginEnd(dp(12));
        t.setLayoutParams(lp);
        t.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
            refreshAges();
        });
        t.setOnClickListener(v -> {
            Personal.setWatchDays(this, days);
            refreshAges();
        });
        ageChoices.add(t);
        return t;
    }

    private void refreshAges() {
        int chosen = Personal.watchDays(this);
        for (TextView t : ageChoices) {
            boolean on = (Integer) t.getTag() == chosen;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(4));   // highlights are rectangles
            g.setColor(on ? accent : 0x1FFFFFFF);
            if (t.isFocused()) g.setStroke(dp(2), 0xFFFFFFFF);
            t.setBackground(g);
            t.setTextColor(on && Personal.light(accent) ? 0xFF000000 : 0xFFFFFFFF);
        }
    }

    /** One colour: a circle of it with its name under it; a ring and a tick on the one in use. */
    private View swatch(int color, String name) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setFocusable(true);
        v.setClickable(true);
        v.setTag(color);
        v.setContentDescription(name);
        TextView dot = new TextView(this);
        dot.setGravity(Gravity.CENTER);
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        dot.setTextColor(Personal.light(color) ? 0xFF000000 : 0xFFFFFFFF);
        v.addView(dot, new LinearLayout.LayoutParams(dp(50), dp(50)));
        TextView caption = text(name, 11, 0x99FFFFFF, light);
        caption.setGravity(Gravity.CENTER);
        caption.setSingleLine(true);
        v.addView(caption, margins(0, 8, 0, 0));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT));
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(140).start();
            caption.setTextColor(has ? 0xFFFFFFFF : 0x99FFFFFF);
        });
        v.setOnClickListener(view -> {
            accent = color;
            Personal.setAccent(this, color);
            refreshSwatches();
            refreshAges();
            styleField(nameField.hasFocus());
        });
        v.setOnKeyListener((view, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                && keyCode == KeyEvent.KEYCODE_DPAD_UP && swatches.indexOf(view) < Personal.COLORS[0].length
                && !ageChoices.isEmpty() && ageChoices.get(0).requestFocus());
        swatches.add(v);
        return v;
    }

    private void refreshSwatches() {
        for (View v : swatches) {
            int color = (Integer) v.getTag();
            TextView dot = (TextView) ((LinearLayout) v).getChildAt(0);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            boolean chosen = color == accent;
            // A dark colour on black needs its outline to be seen at all.
            g.setStroke(dp(chosen ? 3 : 1), chosen ? 0xFFFFFFFF : 0x33FFFFFF);
            dot.setBackground(g);
            dot.setText(chosen ? "✓" : "");
        }
        preview.setTextColor(accent == 0xFF000000 ? 0xB3FFFFFF : accent);
    }

    private TextView label(String s) {
        return text(s, 18, 0xFFFFFFFF, medium);
    }

    private TextView text(String s, float sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
