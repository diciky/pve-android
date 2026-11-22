package com.pvebuddy.android.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.pvebuddy.android.core.PveClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 资源页：全部 VM/CT 列表（cluster/resources 过滤 qemu/lxc），支持搜索，点击进详情。 */
public final class PageGuests {

    private final Activity act;
    private LinearLayout content;
    private ListView list;
    private JSONArray all = new JSONArray();
    private List<JSONObject> shown = new ArrayList<>();
    private String query = "";
    private TextView status;

    PageGuests(Activity a) { act = a; }

    public View build() {
        if (content == null) {
            content = new LinearLayout(act);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setBackgroundColor(UiKit.PAGE_BG);

            EditText search = UiKit.input(act, "搜索 VMID / 名称 / 节点");
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.setMargins(UiKit.dp(act, 12), UiKit.dp(act, 8), UiKit.dp(act, 12), 0);
            search.setLayoutParams(sp);
            search.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                public void onTextChanged(CharSequence s, int a, int b, int c) { }
                public void afterTextChanged(android.text.Editable s) {
                    query = s.toString().toLowerCase();
                    applyFilter();
                }
            });
            content.addView(search);

            status = UiKit.caption(act, "");
            status.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 6), UiKit.dp(act, 16), 0);
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
                all = main.client.resources();
                act.runOnUiThread(() -> { status.setText(""); applyFilter(); });
            } catch (Exception e) {
                act.runOnUiThread(() -> status.setText("加载失败: " + e.getMessage()));
            }
        }).start();
    }

    private void applyFilter() {
        shown.clear();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("type");
            if (!"qemu".equals(t) && !"lxc".equals(t)) continue;
            if (!query.isEmpty()) {
                String hay = (o.optInt("vmid", 0) + " " + o.optString("name") + " " + o.optString("node")).toLowerCase();
                if (!hay.contains(query)) continue;
            }
            shown.add(o);
        }
        list.setAdapter(new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View cv, ViewGroup parent) { return guestRow(shown.get(i)); }
        });
        list.setOnItemClickListener((p, v, pos, id) -> openDetail(shown.get(pos)));
        if (shown.isEmpty()) status.setText(query.isEmpty() ? "暂无资源" : "无匹配结果");
    }

    private View guestRow(JSONObject g) {
        String type = g.optString("type");
        int vmid = g.optInt("vmid", 0);
        String name = g.optString("name", "(未命名)");
        String node = g.optString("node");
        String status = g.optString("status");

        LinearLayout card = UiKit.card(act);
        LinearLayout row = UiKit.row(act);
        TextView badge = UiKit.chip(act, "qemu".equals(type) ? "VM" : "CT",
                "qemu".equals(type) ? UiKit.ACCENT : 0xFF7C3AED);
        row.addView(badge);
        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = UiKit.dp(act, 10);
        mid.setLayoutParams(mlp);
        mid.addView(UiKit.text(act, vmid + " · " + name, 15, UiKit.TEXT_MAIN, true));
        mid.addView(UiKit.caption(act, node + " · " + status));
        row.addView(mid);
        TextView st = UiKit.text(act, "running".equals(status) ? "运行中" : "stopped".equals(status) ? "已停止" : status,
                13, UiKit.statusColor(status), true);
        row.addView(st);
        card.addView(row);

        card.setOnClickListener(v -> openDetail(g));
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(card, UiKit.cardParams(act));
        return wrap;
    }

    private void openDetail(JSONObject g) {
        Intent it = new Intent(act, GuestDetailActivity.class);
        it.putExtra("guest", g.toString());
        it.putExtra("connId", currentConnId());
        act.startActivity(it);
    }

    private String currentConnId() {
        MainActivity m = (MainActivity) act;
        return m.currentConn != null ? m.currentConn.optString("id") : "";
    }
}
