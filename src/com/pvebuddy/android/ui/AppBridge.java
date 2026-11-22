package com.pvebuddy.android.ui;

import com.pvebuddy.android.core.PveClient;

/** GuestDetailActivity 等子 Activity 获取当前 PveClient 的桥。MainActivity 切换连接时更新。 */
public final class AppBridge {
    public static volatile PveClient client;
    private AppBridge() { }
}
