package com.pvebuddy.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.pvebuddy.android.core.AppLogger;
import com.pvebuddy.android.core.SshSession;
import com.pvebuddy.android.core.Store;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 终端页：SSH 会话（JSch + PTY），ANSI 基础上色，输入行 + 历史 + 常用指令。
 * 第一版边界：光标定位类转义简化处理，复杂 TUI（vim/htop）渲染可能不全。
 */
public final class PageTerminal {

    private final Activity act;
    private LinearLayout content;
    private TextView termView;
    private ScrollView termScroll;
    private EditText input;
    private TextView connState;
    private Spinner hostSpinner;

    private SshSession session;
    private final AnsiRenderer.State ansiState = new AnsiRenderer.State();
    private final StringBuilder buffer = new StringBuilder();
    private String activeConnKey = "";
    private String[] history = new String[0];
    private int historyIdx = -1;

    PageTerminal(Activity a) { act = a; }

    public View build() {
        if (content == null) {
            content = new LinearLayout(act);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setBackgroundColor(UiKit.TERMINAL_BG);

            // 连接条：主机选择 + 连接/断开
            LinearLayout bar = new LinearLayout(act);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0xFF16161E);
            bar.setPadding(UiKit.dp(act, 8), UiKit.dp(act, 6), UiKit.dp(act, 8), UiKit.dp(act, 6));

            hostSpinner = new Spinner(act);
            hostSpinner.setBackgroundColor(0xFF22222E);
            bar.addView(hostSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            connState = UiKit.text(act, "未连接", 12, UiKit.ORANGE, true);
            connState.setPadding(UiKit.dp(act, 8), 0, 0, 0);
            bar.addView(connState);

            bar.addView(UiKit.button(act, "连接", UiKit.GREEN, Color.WHITE, v -> connect()));
            bar.addView(UiKit.button(act, "断开", 0xFF33333E, Color.WHITE, v -> disconnect()));
            content.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(act, 48)));

