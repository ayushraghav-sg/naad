package com.example.voicerecorder;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** App-styled dialogs, bottom sheets and full-screen pages with dark-mode and edge-to-edge support. */
public class NaadDialog {
    public static final int PRIMARY = 0, SECONDARY = 1, DANGER = 2;
    public static final int DIALOG = 0, SHEET = 1, FULL = 2;

    private final Context c;
    private final Dialog dialog;
    private final int kind;
    private final int indigo, indigoAccent, saffron, red, text, muted, line, bg, surface;
    private final LinearLayout outer;   // whole window content
    private final LinearLayout body;    // where content goes
    private FrameLayout fullBarWrap;
    private LinearLayout buttons;
    private TextView fullTitle;
    private final float d;

    public NaadDialog(Context c, boolean sheet) { this(c, sheet ? SHEET : DIALOG); }

    public NaadDialog(Context c, int kind) {
        this.c = c;
        this.kind = kind;
        d = c.getResources().getDisplayMetrics().density;
        indigo = ContextCompat.getColor(c, R.color.indigo);
        indigoAccent = ContextCompat.getColor(c, R.color.indigo_accent);
        saffron = ContextCompat.getColor(c, R.color.saffron);
        red = ContextCompat.getColor(c, R.color.rec_red);
        text = ContextCompat.getColor(c, R.color.text);
        muted = ContextCompat.getColor(c, R.color.muted);
        line = ContextCompat.getColor(c, R.color.line);
        bg = ContextCompat.getColor(c, R.color.bg);
        surface = ContextCompat.getColor(c, R.color.surface);

        dialog = new Dialog(c, kind == FULL ? R.style.Theme_Dhwani : 0);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);

        if (kind == FULL) {
            outer.setBackgroundColor(bg);
            fullBarWrap = new FrameLayout(c);
            fullBarWrap.setBackgroundColor(indigo);

            LinearLayout bar = new LinearLayout(c);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
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
            fullTitle.setTypeface(fullTitle.getTypeface(), Typeface.BOLD);
            fullTitle.setPadding(dp(8), 0, 0, 0);
            bar.addView(fullTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            fullBarWrap.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
            outer.addView(fullBarWrap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ScrollView sv = new ScrollView(c);
            body.setPadding(dp(16), dp(16), dp(16), dp(24));
            sv.addView(body);
            outer.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            body.setPadding(dp(24), dp(kind == SHEET ? 12 : 24), dp(24), dp(16));
            GradientDrawable bgDraw = new GradientDrawable();
            bgDraw.setColor(surface);
            float r = dp(24);
            bgDraw.setCornerRadii(kind == SHEET
                    ? new float[]{r, r, r, r, 0, 0, 0, 0}
                    : new float[]{r, r, r, r, r, r, r, r});
            body.setBackground(bgDraw);
            if (kind == SHEET) {
                View h = new View(c);
                GradientDrawable hb = new GradientDrawable();
                hb.setColor(line);
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
        tv.setTextColor(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
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
        tv.setTextColor(muted);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(0, 0, 0, dp(8));
        body.addView(tv);
        return this;
    }

    public NaadDialog message(String m) {
        TextView tv = new TextView(c);
        tv.setText(m);
        tv.setTextColor(muted);
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
            iv.setColorFilter(danger ? red : indigoAccent);
        }
        row.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(danger ? red : text);
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
        v.setBackgroundColor(line);
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
                buttons.setBackgroundColor(surface);
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
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        if (style == PRIMARY) { g.setColor(saffron); b.setTextColor(Color.WHITE); }
        else if (style == DANGER) { g.setColor(red); b.setTextColor(Color.WHITE); }
        else { g.setColor(Color.TRANSPARENT); b.setTextColor(indigoAccent); }
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

    @SuppressWarnings("deprecation")
    public NaadDialog show() {
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            int width = c.getResources().getDisplayMetrics().widthPixels;
            if (kind == FULL) {
                WindowCompat.setDecorFitsSystemWindows(w, false);
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    w.setStatusBarColor(indigo);
                }
                WindowInsetsControllerCompat ic = WindowCompat.getInsetsController(w, w.getDecorView());
                if (ic != null) ic.setAppearanceLightStatusBars(false);
                w.setWindowAnimations(android.R.style.Animation_Activity);

                ViewCompat.setOnApplyWindowInsetsListener(outer, (v, insets) -> {
                    Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
                    if (fullBarWrap != null) {
                        fullBarWrap.setPadding(sys.left, sys.top, sys.right, 0);
                    }
                    if (buttons != null) {
                        buttons.setPadding(dp(16) + sys.left, dp(12), dp(16) + sys.right, dp(12) + sys.bottom);
                    } else {
                        outer.setPadding(0, 0, 0, sys.bottom);
                    }
                    return insets;
                });
            } else {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDimAmount(0.45f);
                if (kind == SHEET) {
                    w.setGravity(Gravity.BOTTOM);
                    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    w.setWindowAnimations(android.R.style.Animation_InputMethod);
                    ViewCompat.setOnApplyWindowInsetsListener(outer, (v, insets) -> {
                        Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
                        body.setPadding(dp(24), dp(12), dp(24), dp(16) + nav.bottom);
                        return insets;
                    });
                } else {
                    w.setLayout(Math.min(width - dp(32), dp(480)), ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            }
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return this;
    }
}
