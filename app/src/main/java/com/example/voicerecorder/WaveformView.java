package com.example.voicerecorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Scrolling bar waveform. Newest sample on the right. */
public class WaveformView extends View {
    private final List<Float> levels = new ArrayList<>();
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float barW, gap;
    private boolean active = false;

    public WaveformView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        barW = 3 * d;
        gap = 2 * d;
        bar.setStrokeCap(Paint.Cap.ROUND);
        bar.setStrokeWidth(barW);
        base.setColor(0xFFE4E2EC);
        base.setStrokeWidth(1 * d);
        setColors(false);
    }

    private void setColors(boolean recording) {
        bar.setColor(recording ? 0xFFE8771E : 0xFF2E2A6B);
    }

    /** level 0..1 */
    public void add(float level) {
        levels.add(Math.max(0.02f, Math.min(1f, level)));
        int max = (int) (getWidth() / (barW + gap)) + 2;
        while (max > 2 && levels.size() > max) levels.remove(0);
        invalidate();
    }

    public void setActive(boolean recording) {
        active = recording;
        setColors(recording);
        invalidate();
    }

    public void clear() {
        levels.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float mid = getHeight() / 2f;
        c.drawLine(0, mid, getWidth(), mid, base);
        float x = getWidth() - barW / 2f;
        for (int i = levels.size() - 1; i >= 0 && x > 0; i--) {
            float h = levels.get(i) * (getHeight() / 2f - barW);
            c.drawLine(x, mid - h, x, mid + h, bar);
            x -= barW + gap;
        }
    }
}
