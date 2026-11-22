package com.pvebuddy.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.pvebuddy.android.BuildInfo;
import com.pvebuddy.android.core.AppLogger;
import com.pvebuddy.android.core.LocalScanner;
import com.pvebuddy.android.core.PveClient;
import com.pvebuddy.android.core.Store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 设置页：PVE 连接管理（增删改+测试）、SSH 主机、扫描、偏好、诊断日志、关于。 */
public final class PageSettings {

    private final Activity act;
    private LinearLayout content;
    private LinearLayout connsBox, hostsBox;
    private TextView verText;
    /** 返回键消费标记（对话框打开时） */
    private boolean dialogOpen;

    PageSettings(Activity a) { act = a; }

    public View build() {
        if (content == null) {
            content = new LinearLayout(act);
            content.setOrientation(LinearLayout.VERTICAL);
            ScrollView scroll = new ScrollView(act);
            LinearLayout inner = new LinearLayout(act);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setPadding(0, UiKit.dp(act, 8), 0, UiKit.dp(act, 40));
            scroll.addView(inner, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            inner.addView(sectionTitle("PVE 连接"));
            connsBox = new LinearLayout(act);
            connsBox.setOrientation(LinearLayout.VERTICAL);
            inner.addView(connsBox);
            LinearLayout btns = new LinearLayout(act);
            btns.setPadding(UiKit.dp(act, 12), 0, UiKit.dp(act, 12), 0);
            btns.addView(UiKit.button(act, "扫描局域网", UiKit.PAGE_BG, UiKit.ACCENT, v -> showScanner()));
            btns.addView(spacer());
            btns.addView(UiKit.button(act, "手动添加", UiKit.ACCENT, Color.WHITE, v -> showConnForm(null)));
            inner.addView(btns);

            inner.addView(sectionTitle("SSH 主机（终端用）"));
            hostsBox = new LinearLayout(act);
            hostsBox.setOrientation(LinearLayout.VERTICAL);
            inner.addView(hostsBox);
            LinearLayout hb = new LinearLayout(act);
            hb.setPadding(UiKit.dp(act, 12), 0, UiKit.dp(act, 12), 0);
            hb.addView(UiKit.button(act, "添加 SSH 主机", UiKit.ACCENT, Color.WHITE, v -> showHostForm(null)));
            inner.addView(hb);

            inner.addView(sectionTitle("偏好"));
            LinearLayout pref = UiKit.card(act);
            addToggle(pref, "仪表盘自动刷新（5 秒）", "dashboard_auto_refresh");
            inner.addView(pref, UiKit.cardParams(act));

            inner.addView(sectionTitle("诊断"));
            LinearLayout diag = UiKit.card(act);
            diag.addView(UiKit.button(act, "查看 App 诊断日志", UiKit.PAGE_BG, UiKit.ACCENT, v -> showLogs()));
            diag.addView(UiKit.caption(act, "记录 API/SSH/扫描等事件，可导出排查问题（不含密码）"));
            inner.addView(diag, UiKit.cardParams(act));

            inner.addView(sectionTitle("关于"));
            LinearLayout about = UiKit.card(act);
            verText = UiKit.caption(act, "版本 " + BuildInfo.displayVersion());
            about.addView(verText);
            about.addView(UiKit.caption(act, BuildInfo.RELEASE_NOTES));
            about.addView(UiKit.button(act, "历史版本变更", UiKit.PAGE_BG, UiKit.ACCENT, v -> showChangelog()));
            inner.addView(about, UiKit.cardParams(act));

            content.addView(scroll, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        renderConns();
        renderHosts();
        return content;
    }

    private View sectionTitle(String t) {
        TextView v = UiKit.text(act, t, 16, UiKit.TEXT_MAIN, true);
        v.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 16), 0, UiKit.dp(act, 4));
        return v;
    }

    private View spacer() {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(act, 8), 1));
        return v;
    }

    private void addToggle(LinearLayout box, String label, String key) {
        android.widget.Switch sw = new android.widget.Switch(act);
        sw.setText(label);
        sw.setChecked(Store.get(act).bool(key, key.equals("dashboard_auto_refresh")));
        sw.setOnCheckedChangeListener((b, v) -> Store.get(act).put(key, v));
        sw.setPadding(0, UiKit.dp(act, 8), 0, UiKit.dp(act, 8));
        box.addView(sw);
    }

    // ---- PVE 连接 ----

    private void renderConns() {
        if (connsBox == null) return;
        connsBox.removeAllViews();
        JSONArray conns = Store.get(act).loadConnections();
        MainActivity main = (MainActivity) act;
        for (int i = 0; i < conns.length(); i++) {
            JSONObject c = conns.optJSONObject(i);
            if (c == null) continue;
            boolean active = c.optString("id").equals(main.store.str("last_conn", ""));
            LinearLayout card = UiKit.card(act);
            LinearLayout row = UiKit.row(act,
                    UiKit.text(act, c.optString("name"), 15, UiKit.TEXT_MAIN, true),
                    UiKit.caption(act, c.optString("username") + " @ " + c.optString("host") + ":" + c.optInt("port", 8006)));
            if (active) {
                TextView chip = UiKit.chip(act, "当前", UiKit.GREEN);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.leftMargin = UiKit.dp(act, 8);
                chip.setLayoutParams(clp);
                row.addView(chip);
            }
            row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(UiKit.button(act, "编辑", UiKit.PAGE_BG, UiKit.ACCENT, v -> showConnForm(c)));
            final int delIdx = i;
            row.addView(UiKit.button(act, "删除", 0xFFFEE2E2, UiKit.RED, v -> {
                JSONArray n = new JSONArray();
                for (int j = 0; j < conns.length(); j++) if (j != delIdx) n.put(conns.opt(j));
                Store.get(act).saveConnections(n);
                if (active) { main.currentConn = null; main.client = null; }
                renderConns();
            }));
            card.addView(row);
            // 点击卡片 = 切换当前连接
            card.setOnClickListener(v -> {
                try {
                    main.setCurrentConn(c);
                    AppBridge.client = main.client;
                    renderConns();
                    toast("已切换到 " + c.optString("name"));
                } catch (Exception e) { toast("切换失败"); }
            });
            connsBox.addView(card, UiKit.cardParams(act));
        }
        if (conns.length() == 0) {
            connsBox.addView(UiKit.caption(act, "  尚无连接。扫描或手动添加以开始。"));
        }
    }

    private void showConnForm(JSONObject existing) {
        dialogOpen = true;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 8), UiKit.dp(act, 16), 0);
        EditText name = UiKit.input(act, "名称（如：家里 PVE）");
        EditText host = UiKit.input(act, "主机地址");
        EditText port = UiKit.input(act, "端口（默认 8006）");
        EditText user = UiKit.input(act, "用户（root@pam）");
        EditText pass = UiKit.input(act, existing == null ? "密码" : "密码（留空不修改）");
        if (existing != null) {
            name.setText(existing.optString("name"));
            host.setText(existing.optString("host"));
            port.setText(String.valueOf(existing.optInt("port", 8006)));
            user.setText(existing.optString("username"));
        }
        box.addView(name); box.addView(host); box.addView(port); box.addView(user); box.addView(pass);
        box.addView(UiKit.button(act, "测试连接", UiKit.PAGE_BG, UiKit.ACCENT, v -> new Thread(() -> {
            try {
                PveClient.Connection tc = new PveClient.Connection();
                tc.host = host.getText().toString().trim();
                tc.port = parsePort(port);
                tc.username = user.getText().toString().trim();
                tc.allowSelfSigned = true;
                PveClient pc = new PveClient(tc);
                pc.login(pass.getText().toString());
                JSONArray nodes = pc.nodes();
                StringBuilder sb = new StringBuilder("✅ 连接成功，节点: ");
                for (int i = 0; i < nodes.length(); i++) sb.append(nodes.optJSONObject(i).optString("node")).append(" ");
                act.runOnUiThread(() -> toast(sb.toString()));
            } catch (Exception e) {
                act.runOnUiThread(() -> toast("❌ " + e.getMessage()));
            }
        }).start()));
        new AlertDialog.Builder(act)
                .setTitle(existing == null ? "添加 PVE 连接" : "编辑连接")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        JSONArray conns = Store.get(act).loadConnections();
                        JSONObject c;
                        if (existing != null) {
                            c = existing;
                        } else {
                            c = new JSONObject();
                            c.put("id", UUID.randomUUID().toString());
                        }
                        c.put("name", name.getText().toString().trim());
                        c.put("host", host.getText().toString().trim());
                        c.put("port", parsePort(port));
                        c.put("username", user.getText().toString().trim());
                        if (!pass.getText().toString().isEmpty()) {
                            c.put("secretEnc", Store.get(act).encrypt(pass.getText().toString()));
                        }
                        if (existing == null) conns.put(c); else conns.put(conns.length(), c);
                        Store.get(act).saveConnections(conns);
                        renderConns();
                    } catch (Exception e) { toast("保存失败: " + e.getMessage()); }
                })
                .setNegativeButton("取消", (d, w) -> dialogOpen = false)
                .setOnDismissListener(d -> dialogOpen = false)
                .show();
    }

    private int parsePort(EditText e) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception x) { return 8006; }
    }

    // ---- SSH 主机 ----

    private void renderHosts() {
        if (hostsBox == null) return;
        hostsBox.removeAllViews();
        JSONArray hosts = Store.get(act).loadSshHosts();
        for (int i = 0; i < hosts.length(); i++) {
            JSONObject h = hosts.optJSONObject(i);
            if (h == null) continue;
            LinearLayout card = UiKit.card(act);
            LinearLayout row = UiKit.row(act,
                    UiKit.text(act, h.optString("name"), 15, UiKit.TEXT_MAIN, true),
                    UiKit.caption(act, h.optString("username") + "@" + h.optString("host") + ":" + h.optInt("port", 22)));
            row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(UiKit.button(act, "编辑", UiKit.PAGE_BG, UiKit.ACCENT, v -> showHostForm(h)));
            final int delIdx = i;
            row.addView(UiKit.button(act, "删除", 0xFFFEE2E2, UiKit.RED, v -> {
                JSONArray n = new JSONArray();
                for (int j = 0; j < hosts.length(); j++) if (j != delIdx) n.put(hosts.opt(j));
                Store.get(act).saveSshHosts(n);
                renderHosts();
            }));
            card.addView(row);
            hostsBox.addView(card, UiKit.cardParams(act));
        }
    }

    private void showHostForm(JSONObject existing) {
        dialogOpen = true;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 8), UiKit.dp(act, 16), 0);
        EditText name = UiKit.input(act, "名称");
        EditText host = UiKit.input(act, "主机");
        EditText port = UiKit.input(act, "端口（默认 22）");
        EditText user = UiKit.input(act, "用户名");
        EditText pass = UiKit.input(act, existing == null ? "密码" : "密码（留空不修改）");
        if (existing != null) {
            name.setText(existing.optString("name"));
            host.setText(existing.optString("host"));
            port.setText(String.valueOf(existing.optInt("port", 22)));
            user.setText(existing.optString("username"));
        }
        box.addView(name); box.addView(host); box.addView(port); box.addView(user); box.addView(pass);
        new AlertDialog.Builder(act)
                .setTitle(existing == null ? "添加 SSH 主机" : "编辑 SSH 主机")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        JSONArray hosts = Store.get(act).loadSshHosts();
                        JSONObject h;
                        if (existing != null) h = existing;
                        else { h = new JSONObject(); h.put("id", UUID.randomUUID().toString()); }
                        h.put("name", name.getText().toString().trim());
                        h.put("host", host.getText().toString().trim());
                        try { h.put("port", Integer.parseInt(port.getText().toString().trim())); }
                        catch (Exception x) { h.put("port", 22); }
                        h.put("username", user.getText().toString().trim());
                        if (!pass.getText().toString().isEmpty()) {
                            h.put("secretEnc", Store.get(act).encrypt(pass.getText().toString()));
                        }
                        if (existing == null) hosts.put(h); else hosts.put(hosts.length(), h);
                        Store.get(act).saveSshHosts(hosts);
                        renderHosts();
                    } catch (Exception e) { toast("保存失败: " + e.getMessage()); }
                })
                .setNegativeButton("取消", (d, w) -> dialogOpen = false)
                .setOnDismissListener(d -> dialogOpen = false)
                .show();
    }

    // ---- 扫描 ----

    private void showScanner() {
        dialogOpen = true;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 8), UiKit.dp(act, 16), 0);

        TextView status = UiKit.caption(act, "点击扫描开始");
        android.widget.ProgressBar bar = new android.widget.ProgressBar(act, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout results = new LinearLayout(act);
        results.setOrientation(LinearLayout.VERTICAL);
        EditText custom = UiKit.input(act, "手动指定网段（可选，如 10.10.10）");

        box.addView(custom);
        box.addView(UiKit.button(act, "开始扫描", UiKit.ACCENT, Color.WHITE, v -> {
            results.removeAllViews();
            status.setText("获取本机网络…");
            new Thread(() -> {
                String subnet = custom.getText().toString().trim();
                if (!subnet.isEmpty() && !LocalScanner.isValidPrefix(subnet)) {
                    act.runOnUiThread(() -> status.setText("网段格式不正确（应为 x.x.x）"));
                    return;
                }
                List<LocalScanner.Discovered> found = LocalScanner.scan(
                        subnet.isEmpty() ? null : subnet,
                        (s, p, f) -> act.runOnUiThread(() -> {
                            status.setText(s);
                            bar.setProgress((int) (p * 1000));
                            results.removeAllViews();
                            for (LocalScanner.Discovered d : f) {
                                LinearLayout row = UiKit.row(act,
                                        UiKit.text(act, d.ip + ":" + LocalScanner.PVE_PORT, 14, UiKit.TEXT_MAIN, true),
                                        UiKit.chip(act, d.verified ? "已验证" : "端口开放",
                                                d.verified ? UiKit.GREEN : UiKit.ORANGE));
                                results.addView(row);
                                TextView add = UiKit.text(act, "添加 →", 14, UiKit.ACCENT, true);
                                add.setPadding(UiKit.dp(act, 12), 0, 0, 0);
                                add.setOnClickListener(vv -> {
                                    JSONObject c = new JSONObject();
                                    try {
                                        c.put("id", UUID.randomUUID().toString());
                                        c.put("name", "PVE @ " + d.ip);
                                        c.put("host", d.ip);
                                        c.put("port", LocalScanner.PVE_PORT);
                                        c.put("username", "root@pam");
                                        c.put("allowSelfSigned", true);
                                        JSONArray conns = Store.get(act).loadConnections();
                                        conns.put(c);
                                        Store.get(act).saveConnections(conns);
                                        renderConns();
                                        toast("已添加，请编辑填入密码");
                                    } catch (Exception e) { toast("添加失败"); }
                                });
                                row.addView(add);
                            }
                        }));
            }, "scanner").start();
        }));
        box.addView(status);
        box.addView(bar);
        box.addView(results);

        android.app.Dialog d = new AlertDialog.Builder(act).setTitle("扫描局域网中的 PVE 服务器")
                .setView(box).setNegativeButton("关闭", (dd, w) -> dialogOpen = false)
                .setOnDismissListener(dd -> dialogOpen = false).create();
        d.show();
    }

    // ---- 诊断日志 ----

    private void showLogs() {
        dialogOpen = true;
        List<AppLogger.Entry> entries = AppLogger.get().snapshot(AppLogger.LEVEL_DEBUG);
        StringBuilder sb = new StringBuilder();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US);
        for (AppLogger.Entry e : entries) {
            sb.append(fmt.format(new java.util.Date(e.ts))).append(" [")
              .append(levelName(e.level)).append("] [").append(e.tag).append("] ")
              .append(e.message).append('\n');
        }
        TextView tv = new TextView(act);
        tv.setText(sb.length() == 0 ? "（暂无日志）" : sb.toString());
        tv.setTextSize(10);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextColor(UiKit.TEXT_MAIN);
        tv.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 8), UiKit.dp(act, 16), UiKit.dp(act, 8));
        ScrollView sc = new ScrollView(act);
        sc.addView(tv);
        new AlertDialog.Builder(act).setTitle("App 诊断日志（" + entries.size() + " 条）")
                .setView(sc)
                .setPositiveButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("log", sb.toString()));
                    toast("已复制 " + entries.size() + " 条日志");
                })
                .setNeutralButton("清空", (d, w) -> { AppLogger.get().clear(); toast("已清空"); })
                .setNegativeButton("关闭", (d, w) -> dialogOpen = false)
                .setOnDismissListener(d -> dialogOpen = false)
                .show();
    }

    private static String levelName(int l) {
        switch (l) {
            case AppLogger.LEVEL_DEBUG: return "调试";
            case AppLogger.LEVEL_INFO: return "信息";
            case AppLogger.LEVEL_WARN: return "警告";
            default: return "错误";
        }
    }

    // ---- 关于 / 历史版本 ----

    private void showChangelog() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 8), UiKit.dp(act, 16), 0);
        for (String[] h : BuildInfo.HISTORY) {
            box.addView(UiKit.text(act, h[0] + " · " + h[1], 14, UiKit.TEXT_MAIN, true));
            box.addView(UiKit.caption(act, h[2]));
            box.addView(new View(act), new LinearLayout.LayoutParams(1, UiKit.dp(act, 8)));
        }
        ScrollView sc = new ScrollView(act);
        sc.addView(box);
        new AlertDialog.Builder(act).setTitle("历史版本变更").setView(sc)
                .setPositiveButton("关闭", (d, w) -> dialogOpen = false)
                .setOnDismissListener(d -> dialogOpen = false).show();
    }

    /** 返回键：对话框打开时只关对话框 */
    public boolean onBack() {
        if (dialogOpen) { dialogOpen = false; return true; }
        return false;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
