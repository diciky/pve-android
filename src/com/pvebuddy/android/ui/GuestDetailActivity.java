package com.pvebuddy.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.pvebuddy.android.core.PveClient;
import com.pvebuddy.android.core.Store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * VM/CT 详情：状态刷新、电源操作（启动/关机/强制停止/重启/挂起/恢复）、
 * 快照（创建/删除/回滚）、备份（vzdump）、迁移、任务日志。
 */
public final class GuestDetailActivity extends Activity {

    private PveClient client;
    private JSONObject guest;
    private String node, type;
    private int vmid;
    private LinearLayout detailBox, logBox;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { guest = new JSONObject(getIntent().getStringExtra("guest")); } catch (Exception e) { finish(); return; }
        node = guest.optString("node");
        type = guest.optString("type", "qemu");
        vmid = guest.optInt("vmid", 0);
        setTitle(vmid + " · " + guest.optString("name", ""));

        // 通过单例桥接获取 client
        client = AppBridge.client;
        if (client == null) { finish(); return; }

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 24));
        scroll.addView(root);
        setContentView(scroll);

        detailBox = new LinearLayout(this);
        detailBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(detailBox);

        TextView logTitle = UiKit.text(this, "任务日志", 16, UiKit.TEXT_MAIN, true);
        logTitle.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), 0, UiKit.dp(this, 4));
        root.addView(logTitle);
        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(logBox);

        refreshDetail();
        timer.scheduleAtFixedRate(this::refreshDetail, 5, 5, TimeUnit.SECONDS);
    }

    private void refreshDetail() {
        new Thread(() -> {
            try {
                JSONObject st = client.guestStatus(node, type, vmid);
                runOnUiThread(() -> renderDetail(st));
            } catch (Exception e) {
                runOnUiThread(() -> UiKit.text(this, "加载失败: " + e.getMessage(), 13, UiKit.RED, false));
            }
        }).start();
    }

    private void renderDetail(JSONObject st) {
        detailBox.removeAllViews();
        String status = st.optString("status");

        LinearLayout card = UiKit.card(this);
        LinearLayout head = UiKit.row(this,
                UiKit.text(this, status.equals("running") ? "运行中" : status, 18, UiKit.statusColor(status), true));
        card.addView(head);
        double cpu = PveClient.asDouble(st.opt("cpu"), 0);
        double mem = PveClient.asDouble(st.opt("mem"), 0), maxmem = PveClient.asDouble(st.opt("maxmem"), 0);
        double uptime = PveClient.asDouble(st.opt("uptime"), 0);
        card.addView(UiKit.caption(this, "CPU: " + String.format("%.1f%%", cpu * 100)
                + " · 内存: " + (maxmem > 0 ? UiKit.memGB(mem) + "/" + UiKit.memGB(maxmem) : "-")
                + " · 运行时长: " + fmtUptime((long) uptime)));
        detailBox.addView(card, UiKit.cardParams(this));

        // 电源操作
        LinearLayout ops = UiKit.card(this);
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        if ("running".equals(status)) {
            row1.addView(opBtn("关机", 0xFFFEE2E2, UiKit.RED, () -> act("shutdown")));
            row1.addView(opBtn("强制停止", 0xFFFEE2E2, UiKit.RED, () -> act("stop")));
            row1.addView(opBtn("重启", 0xFFFEF3C7, UiKit.ORANGE, () -> act("reboot")));
            row1.addView(opBtn("挂起", UiKit.PAGE_BG, UiKit.ACCENT, () -> act("suspend")));
        } else {
            row1.addView(opBtn("启动", 0xFFDCFCE7, UiKit.GREEN, () -> act("start")));
            row1.addView(opBtn("恢复", UiKit.PAGE_BG, UiKit.ACCENT, () -> act("resume")));
        }
        ops.addView(row1);
        detailBox.addView(ops, UiKit.cardParams(this));

        // 管理操作
        LinearLayout mgmt = UiKit.card(this);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(opBtn("快照", UiKit.PAGE_BG, UiKit.ACCENT, this::showSnapshots));
        row2.addView(opBtn("备份", UiKit.PAGE_BG, UiKit.ACCENT, this::showBackup));
        row2.addView(opBtn("迁移", UiKit.PAGE_BG, UiKit.ACCENT, this::showMigrate));
        mgmt.addView(row2);
        detailBox.addView(mgmt, UiKit.cardParams(this));
    }

    private View opBtn(String label, int bg, int fg, Runnable r) {
        View b = UiKit.button(this, label, bg, fg, v -> r.run());
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.rightMargin = UiKit.dp(this, 8);
        b.setLayoutParams(lp);
        return b;
    }

    private String fmtUptime(long sec) {
        long d = sec / 86400, h = (sec % 86400) / 3600, m = (sec % 3600) / 60;
        return d > 0 ? d + "天" + h + "时" : h > 0 ? h + "时" + m + "分" : m + "分";
    }

    private void act(String action) {
        new AlertDialog.Builder(this)
                .setMessage("确定对 VM " + vmid + " 执行 " + action + "？")
                .setPositiveButton("执行", (d, w) -> new Thread(() -> {
                    try {
                        client.post(actionPath(action), "");
                        runOnUiThread(() -> { toast(action + " 已下发"); refreshDetail(); loadTaskLog(); });
                    } catch (Exception e) {
                        runOnUiThread(() -> toast("失败: " + e.getMessage()));
                    }
                }).start())
                .setNegativeButton("取消", null).show();
    }

    private String actionPath(String action) {
        return "/api2/json/nodes/" + node + "/" + type + "/" + vmid + "/" + action;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showSnapshots() {
        new Thread(() -> {
            try {
                JSONArray list = client.snapshots(node, type, vmid);
                runOnUiThread(() -> {
                    LinearLayout box = new LinearLayout(this);
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 8), UiKit.dp(this, 16), 0);
                    EditText nameIn = UiKit.input(this, "新快照名称");
                    EditText descIn = UiKit.input(this, "备注（可选）");
                    box.addView(nameIn);
                    box.addView(descIn);
                    box.addView(UiKit.button(this, "创建快照", UiKit.ACCENT, Color.WHITE, v -> {
                        String n = nameIn.getText().toString().trim();
                        if (n.isEmpty()) { toast("名称不能为空"); return; }
                        new Thread(() -> {
                            try {
                                client.snapshot(node, type, vmid, n, descIn.getText().toString());
                                runOnUiThread(() -> toast("快照任务已下发"));
                            } catch (Exception e) { runOnUiThread(() -> toast("失败: " + e.getMessage())); }
                        }).start();
                    }));
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject s = list.optJSONObject(i);
                        if (s == null) continue;
                        String name = s.optString("name");
                        if ("current".equals(name)) continue;
                        LinearLayout row = UiKit.row(this,
                                UiKit.text(this, name, 14, UiKit.TEXT_MAIN, true));
                        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
                        row.addView(opBtn("回滚", 0xFFFEF3C7, UiKit.ORANGE, () -> {
                            try { client.rollbackSnapshot(node, type, vmid, name); toast("回滚任务已下发"); }
                            catch (Exception e) { toast("失败: " + e.getMessage()); }
                        }));
                        row.addView(opBtn("删除", 0xFFFEE2E2, UiKit.RED, () -> {
                            try { client.deleteSnapshot(node, type, vmid, name); toast("删除任务已下发"); }
                            catch (Exception e) { toast("失败: " + e.getMessage()); }
                        }));
                        box.addView(row);
                    }
                    new AlertDialog.Builder(this).setTitle("快照 · VM " + vmid).setView(box)
                            .setPositiveButton("关闭", null).show();
                });
            } catch (Exception e) { runOnUiThread(() -> toast("获取快照失败: " + e.getMessage())); }
        }).start();
    }

    private void showBackup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 8), UiKit.dp(this, 16), 0);
        box.addView(UiKit.caption(this, "对 VM " + vmid + " 创建 vzdump 备份到 local 存储，模式 snapshot，压缩 zstd"));
        box.addView(UiKit.button(this, "开始备份", UiKit.ACCENT, Color.WHITE, v -> new Thread(() -> {
            try {
                client.createBackup(node, vmid, "local", "snapshot", "zstd");
                runOnUiThread(() -> toast("备份任务已下发，可在任务页查看"));
            } catch (Exception e) { runOnUiThread(() -> toast("失败: " + e.getMessage())); }
        }).start()));
        new AlertDialog.Builder(this).setTitle("备份").setView(box).setPositiveButton("关闭", null).show();
    }

    private void showMigrate() {
        new Thread(() -> {
            try {
                JSONArray nodes = client.nodes();
                runOnUiThread(() -> {
                    String[] names = new String[nodes.length()];
                    for (int i = 0; i < nodes.length(); i++) names[i] = nodes.optJSONObject(i).optString("node");
                    new AlertDialog.Builder(this)
                            .setTitle("迁移 VM " + vmid + " 到节点")
                            .setItems(names, (d, w) -> new Thread(() -> {
                                try {
                                    client.migrate(node, type, vmid, names[w]);
                                    runOnUiThread(() -> toast("迁移任务已下发"));
                                } catch (Exception e) { runOnUiThread(() -> toast("失败: " + e.getMessage())); }
                            }).start())
                            .setNegativeButton("取消", null).show();
                });
            } catch (Exception e) { runOnUiThread(() -> toast("获取节点失败: " + e.getMessage())); }
        }).start();
    }

    private void loadTaskLog() {
        // 最近任务日志（简化：详情操作后由用户到任务页查看完整列表）
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timer.shutdownNow();
    }
}
