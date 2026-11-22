package com.pvebuddy.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.pvebuddy.android.core.AppLogger;
import com.pvebuddy.android.core.PveClient;
import com.pvebuddy.android.core.Store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 仪表盘：节点卡片（CPU/内存/存储进度条）+ 资源汇总 + 控制面板（节点重启/关机、批量启停、服务重启）。
 * 5 秒自动刷新（可关）。
 */
public final class PageDashboard {

    private final Activity act;
    private LinearLayout content;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean showing;
    private LinearLayout nodesBox, summaryBox, controlBox;
    private TextView statusText;

    PageDashboard(Activity a) { act = a; }

    public View build() {
        if (content == null) {
            content = new LinearLayout(act);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setBackgroundColor(UiKit.PAGE_BG);

            ScrollView scroll = new ScrollView(act);
            scroll.setFillViewport(true);
            LinearLayout inner = new LinearLayout(act);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setPadding(0, UiKit.dp(act, 8), 0, UiKit.dp(act, 80));
            scroll.addView(inner);

            statusText = UiKit.caption(act, "");
            statusText.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 8), UiKit.dp(act, 16), 0);
            inner.addView(statusText);

            nodesBox = new LinearLayout(act);
            nodesBox.setOrientation(LinearLayout.VERTICAL);
            inner.addView(nodesBox);

