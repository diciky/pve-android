package com.pvebuddy.android.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;

/**
 * 诊断日志：内存环形缓冲（800 条）+ JSON Lines 落盘。
 * 真机排障核心——API 请求（含耗时）/ SSH / 扫描 / 控制面板操作全钩子。
 * 对等 iOS 版 AppLogger。
 */
public final class AppLogger {

    public static final int LEVEL_DEBUG = 0, LEVEL_INFO = 1, LEVEL_WARN = 2, LEVEL_ERROR = 3;
    private static final int CAPACITY = 800;

    public static final class Entry {
        public final long ts;
        public final int level;
        public final String tag, message;
        Entry(long ts, int level, String tag, String message) {
            this.ts = ts; this.level = level; this.tag = tag; this.message = message;
        }
    }

    private static final AppLogger INSTANCE = new AppLogger();
    private final Deque<Entry> ring = new ArrayDeque<>(CAPACITY);
    private FileWriter logFile;
    private File file;
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private final Object lock = new Object();

    public static AppLogger get() { return INSTANCE; }

    public void init(Context ctx) {
        synchronized (lock) {
            try {
                File dir = new File(ctx.getExternalFilesDir(null), "logs");
                if (!dir.exists()) dir.mkdirs();
                file = new File(dir, "diagnostic.log");
                logFile = new FileWriter(file, true);
            } catch (Exception e) {
                file = null; logFile = null;
            }
        }
    }

    public void debug(String tag, String msg) { log(LEVEL_DEBUG, tag, msg); }
    public void info(String tag, String msg)  { log(LEVEL_INFO, tag, msg); }
    public void warn(String tag, String msg)  { log(LEVEL_WARN, tag, msg); }
    public void error(String tag, String msg) { log(LEVEL_ERROR, tag, msg); }

    public void log(int level, String tag, String message) {
        Entry e = new Entry(System.currentTimeMillis(), level, tag, message);
        synchronized (lock) {
            if (ring.size() >= CAPACITY) ring.pollFirst();
            ring.addLast(e);
            if (logFile != null) {
                try {
                    logFile.write(String.format(Locale.US, "%s [%s] [%s] %s%n",
                            fmt.format(new Date(e.ts)), levelName(level), tag, message));
                    logFile.flush();
                } catch (Exception ignore) { }
            }
        }
    }

    /** 取快照（新→旧），minLevel 以下过滤 */
    public java.util.List<Entry> snapshot(int minLevel) {
        synchronized (lock) {
            java.util.List<Entry> out = new java.util.ArrayList<>();
            Iterator<Entry> it = ring.descendingIterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (e.level >= minLevel) out.add(e);
            }
            return out;
        }
    }

    public String exportText(int minLevel) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat full = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
        for (Entry e : snapshot(minLevel)) {
            sb.append(full.format(new Date(e.ts))).append(" [").append(levelName(e.level))
              .append("] [").append(e.tag).append("] ").append(e.message).append('\n');
        }
        return sb.toString();
    }

    public void clear() {
        synchronized (lock) {
            ring.clear();
            if (logFile != null) {
                try { logFile.close(); } catch (Exception ignore) { }
                if (file != null && file.exists()) file.delete();
                try { logFile = new FileWriter(file, false); } catch (Exception ignore) { }
            }
        }
    }

    public File logFile() { return file; }

    private static String levelName(int l) {
        switch (l) {
            case LEVEL_DEBUG: return "调试";
            case LEVEL_INFO:  return "信息";
            case LEVEL_WARN:  return "警告";
            default:          return "错误";
        }
    }
}
