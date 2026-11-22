package com.pvebuddy.android.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 基础 ANSI 转义渲染：SGR 颜色/加粗/下划线/重置。
 * 第一版边界：不做光标定位/滚动区域，复杂 TUI（vim/htop）渲染可能不全。
 */
public final class AnsiRenderer {

    private static final int[] BASIC = {
            0xFF000000, 0xFFCD3131, 0xFF0DBC79, 0xFFE5E510,
            0xFF2472C8, 0xFFBC3FBC, 0xFF11A8CD, 0xFFE5E5E5
    };
    private static final int[] BRIGHT = {
            0xFF666666, 0xFFF14C4C, 0xFF23D18B, 0xFFF5F543,
            0xFF3B8EEA, 0xFFD670D6, 0xFF29B8DB, 0xFFFFFFFF
    };

    /** 渲染一段含 ANSI 转义的文本为 Spannable（carryState 保存跨块颜色状态） */
    public static SpannableStringBuilder render(String text, State carry) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int fg = carry.fg, bg = carry.bg;
        boolean bold = carry.bold, underline = carry.underline;
        int i = 0, n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == 0x1B && i + 1 < n && text.charAt(i + 1) == '[') {
                int j = i + 2;
                while (j < n && !isTerminator(text.charAt(j))) j++;
                if (j < n) {
                    String params = text.substring(i + 2, j);
                    char term = text.charAt(j);
                    if (term == 'm') {
                        int[] r = applySgr(params, fg, bg, bold, underline);
                        fg = r[0]; bg = r[1]; bold = r[2] == 1; underline = r[3] == 1;
                    } else if (term == 'A' || term == 'B' || term == 'C' || term == 'D'
                            || term == 'J' || term == 'K' || term == 'H' || term == 'r') {
                        // 光标/清屏类：第一版简化为忽略（文本仍追加）
                    }
                    i = j + 1;
                    continue;
                }
            }
            // CR 处理：\r\n 保留，孤立 \r 忽略（重绘行）
            if (ch == '\r') {
                if (i + 1 < n && text.charAt(i + 1) == '\n') { sb.append('\n'); i += 2; continue; }
                i++; continue;
            }
            sb.append(ch);
            if (ch == '\n') { /* 行结束，颜色状态延续 */ }
            i++;
        }
        carry.fg = fg; carry.bg = bg; carry.bold = bold; carry.underline = underline;
        if (sb.length() > 0) applySpans(sb, 0, sb.length(), fg, bg, bold, underline);
        return sb;
    }

    private static boolean isTerminator(char c) {
        return c >= 0x40 && c <= 0x7E;
    }

    private static void applySpans(SpannableStringBuilder sb, int start, int end,
                                   int fg, int bg, boolean bold, boolean underline) {
        if (fg != 0) sb.setSpan(new ForegroundColorSpan(fg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bg != 0) sb.setSpan(new BackgroundColorSpan(bg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (underline) sb.setSpan(new UnderlineSpanCompat(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 返回 {fg,bg,bold,underline} */
    private static int[] applySgr(String params, int fg, int bg, boolean bold, boolean underline) {
        String[] parts = params.isEmpty() ? new String[]{""} : params.split(";");
        for (String p : parts) {
            int code;
            try { code = p.isEmpty() ? 0 : Integer.parseInt(p); }
            catch (Exception e) { continue; }
            if (code == 0) { fg = 0; bg = 0; bold = false; underline = false; }
            else if (code == 1) bold = true;
            else if (code == 4) underline = true;
            else if (code == 22) bold = false;
            else if (code == 24) underline = false;
            else if (code >= 30 && code <= 37) fg = BASIC[code - 30];
            else if (code == 39) fg = 0;
            else if (code >= 40 && code <= 47) bg = BASIC[code - 40];
            else if (code == 49) bg = 0;
            else if (code >= 90 && code <= 97) fg = BRIGHT[code - 90];
            else if (code >= 100 && code <= 107) bg = BRIGHT[code - 100];
            else if (code == 38 || code == 48) {
                // 256 色/真彩：尝试读取 ;5;n 或 ;2;r;g;b
                int idx = indexOf(parts, p);
                int color = parseExtended(parts, idx);
                if (color != 0) {
                    if (code == 38) fg = color; else bg = color;
                }
            }
        }
        return new int[]{fg, bg, bold ? 1 : 0, underline ? 1 : 0};
    }

    private static int indexOf(String[] arr, String item) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(item)) return i;
        return -1;
    }

    private static int parseExtended(String[] parts, int idx) {
        try {
            String mode = parts[idx + 1];
            if ("5".equals(mode)) {
                int n = Integer.parseInt(parts[idx + 2]);
                if (n < 16) return n < 8 ? BASIC[n] : BRIGHT[n - 8];
                if (n < 232) {
                    n -= 16;
                    int r = (n / 36) * 51, g = ((n % 36) / 6) * 51, b = (n % 6) * 51;
                    return Color.rgb(r, g, b);
                }
                int v = (n - 232) * 10 + 8;
                return Color.rgb(v, v, v);
            } else if ("2".equals(mode) && idx + 4 < parts.length) {
                return Color.rgb(clamp(parts[idx + 2]), clamp(parts[idx + 3]), clamp(parts[idx + 4]));
            }
        } catch (Exception ignore) { }
        return 0;
    }

    private static int clamp(String s) {
        int v = Integer.parseInt(s);
        return Math.max(0, Math.min(255, v));
    }

    static final class UnderlineSpanCompat extends CharacterStyle {
        @Override public void updateDrawState(android.text.TextPaint tp) {
            tp.setUnderlineText(true);
        }
    }

    /** ANSI 状态（跨块延续） */
    public static final class State {
        public int fg = 0, bg = 0;
        public boolean bold, underline;
    }

    private AnsiRenderer() { }
}
