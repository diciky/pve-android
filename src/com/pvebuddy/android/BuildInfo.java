package com.pvebuddy.android;

/**
 * 构建信息：版本号与变更内容手动维护（每次修改代码必须递增版本号并填写变更摘要）。
 * 与 CHANGELOG.md 同步。语义化：主版本=重制、次版本=新功能、修订号=修复。
 * 本地构建 BUILD_NUMBER=0；CI 注入 run_number 与 commit。
 */
public final class BuildInfo {

    public static final String VERSION_NAME = "1.0.0";
    public static final int BUILD_NUMBER = 0;
    public static final String COMMIT_SHA = "dev";

    /** 当前版本变更摘要 */
    public static final String RELEASE_NOTES =
            "首版：登录多服务器、仪表盘、资源管理（开关机/快照/备份/迁移）、任务、SSH 终端、控制面板、系统日志、局域网扫描、诊断日志";

    /** 历史版本变更：{版本, 日期, 说明}，新版本加在最前 */
    public static final String[][] HISTORY = {
        {"1.0.0", "2026-11-22", "首版：登录多服务器、仪表盘、资源管理（开关机/快照/备份/迁移）、任务、SSH 终端、控制面板、系统日志、局域网扫描、诊断日志"},
    };

    public static String displayVersion() {
        StringBuilder sb = new StringBuilder(VERSION_NAME);
        if (BUILD_NUMBER != 0) sb.append(" (").append(BUILD_NUMBER).append(")");
        if (!"dev".equals(COMMIT_SHA)) sb.append(" ").append(COMMIT_SHA);
        return sb.toString();
    }

    private BuildInfo() {}
}
