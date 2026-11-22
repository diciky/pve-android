package com.pvebuddy.android.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 公共 UI 工具：8dp 网格、卡片、按钮、输入行。全部程序化构建。 */
public final class UiKit {

    public static final int ACCENT = 0xFF0F6CBD;
    public static final int ACCENT_DARK = 0xFF0B5394;
    public static final int CARD_BG = 0xFFFFFFFF;
    public static final int PAGE_BG = 0xFFF5F6F8;
    public static final int TEXT_MAIN = 0xFF1A1A2E;
    public static final int TEXT_SUB = 0xFF6B7280;
    public static final int GREEN = 0xFF16A34A;
    public static final int RED = 0xFFDC2626;
    public static final int ORANGE = 0xFFF59E0B;
    public static final int TERMINAL_BG = 0xFF0C0C14;

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(dp(c, 12));
        card.setBackground(bg);
        return card;
    }

    public static LinearLayout.LayoutParams cardParams(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        return lp;
    }

    public static TextView text(Context c, String s, float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return tv;
    }

    public static TextView caption(Context c, String s) {
        return text(c, s, 12, TEXT_SUB, false);
    }

    public static Button button(Context c, String label, int bg, int fg, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextColor(fg);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackground(roundRect(c, bg, dp(c, 10)));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 40));
        b.setLayoutParams(lp);
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        return b;
    }

    public static GradientDrawable roundRect(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp);
        return d;
    }

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(14);
        e.setTextColor(TEXT_MAIN);
        e.setHintTextColor(TEXT_SUB);
        e.setBackground(roundRect(c, PAGE_BG, dp(c, 8)));
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        return e;
    }

    public static TextView chip(Context c, String label, int color) {
        TextView t = text(c, label, 11, Color.WHITE, true);
        t.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
        t.setBackground(roundRect(c, color, dp(c, 8)));
        return t;
    }

    public static LinearLayout row(Context c, View... children) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        for (View v : children) l.addView(v);
        return l;
    }

    /** 状态文本颜色：running 绿 / stopped 红 / paused 橙 */
    public static int statusColor(String status) {
        if (status == null) return TEXT_SUB;
        switch (status) {
            case "running": case "online": case "OK": return GREEN;
            case "paused": case "suspended": return ORANGE;
            case "stopped": case "offline": return RED;
            default: return TEXT_SUB;
        }
    }

    public static String memGB(double bytes) {
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 1 ? String.format(java.util.Locale.US, "%.1f GB", gb)
                : String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024));
    }

    private UiKit() { }
}
