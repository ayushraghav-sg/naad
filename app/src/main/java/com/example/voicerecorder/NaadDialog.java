package com.example.voicerecorder;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/** App-styled dialogs, bottom sheets and full-screen pages that replace the default Android pop-ups. */
public class NaadDialog {
    public static final int PRIMARY = 0, SECONDARY = 1, DANGER = 2;
    public static final int DIALOG = 0, SHEET = 1, FULL = 2;

    static final int INDIGO = 0xFF2E2A6B, SAFFRON = 0xFFE8771E, RED = 0xFFC8361E,
            TEXT = 0xFF1C1B29, MUTED = 0xFF6B6880, LINE = 0xFFE4E2EC, BG = 0xFFF7F6F2;

    private final Context c;
    private final Dialog dialog;
    private final int kind;
    private final LinearLayout outer;   // whole window content
    private final LinearLayout body;    // where content goes
    private LinearLayout buttons;
    private TextView fullTitle;
    private final float d;

    public NaadDialog(Context c, boolean sheet) { this(c, sheet ? SHEET : DIALOG); }

    public NaadDialog(Context c, int kind) {
        this.c = c;
        this.kind = kind;
        d = c.getResources().getDisplayMetrics().density;
        dialog = new Dialog(c, kind == FULL ? android.R.style.Theme_Material_Light_NoActionBar : 0);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);

        if (kind == FULL) {
            outer.setBackgroundColor(BG);
            LinearLayout bar = new LinearLayout(c);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(INDIGO);
            bar.setPadding(dp(4), 0, dp(16), 0);
            ImageView back = new ImageView(c);
            back.setImageResource(R.drawable.ic_back);
            back.setColorFilter(Color.WHITE);
            back.setScaleType(ImageView.ScaleType.CENTER);
            back.setContentDescription("Back");
            back.setBackground(ripple(true));
            back.setOnClickListener(v -> dialog.dismiss());
            bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
            fullTitle = new TextView(c);
            fullTitle.setTextColor(Color.WHITE);
            fullTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            fullTitle.setTypeface(fullTitle.getTypeface(), android.graphics.Typeface.BOLD);
            fullTitle.setPadding(dp(8), 0, 0, 0);
            bar.addView(fullTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            outer.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

            ScrollView sv = new ScrollView(c);
            body.setPadding(dp(16), dp(16), dp(16), dp(24));
            sv.addView(body);
            outer.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            body.setPadding(dp(24), dp(kind == SHEET ? 12 : 24), dp(24), dp(16));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            float r = dp(24);
            bg.setCornerRadii(kind == SHEET ? new float[]{r, r, r, r, 0, 0, 0, 0} : new float[]{r, r, r, r, r, r, r, r});
            body.setBackground(bg);
            if (kind == SHEET) {
                View h = new View(c);
                GradientDrawable hb = new GradientDrawable();
                hb.setColor(LINE);
                hb.setCornerRadius(dp(2));
                h.setBackground(hb);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(4));
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                lp.bottomMargin = dp(16);
                body.addView(h, lp);
            }
            outer.addView(body);
        }
        dialog.setContentView(outer);
    }

    int dp(int v) { return (int) (v * d + 0.5f); }

    private Drawable ripple(boolean borderless) {
        TypedValue out = new TypedValue();
        c.getTheme().resolveAttribute(borderless ? android.R.attr.selectableItemBackgroundBorderless
                : android.R.attr.selectableItemBackground, out, true);
        return ContextCompat.getDrawable(c, out.resourceId);
    }

    public NaadDialog title(String t) {
        if (kind == FULL) { fullTitle.setText(t); return this; }
        TextView tv = new TextView(c);
        tv.setText(t);
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(0, 0, 0, dp(4));
        body.addView(tv);
        return this;
    }

    /** Small grey line under the title. */
    public NaadDialog subtitle(String t) {
        TextView tv = new TextView(c);
        tv.setText(t);
        tv.setTextColor(MUTED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(0, 0, 0, dp(8));
        body.addView(tv);
        return this;
    }

    public NaadDialog message(String m) {
        TextView tv = new TextView(c);
        tv.setText(m);
        tv.setTextColor(MUTED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setLineSpacing(0, 1.2f);
        tv.setPadding(0, dp(4), 0, dp(8));
        body.addView(tv);
        return this;
    }

    /** Adds a view. scroll = true caps its height and lets it scroll. */
    public NaadDialog view(View v, boolean scroll) {
        if (scroll && kind != FULL) {
            final int max = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.6f);
            ScrollView sv = new ScrollView(c) {
                @Override protected void onMeasure(int w, int h) {
                    super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
                }
            };
            sv.addView(v);
            body.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            body.addView(v, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return this;
    }

    /** Sheet row with an icon. iconRes 0 leaves the icon space empty (keeps labels aligned). */
    public NaadDialog item(String label, int iconRes, boolean danger, Runnable action) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(4), 0, dp(4), 0);
        row.setBackground(ripple(false));
        ImageView iv = new ImageView(c);
        if (iconRes != 0) {
            iv.setImageResource(iconRes);
            iv.setColorFilter(danger ? RED : INDIGO);
        }
        row.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(danger ? RED : TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(20);
        row.addView(tv, lp);
        row.setOnClickListener(v -> { dialog.dismiss(); if (action != null) action.run(); });
        body.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return this;
    }

    public NaadDialog item(String label, boolean danger, Runnable action) {
        return item(label, 0, danger, action);
    }

    /** Thin divider between groups of rows. */
    public NaadDialog divider() {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        body.addView(v, lp);
        return this;
    }

    public NaadDialog button(String label, int style, Runnable action) {
        return button(label, style, false, action);
    }

    public NaadDialog button(String label, int style, boolean keepOpen, Runnable action) {
        if (buttons == null) {
            buttons = new LinearLayout(c);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            buttons.setPadding(kind == FULL ? dp(16) : 0, dp(12), kind == FULL ? dp(16) : 0, kind == FULL ? dp(12) : 0);
            if (kind == FULL) {
                buttons.setBackgroundColor(Color.WHITE);
                outer.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                body.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(44));
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        if (style == PRIMARY) { g.setColor(SAFFRON); b.setTextColor(Color.WHITE); }
        else if (style == DANGER) { g.setColor(RED); b.setTextColor(Color.WHITE); }
        else { g.setColor(Color.TRANSPARENT); b.setTextColor(INDIGO); }
        b.setBackground(g);
        b.setOnClickListener(v -> {
            if (!keepOpen) dialog.dismiss();
            if (action != null) action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        buttons.addView(b, lp);
        return this;
    }

    public NaadDialog onDismiss(Runnable r) {
        dialog.setOnDismissListener(x -> r.run());
        return this;
    }

    public void dismiss() { dialog.dismiss(); }
    public boolean isShowing() { return dialog.isShowing(); }

    public NaadDialog show() {
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            int width = c.getResources().getDisplayMetrics().widthPixels;
            if (kind == FULL) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                w.setStatusBarColor(INDIGO);
                w.setWindowAnimations(android.R.style.Animation_Activity);
            } else {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDimAmount(0.45f);
                if (kind == SHEET) {
                    w.setGravity(Gravity.BOTTOM);
                    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    w.setWindowAnimations(android.R.style.Animation_InputMethod);
                } else {
                    w.setLayout(Math.min(width - dp(32), dp(480)), ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            }
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return this;
    }
}
