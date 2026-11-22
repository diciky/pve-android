package com.pvebuddy.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.pvebuddy.android.core.PveClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 任务页：集群任务列表（⚠️ 不带查询参数），点击看日志。 */
public final class PageTasks {

    private final Activity act;
    private LinearLayout content;
    private ListView list;
    private final List<JSONObject> tasks = new ArrayList<>();
    private TextView status;
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);

    PageTasks(Activity a) { act = a; }

    public View build() {
        if (content == null) {
            content = new LinearLayout(act);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setBackgroundColor(UiKit.PAGE_BG);
            status = UiKit.caption(act, "");
            status.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 8), UiKit.dp(act, 16), 0);
            content.addView(status);
            list = new ListView(act);
            list.setDivider(null);
            content.addView(list, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return content;
    }

    public void onShow() { refresh(); }

    public void refresh() {
        MainActivity main = (MainActivity) act;
        if (!main.ensureClient()) { status.setText("请先在 设置 中添加 PVE 连接"); return; }
        status.setText("加载中…");
        new Thread(() -> {
            try {
                // ⚠️ /cluster/tasks 不接受任何查询参数（含 limit），PVE schema 校验会 400
                JSONArray arr = main.client.clusterTasks();
                act.runOnUiThread(() -> {
                    tasks.clear();
                    for (int i = 0; i < arr.length() && i < 100; i++) {
                        JSONObject t = arr.optJSONObject(i);
                        if (t != null) tasks.add(t);
                    }
                    status.setText("");
                    list.setAdapter(new BaseAdapter() {
                        public int getCount() { return tasks.size(); }
                        public Object getItem(int i) { return tasks.get(i); }
                        public long getItemId(int i) { return i; }
                        public View getView(int i, View cv, ViewGroup p) { return taskRow(tasks.get(i)); }
                    });
                    list.setOnItemClickListener((p, v, pos, id) -> showLog(tasks.get(pos)));
                    if (tasks.isEmpty()) status.setText("暂无任务");
                });
            } catch (Exception e) {
                act.runOnUiThread(() -> status.setText("加载失败: " + e.getMessage()));
            }
        }).start();
    }

    private View taskRow(JSONObject t) {
        String type = t.optString("type", "?");
        String status = t.optString("status", "");
        boolean ok = "OK".equals(status);
        boolean failed = status.contains("error") || status.contains("Error");
        String desc = t.optString("description", type);
        String upid = t.optString("upid");

        LinearLayout card = UiKit.card(act);
        LinearLayout row = UiKit.row(act,
                UiKit.chip(act, ok ? "成功" : failed ? "失败" : status.isEmpty() ? "运行中" : status,
                        ok ? UiKit.GREEN : failed ? UiKit.RED : UiKit.ORANGE));
        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = UiKit.dp(act, 8);
        mid.setLayoutParams(mlp);
        mid.addView(UiKit.text(act, desc, 14, UiKit.TEXT_MAIN, true));
        long start = PveClient.asLong(t.opt("starttime"), 0) * 1000;
        mid.addView(UiKit.caption(act, t.optString("node") + " · "
                + (start > 0 ? fmt.format(new Date(start)) : "")));
        row.addView(mid);
        card.addView(row);

        card.setOnClickListener(v -> showLog(t));
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(card, UiKit.cardParams(act));
        return wrap;
    }

    private void showLog(JSONObject t) {
        String upid = t.optString("upid");
        String node = t.optString("node");
        new Thread(() -> {
            try {
                JSONArray log = ((MainActivity) act).client.taskLog(node, upid);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < log.length(); i++) {
                    JSONObject line = log.optJSONObject(i);
                    if (line != null) sb.append(line.optString("t")).append('\n');
                }
                String text = sb.length() == 0 ? "（无日志）" : sb.toString();
                act.runOnUiThread(() -> {
                    TextView tv = new TextView(act);
                    tv.setText(text);
                    tv.setTextSize(11);
                    tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                    tv.setTextColor(UiKit.TEXT_MAIN);
                    tv.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 8), UiKit.dp(act, 16), UiKit.dp(act, 8));
                    android.widget.ScrollView sc = new android.widget.ScrollView(act);
                    sc.addView(tv);
                    new AlertDialog.Builder(act).setTitle("任务日志").setView(sc)
                            .setPositiveButton("关闭", null).show();
                });
            } catch (Exception e) {
                act.runOnUiThread(() -> toast("获取日志失败: " + e.getMessage()));
            }
        }).start();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