            TextView sumTitle = UiKit.text(act, "资源汇总", 16, UiKit.TEXT_MAIN, true);
            sumTitle.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 16), 0, UiKit.dp(act, 4));
            inner.addView(sumTitle);
            summaryBox = new LinearLayout(act);
            summaryBox.setOrientation(LinearLayout.VERTICAL);
            inner.addView(summaryBox);

            TextView ctlTitle = UiKit.text(act, "控制面板", 16, UiKit.TEXT_MAIN, true);
            ctlTitle.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 16), 0, UiKit.dp(act, 4));
            inner.addView(ctlTitle);
            controlBox = new LinearLayout(act);
            controlBox.setOrientation(LinearLayout.VERTICAL);
            inner.addView(controlBox);

            content.addView(scroll, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return content;
    }

    public void onShow() {
        showing = true;
        refresh();
        boolean auto = Store.get(act).bool("dashboard_auto_refresh", true);
        if (auto) {
            timer.scheduleAtFixedRate(() -> { if (showing) refresh(); }, 5, 5, TimeUnit.SECONDS);
        }
    }

    public void onHide() { showing = false; }

    private void refresh() {
        MainActivity main = (MainActivity) act;
        if (!main.ensureClient()) { setStatus("请先在 设置 中添加 PVE 连接"); return; }
        setStatus("加载中…");
        new Thread(() -> {
            try {
                JSONArray res = main.client.resources();
                JSONArray nodes = filterNodes(res);
                act.runOnUiThread(() -> {
                    renderNodes(nodes);
                    renderSummary(res);
                    renderControls(nodes);
                    setStatus("");
                });
            } catch (Exception e) {
                act.runOnUiThread(() -> setStatus("加载失败: " + e.getMessage()));
            }
        }).start();
    }

    private JSONArray filterNodes(JSONArray res) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < res.length(); i++) {
            JSONObject o = res.optJSONObject(i);
            if (o != null && "node".equals(o.optString("type"))) out.put(o);
        }
        return out;
    }

    private void setStatus(String s) { statusText.setText(s); }

    private LinearLayout progressBar(String label, double used, double total, String detail) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, UiKit.dp(act, 6), 0, 0);
        LinearLayout row = UiKit.row(act, UiKit.text(act, label, 14, UiKit.TEXT_MAIN, true));
        TextView val = UiKit.caption(act, detail);
        val.setGravity(Gravity.END);
        row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(val);
        box.addView(row);
        ProgressBar p = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(1000);
        p.setProgress((int) (total > 0 ? used / total * 1000 : 0));
        p.getProgressDrawable().setTint(UiKit.ACCENT);
        box.addView(p, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(act, 8)));
        return box;
    }

    private void renderNodes(JSONArray nodes) {
        nodesBox.removeAllViews();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            String name = n.optString("node");
            String status = n.optString("status");
            double cpu = PveClient.asDouble(n.opt("cpu"), 0);
            double mem = PveClient.asDouble(n.opt("mem"), 0), maxmem = PveClient.asDouble(n.opt("maxmem"), 0);
            double disk = PveClient.asDouble(n.opt("disk"), 0), maxdisk = PveClient.asDouble(n.opt("maxdisk"), 0);

            LinearLayout card = UiKit.card(act);
            LinearLayout head = UiKit.row(act,
                    UiKit.text(act, name, 16, UiKit.TEXT_MAIN, true),
                    UiKit.chip(act, status.equals("online") ? "在线" : status, UiKit.statusColor(status)));
            ((LinearLayout.LayoutParams) head.getChildAt(1).getLayoutParams()).leftMargin = UiKit.dp(act, 8);
            card.addView(head);
            card.addView(progressBar("CPU", cpu, 1, String.format("%.0f%%", cpu * 100)));
            card.addView(progressBar("内存", mem, maxmem, maxmem > 0 ? UiKit.memGB(mem) + " / " + UiKit.memGB(maxmem) : "-"));
            card.addView(progressBar("存储", disk, maxdisk, maxdisk > 0 ? UiKit.memGB(disk) + " / " + UiKit.memGB(maxdisk) : "-"));
            nodesBox.addView(card, UiKit.cardParams(act));
        }
    }

    private void renderSummary(JSONArray res) {
        int qmRun = 0, qmAll = 0, ctRun = 0, ctAll = 0;
        for (int i = 0; i < res.length(); i++) {
            JSONObject o = res.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("type"), s = o.optString("status");
            if ("qemu".equals(t)) { qmAll++; if ("running".equals(s)) qmRun++; }
            if ("lxc".equals(t)) { ctAll++; if ("running".equals(s)) ctRun++; }
        }
        summaryBox.removeAllViews();
        LinearLayout card = UiKit.card(act);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(countCell("VM 运行", qmRun + "/" + qmAll, UiKit.GREEN));
        row.addView(countCell("VM 总数", String.valueOf(qmAll), UiKit.ACCENT));
        row.addView(countCell("CT 运行", ctRun + "/" + ctAll, UiKit.GREEN));
        row.addView(countCell("CT 总数", String.valueOf(ctAll), UiKit.ACCENT));
        card.addView(row);
        summaryBox.addView(card, UiKit.cardParams(act));
    }

    private LinearLayout countCell(String label, String value, int color) {
        LinearLayout cell = new LinearLayout(act);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cell.setLayoutParams(lp);
        TextView v = UiKit.text(act, value, 22, color, true);
        v.setGravity(Gravity.CENTER);
        cell.addView(v);
        cell.addView(UiKit.caption(act, label));
        return cell;
    }

    private void renderControls(JSONArray nodes) {
        controlBox.removeAllViews();
        LinearLayout card = UiKit.card(act);
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null || !"online".equals(n.optString("status"))) continue;
            String node = n.optString("node");

            LinearLayout row = UiKit.row(act, UiKit.text(act, node, 14, UiKit.TEXT_MAIN, true));
            row.setPadding(0, UiKit.dp(act, 8), 0, UiKit.dp(act, 4));
            card.addView(row);

            LinearLayout btns = new LinearLayout(act);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.addView(UiKit.button(act, "全部启动", 0xFFDCFCE7, UiKit.GREEN,
                    v -> confirm("在节点 " + node + " 批量启动全部虚拟机？", () -> batch(node, "startall"))));
            btns.addView(spacer());
            btns.addView(UiKit.button(act, "全部停止", 0xFFFEE2E2, UiKit.RED,
                    v -> confirm("⚠️ 在节点 " + node + " 批量停止全部虚拟机？", () -> batch(node, "stopall"))));
            btns.addView(spacer());
            btns.addView(UiKit.button(act, "重启节点", 0xFFFEF3C7, UiKit.ORANGE,
                    v -> confirm("⚠️ 确定重启节点 " + node + "？集群会短暂中断", () -> nodeAction(node, "reboot"))));
            btns.addView(spacer());
            btns.addView(UiKit.button(act, "关机", 0xFFFEE2E2, UiKit.RED,
                    v -> confirm("⚠️ 确定关闭节点 " + node + "？", () -> nodeAction(node, "shutdown"))));
            btns.addView(spacer());
            btns.addView(UiKit.button(act, "服务管理", UiKit.PAGE_BG, UiKit.ACCENT,
                    v -> showServices(node)));
            card.addView(btns);
        }
        if (controlBox.getChildCount() == 0) controlBox.addView(card, UiKit.cardParams(act));
    }

    private View spacer() {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(act, 8), 1));
        return v;
    }

    private void confirm(String msg, Runnable action) {
        new AlertDialog.Builder(act)
                .setTitle("确认操作")
                .setMessage(msg)
                .setPositiveButton("执行", (d, w) -> action.run())
                .setNegativeButton("取消", null)
                .show();
    }

    private void batch(String node, String action) {
        MainActivity main = (MainActivity) act;
        new Thread(() -> {
            try {
                main.client.batchGuestAction(node, action);
                AppLogger.get().info("ControlPanel", node + " " + action + " 已下发");
                act.runOnUiThread(() -> main.toast(action + " 已下发"));
            } catch (Exception e) {
                act.runOnUiThread(() -> main.toastLong("失败: " + e.getMessage()));
            }
        }).start();
    }

    private void nodeAction(String node, String action) {
        MainActivity main = (MainActivity) act;
        new Thread(() -> {
            try {
                main.client.nodeAction(node, action);
                AppLogger.get().info("ControlPanel", "节点 " + node + " " + action);
                act.runOnUiThread(() -> main.toast("节点 " + action + " 已下发"));
            } catch (Exception e) {
                act.runOnUiThread(() -> main.toastLong("失败: " + e.getMessage()));
            }
        }).start();
    }

    private void showServices(String node) {
        MainActivity main = (MainActivity) act;
        new Thread(() -> {
            try {
                JSONArray list = main.client.services(node);
                act.runOnUiThread(() -> {
                    LinearLayout box = new LinearLayout(act);
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 8), UiKit.dp(act, 16), 0);
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject s = list.optJSONObject(i);
                        if (s == null) continue;
                        String name = s.optString("name");
                        String state = s.optString("state");
                        LinearLayout row = UiKit.row(act,
                                UiKit.text(act, name, 14, UiKit.TEXT_MAIN, true),
                                UiKit.chip(act, state, UiKit.statusColor(state)));
                        ((LinearLayout.LayoutParams) row.getChildAt(1).getLayoutParams()).leftMargin = UiKit.dp(act, 8);
                        View restart = UiKit.button(act, "重启", UiKit.PAGE_BG, UiKit.ACCENT, v -> {
                            new Thread(() -> {
                                try {
                                    main.client.serviceAction(node, name, "restart");
                                    act.runOnUiThread(() -> main.toast(name + " 重启已下发"));
                                } catch (Exception e) {
                                    act.runOnUiThread(() -> main.toastLong("失败: " + e.getMessage()));
                                }
                            }).start();
                        });
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(act, 34));
                        rlp.leftMargin = UiKit.dp(act, 8);
                        restart.setLayoutParams(rlp);
                        row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
                        row.addView(restart);
                        box.addView(row);
                    }
                    new AlertDialog.Builder(act).setTitle("核心服务 · " + node).setView(box)
                            .setPositiveButton("关闭", null).show();
                });
            } catch (Exception e) {
                act.runOnUiThread(() -> main.toastLong("获取服务列表失败: " + e.getMessage()));
            }
        }).start();
    }
}
