package com.pvebuddy.android.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.pvebuddy.android.BuildInfo;
import com.pvebuddy.android.R;
import com.pvebuddy.android.core.AppLogger;
import com.pvebuddy.android.core.PveClient;
import com.pvebuddy.android.core.Store;

import org.json.JSONObject;

/**
 * 主界面：单 Activity + 底部 5 tab（仪表盘/资源/终端/任务/设置）。
 * 持有当前连接与 PveClient，页面按需刷新。
 */
public class MainActivity extends Activity {

    public static final String[] TAB_NAMES = {"仪表盘", "资源", "终端", "任务", "设置"};

    private FrameLayout container;
    private LinearLayout tabBar;
    private Button[] tabButtons = new Button[5];
    private int currentTab = 0;

    public Store store;
    /** 当前选中的连接（JSONObject 形态，含解密后的 password） */
    public JSONObject currentConn;
    public PveClient client;

    private PageDashboard pageDashboard;
    private PageGuests pageGuests;
    private PageTasks pageTasks;
    private PageTerminal pageTerminal;
    private PageSettings pageSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.get().init(this);
        AppLogger.get().info("App", "PVE Buddy 启动 v" + BuildInfo.displayVersion());
        store = Store.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.PAGE_BG);

        container = new FrameLayout(this);
        root.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(Color.WHITE);
        for (int i = 0; i < 5; i++) {
            Button b = new Button(this);
            b.setText(TAB_NAMES[i]);
            b.setAllCaps(false);
            b.setTextSize(12);
            b.setBackgroundColor(Color.TRANSPARENT);
            b.setTextColor(i == 0 ? UiKit.ACCENT : UiKit.TEXT_SUB);
            b.setTypeface(i == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    UiKit.dp(this, 56), 1f);
            b.setLayoutParams(lp);
            final int idx = i;
            b.setOnClickListener(v -> switchTab(idx));
            tabButtons[i] = b;
            tabBar.addView(b);
        }
        // 上分隔线
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE5E7EB);
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 1)));
        root.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 56)));

        setContentView(root);

        pageDashboard = new PageDashboard(this);
        pageGuests = new PageGuests(this);
        pageTasks = new PageTasks(this);
        pageTerminal = new PageTerminal(this);
        pageSettings = new PageSettings(this);
        switchTab(0);
    }

    private void switchTab(int idx) {
        currentTab = idx;
        for (int i = 0; i < 5; i++) {
            tabButtons[i].setTextColor(i == idx ? UiKit.ACCENT : UiKit.TEXT_SUB);
            tabButtons[i].setTypeface(i == idx ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        container.removeAllViews();
        View page = null;
        switch (idx) {
            case 0: page = pageDashboard.build(); pageDashboard.onShow(); break;
            case 1: page = pageGuests.build(); pageGuests.onShow(); break;
            case 2: page = pageTerminal.build(); break;
            case 3: page = pageTasks.build(); pageTasks.onShow(); break;
            case 4: page = pageSettings.build(); break;
        }
        if (page != null) container.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 选择当前连接并创建 client（返回 false 表示没有可用连接） */
    public boolean ensureClient() {
        if (currentConn != null && client != null) return true;
        try {
            org.json.JSONArray conns = store.loadConnections();
            if (conns.length() == 0) return false;
            // 取上次使用或第一个
            int idx = 0;
            String last = store.str("last_conn", null);
            for (int i = 0; i < conns.length(); i++) {
                if (conns.getJSONObject(i).optString("id").equals(last)) { idx = i; break; }
            }
            setCurrentConn(conns.getJSONObject(idx));
            return true;
        } catch (Exception e) {
            AppLogger.get().error("App", "建立 client 失败: " + e);
            return false;
        }
    }

    public void setCurrentConn(JSONObject conn) {
        currentConn = conn;
        store.put("last_conn", conn.optString("id"));
        PveClient.Connection c = new PveClient.Connection();
        c.id = conn.optString("id");
        c.name = conn.optString("name");
        c.host = conn.optString("host");
        c.port = conn.optInt("port", 8006);
        c.username = conn.optString("username");
        c.allowSelfSigned = conn.optBoolean("allowSelfSigned", true);
        c.password = store.decrypt(conn.optString("secretEnc"));
        client = new PveClient(c);
        AppBridge.client = client;
        AppLogger.get().info("App", "切换连接: " + c.name + " (" + c.host + ")");
    }

    public void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    public void toastLong(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    }

    public void runOnUi(Runnable r) { runOnUiThread(r); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pageTerminal != null) pageTerminal.shutdown();
    }

    @Override
    public void onBackPressed() {
        // 设置页里的子对话框自行处理；主 tab 直接退出
        if (currentTab == 4 && pageSettings.onBack()) return;
        super.onBackPressed();
    }
}
