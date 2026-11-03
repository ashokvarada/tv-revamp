package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Local addition (LatestShows): which of an app's titles is behind the screen, Netflix style. One dot per title; the
 * current one is a longer pill that fills up while its time runs, so you can see the next slide coming.
 */
public class RunDots extends View {
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float size, gap, pill;
    private int count, index;
    private float progress = 1f;
    private ValueAnimator run;

    public RunDots(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        size = 6 * d;
        gap = 7 * d;
        pill = 28 * d;
        dot.setColor(0x66FFFFFF);
        track.setColor(0x40FFFFFF);
        fill.setColor(Personal.DEFAULT_ACCENT);
    }

    /** The colour picked in Personalize. */
    void setAccent(int color) {
        fill.setColor(color);
        invalidate();
    }

    /** The titles and which one shows; the current pill is drawn full until run() starts its timer. */
    void set(int count, int index) {
        boolean resize = count != this.count;
        this.count = Math.min(count, 12);
        this.index = index;
        stop();
        if (resize) requestLayout();
        invalidate();
    }

    /** Fills the current pill over ms, in step with the background's timer. */
    void run(long ms) {
        if (run != null) run.cancel();
        progress = 0f;
        run = ValueAnimator.ofFloat(0f, 1f).setDuration(ms);
        run.setInterpolator(new LinearInterpolator());
        run.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        run.start();
    }

    /** No timer (browsing by hand, or paused): the current pill full. */
    void stop() {
        if (run != null) run.cancel();
        run = null;
        progress = 1f;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = count <= 0 ? 0 : (int) Math.ceil(pill + (count - 1) * (size + gap));
        setMeasuredDimension(w, (int) Math.ceil(size));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float x = 0, r = size / 2f;
        for (int i = 0; i < count; i++) {
            if (i == index) {
                rect.set(x, 0, x + pill, size);
                canvas.drawRoundRect(rect, r, r, track);
                rect.set(x, 0, x + Math.max(size, pill * progress), size);
                canvas.drawRoundRect(rect, r, r, fill);
                x += pill + gap;
            } else {
                canvas.drawCircle(x + r, r, r, dot);
                x += size + gap;
            }
        }
    }
}