            // 终端输出
            termView = new TextView(act);
            termView.setTypeface(Typeface.MONOSPACE);
            termView.setTextSize(11);
            termView.setTextColor(0xFFE0E0E0);
            termView.setBackgroundColor(UiKit.TERMINAL_BG);
            termView.setPadding(UiKit.dp(act, 8), UiKit.dp(act, 8), UiKit.dp(act, 8), UiKit.dp(act, 8));
            termView.setTextIsSelectable(true);
            termScroll = new ScrollView(act);
            termScroll.addView(termView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(termScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // 输入行
            LinearLayout inputBar = new LinearLayout(act);
            inputBar.setOrientation(LinearLayout.HORIZONTAL);
            inputBar.setGravity(Gravity.CENTER_VERTICAL);
            inputBar.setBackgroundColor(0xFF16161E);
            inputBar.setPadding(UiKit.dp(act, 8), UiKit.dp(act, 6), UiKit.dp(act, 8), UiKit.dp(act, 6));

            input = new EditText(act);
            input.setHint("输入命令…");
            input.setTextSize(13);
            input.setTypeface(Typeface.MONOSPACE);
            input.setTextColor(Color.WHITE);
            input.setHintTextColor(0xFF666670);
            input.setBackgroundColor(0xFF22222E);
            input.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 8), UiKit.dp(act, 10), UiKit.dp(act, 8));
            input.setSingleLine(true);
            input.setTextColor(Color.WHITE);
            // 历史上下键：软键盘下用历史按钮替代
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    sendCommand(); return true;
                }
                return false;
            });
            inputBar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            inputBar.addView(smallBtn("⬆", v -> navigateHistory(-1)));
            inputBar.addView(smallBtn("⬇", v -> navigateHistory(1)));
            inputBar.addView(smallBtn(" histories ", v -> showHistoryPanel()));
            inputBar.addView(smallBtn("发送", v -> sendCommand()));
            content.addView(inputBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(act, 50)));

            refreshHostSpinner();
        }
        return content;
    }

    private View smallBtn(String label, View.OnClickListener l) {
        TextView b = UiKit.text(act, label, 13, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(0xFF2A2A36);
        b.setPadding(UiKit.dp(act, 8), 0, UiKit.dp(act, 8), 0);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(act, 36));
        lp.leftMargin = UiKit.dp(act, 4);
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshHostSpinner() {
        JSONArray hosts = Store.get(act).loadSshHosts();
        String[] names = new String[hosts.length()];
        for (int i = 0; i < hosts.length(); i++) {
            JSONObject h = hosts.optJSONObject(i);
            names[i] = h == null ? "?" : h.optString("name") + " (" + h.optString("host") + ")";
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, names);
        hostSpinner.setAdapter(ad);
    }

    public void onShow() { refreshHostSpinner(); }

    private void connect() {
        JSONArray hosts = Store.get(act).loadSshHosts();
        int idx = hostSpinner.getSelectedItemPosition();
        if (hosts.length() == 0 || idx < 0 || idx >= hosts.length()) {
            toast("请先在 设置 → SSH 主机 中添加"); return;
        }
        JSONObject h = hosts.optJSONObject(idx);
        String host = h.optString("host");
        int port = h.optInt("port", 22);
        String user = h.optString("username");
        String secret = Store.get(act).decrypt(h.optString("secretEnc"));
        activeConnKey = h.optString("id");

        disconnect();
        buffer.setLength(0);
        ansiState.fg = 0;
        session = new SshSession();
        connState.post(() -> connState.setText("连接中…"));
        new Thread(() -> {
            try {
                session.connect(host, port, user, secret, null, new SshSession.Listener() {
                    @Override public void onLine(String text) {
                        buffer.append(text);
                        if (buffer.length() > 256 * 1024) {
                            buffer.delete(0, buffer.length() - 128 * 1024);
                        }
                        renderTerm();
                    }
                    @Override public void onState(String state, String detail) {
                        connState.post(() -> {
                            switch (state) {
                                case "connected": connState.setText("已连接"); connState.setTextColor(UiKit.GREEN); break;
                                case "error": connState.setText("错误"); connState.setTextColor(UiKit.RED);
                                    appendSystem("\r\n[连接错误] " + detail + "\r\n"); break;
                                case "closed": connState.setText("已断开"); connState.setTextColor(UiKit.ORANGE); break;
                            }
                        });
                    }
                });
                history = Store.get(act).history(activeConnKey);
                historyIdx = history.length;
            } catch (Exception e) {
                AppLogger.get().error("SSH", "连接失败: " + e);
                connState.post(() -> { connState.setText("失败"); connState.setTextColor(UiKit.RED); });
                appendSystem("\r\n[连接失败] " + e.getMessage() + "\r\n");
            }
        }, "ssh-connect").start();
    }

    private void appendSystem(String text) {
        buffer.append(text);
        renderTerm();
    }

    private void renderTerm() {
        termView.post(() -> {
            SpannableStringBuilder sb = AnsiRenderer.render(buffer.toString(), ansiState);
            int scrollY = termScroll.getScrollY();
            boolean atBottom = termScroll.getChildAt(0) != null &&
                    termScroll.getHeight() + scrollY >= termScroll.getChildAt(0).getHeight() - UiKit.dp(act, 40);
            termView.setText(sb);
            if (atBottom) termScroll.post(() -> termScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void sendCommand() {
        String cmd = input.getText().toString();
        if (session == null || !session.isConnected()) { toast("未连接"); return; }
        session.write(cmd + "\n");
        if (!cmd.isEmpty()) {
            Store.get(act).addHistory(activeConnKey, cmd);
            history = Store.get(act).history(activeConnKey);
        }
        historyIdx = history.length;
        input.setText("");
    }

    private void navigateHistory(int delta) {
        if (history.length == 0) return;
        historyIdx = Math.max(0, Math.min(history.length, historyIdx + delta));
        if (historyIdx < history.length) input.setText(history[historyIdx]);
        else input.setText("");
        input.setSelection(input.getText().length());
    }

    private void showHistoryPanel() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 8), UiKit.dp(act, 16), 0);
        String[] hist = Store.get(act).history(activeConnKey);
        if (hist.length == 0) {
            box.addView(UiKit.caption(act, "暂无历史记录"));
        } else {
            android.widget.ListView lv = new android.widget.ListView(act);
            lv.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_list_item_1, hist));
            lv.setOnItemClickListener((p, v, pos, id) -> {
                input.setText(hist[pos]);
                input.setSelection(hist[pos].length());
            });
            box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(act, 280)));
        }
        new AlertDialog.Builder(act).setTitle("命令历史").setView(box)
                .setPositiveButton("关闭", null).show();
    }

    private void disconnect() {
        if (session != null) { session.close(); session = null; }
    }

    public void shutdown() { disconnect(); }

    private void toast(String s) {
        android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
