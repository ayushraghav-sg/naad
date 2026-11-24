package com.example.voicerecorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/** Scrolling bar waveform. Newest sample on the right. */
public class WaveformView extends View {
    private final List<Float> levels = new ArrayList<>();
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float barW, gap;
    private final int activeColor, idleColor;

    public WaveformView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        barW = 3 * d;
        gap = 2 * d;
        activeColor = ContextCompat.getColor(c, R.color.saffron);
        idleColor = ContextCompat.getColor(c, R.color.indigo_accent);
        bar.setStrokeCap(Paint.Cap.ROUND);
        bar.setStrokeWidth(barW);
        base.setColor(ContextCompat.getColor(c, R.color.line));
        base.setStrokeWidth(1 * d);
        bar.setColor(idleColor);
    }

    /** level 0..1 */
    public void add(float level) {
        levels.add(Math.max(0.02f, Math.min(1f, level)));
        int max = (int) (getWidth() / (barW + gap)) + 2;
        while (max > 2 && levels.size() > max) levels.remove(0);
        invalidate();
    }

    public void setActive(boolean recording) {
        bar.setColor(recording ? activeColor : idleColor);
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
