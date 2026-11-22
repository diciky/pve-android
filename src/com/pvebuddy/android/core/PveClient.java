package com.pvebuddy.android.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * PVE REST API 客户端（Java 对等实现，含 iOS 版全部修复经验）：
 * - ticket + CSRF 认证
 * - GET envelope 只拆一次
 * - /cluster/tasks 不接受任何查询参数（PVE schema 限制，limit 仅节点级）
 * - 含 UPID 的路径手动编码防二次编码
 * - cpuinfo.cpu/mhz 字符串/数字宽容解码
 * - 自签证书支持（allowSelfSigned）
 */
public final class PveClient {

    public static final class PveException extends Exception {
        public final int code;
        public PveException(int code, String msg) { super(msg); this.code = code; }
    }

    public static final class Connection {
        public String id, name, host, username;
        public int port = 8006;
        public boolean allowSelfSigned;
        // 密码在 Store 中加密持久化，此处运行时持有
        public String password;
        public String ticket, csrf;
        public long ticketAt;
    }

    private Connection conn;
    private static javax.net.ssl.SSLSocketFactory trustAllFactory;
    private static final HostnameVerifier TRUST_ALL_HOSTS = (h, s) -> true;

    public PveClient(Connection c) { this.conn = c; }

    public Connection connection() { return conn; }

    public void setConnection(Connection c) { this.conn = c; }

    private static synchronized javax.net.ssl.SSLSocketFactory trustAll() throws Exception {
        if (trustAllFactory == null) {
            TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String t) { }
                public void checkServerTrusted(X509Certificate[] c, String t) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            trustAllFactory = ctx.getSocketFactory();
        }
        return trustAllFactory;
    }

    // ---- 认证 ----

    /** POST /access/ticket。成功填充 ticket/csrf。 */
    public void login(String password) throws Exception {
        String body = "username=" + enc(conn.username) + "&password=" + enc(password);
        JSONObject resp = request("POST", "/api2/json/access/ticket", body, null, false);
        JSONObject data = resp.getJSONObject("data");
        conn.ticket = data.getString("ticket");
        conn.csrf = data.optString("CSRFPreventionToken", null);
        conn.password = password;
        conn.ticketAt = System.currentTimeMillis();
        AppLogger.get().info("API(" + conn.host + ")", "登录成功：" + conn.username);
    }

    /** ticket 2 小时过期，提前 10 分钟续期 */
    public void ensureAuth() throws Exception {
        if (conn.ticket == null || System.currentTimeMillis() - conn.ticketAt > 110 * 60 * 1000L) {
            login(conn.password);
        }
    }

    // ---- 端点 ----

    /** GET /cluster/resources — 全部资源（节点+VM+CT） */
    public JSONArray resources() throws Exception { return getArray("/api2/json/cluster/resources"); }

    /** GET /cluster/tasks — ⚠️ 集群级不接受任何查询参数（含 limit） */
    public JSONArray clusterTasks() throws Exception { return getArray("/api2/json/cluster/tasks"); }

    /** GET /nodes/{node}/tasks — 节点级支持 limit */
    public JSONArray nodeTasks(String node, int limit) throws Exception {
        return getArray("/api2/json/nodes/" + enc(node) + "/tasks?limit=" + limit);
    }

    /** GET /nodes — 节点列表 */
    public JSONArray nodes() throws Exception { return getArray("/api2/json/nodes"); }

    /** GET /nodes/{node}/status */
    public JSONObject nodeStatus(String node) throws Exception {
        return getArray("/api2/json/nodes/" + enc(node) + "/status").getJSONObject(0);
    }

    /** GET /nodes/{node}/qemu 或 /lxc 列表 */
    public JSONArray guests(String node, String type) throws Exception {
        return getArray("/api2/json/nodes/" + enc(node) + "/" + type);
    }

    /** GET /nodes/{node}/{type}/{vmid}/status/current */
    public JSONObject guestStatus(String node, String type, int vmid) throws Exception {
        return getArray("/api2/json/nodes/" + enc(node) + "/" + type + "/" + vmid + "/status/current").getJSONObject(0);
    }

    /** 电源操作：PVE 端点为 /status/{action}（start/stop/shutdown/reboot/suspend/resume），与 iOS 版一致 */
    public JSONObject guestAction(String node, String type, int vmid, String action) throws Exception {
        return post("/api2/json/nodes/" + enc(node) + "/" + type + "/" + vmid + "/status/" + enc(action), "");
    }

    public JSONObject snapshot(String node, String type, int vmid, String name, String desc) throws Exception {
        String body = "snapname=" + enc(name) + (desc == null || desc.isEmpty() ? "" : "&description=" + enc(desc));
        return post("/api2/json/nodes/" + enc(node) + "/" + type + "/" + vmid + "/snapshot", body);
    }

    public JSONArray snapshots(String node, String type, int vmid) throws Exception {
        return getArray("/api2/json/nodes/" + enc(node) + "/" + type + "/" + vmid + "/snapshot");
    }

    public JSONObject deleteSnapshot(String node, String type, int vmid, String name) throws Exception {
        return post("/api2/json/nodes/" + enc(node) + "/" + type + "/" + vmid + "/snapshot/" + enc(name) + "/delsnapshot", "");
    }

    public JSONObject rollbackSnapshot(String node, String type, int vmid, String name) throws Exception {
        return post("/api2/json/nodes/" + enc(node) + "/" + type + "/" + vmid + "/snapshot/" + enc(name) + "/rollback", "");
    }

    public JSONArray storageContent(String node, String storage, String content) throws Exception {
        return getArray("/api2/json/nodes/" + enc(node) + "/storage/" + enc(storage) + "/content?content=" + enc(content));
    }

    public JSONObject createBackup(String node, int vmid, String storage, String mode, String compress) throws Exception {
        String body = "vmid=" + vmid + "&storage=" + enc(storage) + "&mode=" + enc(mode) + "&compress=" + enc(compress);
        return post("/api2/json/nodes/" + enc(node) + "/vzdump", body);
    }

    public JSONObject migrate(String node, String type, int vmid, String target) throws Exception {
        return post("/api2/json/nodes/" + enc(node) + "/" + type + "/" + vmid + "/migrate?target=" + enc(target), "");
    }

    /** GET /nodes/{node}/tasks/{upid}/status — UPID 手动放行（已在路径中编码过） */
    public JSONObject taskStatus(String node, String upid) throws Exception {
        return getArray("/api2/json/nodes/" + enc(node) + "/tasks/" + upid + "/status").getJSONObject(0);
    }

    /** GET /nodes/{node}/tasks/{upid}/log */
    public JSONArray taskLog(String node, String upid) throws Exception {
        return getArray("/api2/json/nodes/" + enc(node) + "/tasks/" + upid + "/log?start=0&limit=200");
    }

    // ---- 控制面板 ----

    /** GET /nodes/{node}/services */
    public JSONArray services(String node) throws Exception {
        return getArray("/api2/json/nodes/" + enc(node) + "/services");
    }

    public JSONObject serviceAction(String node, String service, String action) throws Exception {
        return post("/api2/json/nodes/" + enc(node) + "/services/" + enc(service) + "/" + enc(action), "");
    }

    public JSONObject nodeAction(String node, String action) throws Exception {
        return post("/api2/json/nodes/" + enc(node) + "/" + enc(action), "");
    }

    public JSONObject batchGuestAction(String node, String action) throws Exception {
        return post("/api2/json/nodes/" + enc(node) + "/" + enc(action), "");
    }

    // ---- 日志 ----

    /** GET /nodes/{node}/syslog。注意：节点级接受 limit */
    public JSONArray syslog(String node, String service, int limit) throws Exception {
        String q = "?start=0&limit=" + limit + (service == null || service.isEmpty() ? "" : "&service=" + enc(service));
        return getArray("/api2/json/nodes/" + enc(node) + "/syslog" + q);
    }

    // ---- 底层请求 ----

    private JSONArray getArray(String pathWithQuery) throws Exception {
        ensureAuth();
        JSONObject resp = request("GET", pathWithQuery, null, null, true);
        Object data = resp.opt("data");
        if (data instanceof JSONArray) return (JSONArray) data;
        if (data == null || data == JSONObject.NULL) return new JSONArray();
        JSONArray arr = new JSONArray(); arr.put(data); return arr; // 单对象包装
    }

    public JSONObject post(String path, String form) throws Exception {
        ensureAuth();
        return request("POST", path, form, null, true);
    }

    /**
     * 核心请求。GET envelope 由调用方拆（本方法返回完整 {data:...}）。
     * POST 返回 {"data": "UPID..."}。
     */
    private JSONObject request(String method, String pathWithQuery, String body,
                               Integer timeoutMs, boolean authed) throws Exception {
        long start = System.currentTimeMillis();
        // ⚠️ 防二次编码：pathWithQuery 里已编码的部分不再经 URL 编码器。
        // HttpURLConnection 的 URL() 不会 re-encode 已合法编码的字符串；UPID 中的冒号等
        // 字符已用 enc() 编码过，直接拼 URL 安全。
        String urlStr = "https://" + conn.host + ":" + conn.port + pathWithQuery;
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs != null ? timeoutMs : 8000);
        c.setReadTimeout(timeoutMs != null ? timeoutMs : 20000);
        if (conn.allowSelfSigned && c instanceof HttpsURLConnection) {
            HttpsURLConnection h = (HttpsURLConnection) c;
            h.setSSLSocketFactory(trustAll());
            h.setHostnameVerifier(TRUST_ALL_HOSTS);
        }
        if (authed && conn.ticket != null) {
            c.setRequestProperty("Cookie", "PVEAuthCookie=" + conn.ticket);
            if (!"GET".equals(method) && conn.csrf != null) {
                c.setRequestProperty("CSRFPreventionToken", conn.csrf);
            }
        }
        c.setRequestProperty("Accept", "application/json");

        boolean hasBody = body != null && !body.isEmpty();
        if (hasBody) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = c.getResponseCode();
        String respBody = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        long cost = System.currentTimeMillis() - start;
        String shortPath = pathWithQuery.replaceAll("/api2/json", "");

        if (code == 200) {
            AppLogger.get().debug("API(" + conn.host + ")", method + " " + shortPath + " → OK (" + cost + "ms)");
            try {
                return new JSONObject(respBody);
            } catch (Exception e) {
                throw new PveException(-1, "响应非 JSON：" + respBody.substring(0, Math.min(120, respBody.length())));
            }
        }
        String msg = "PVE 请求失败(" + code + "): " + respBody;
        AppLogger.get().error("API(" + conn.host + ")", method + " " + shortPath + " → " + msg);
        c.disconnect();
        throw new PveException(code, msg);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try (BufferedInputStream bis = new BufferedInputStream(in)) {
            int n;
            while ((n = bis.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s; // UTF-8 恒可用，理论不可达
        }
    }

    // ---- 宽容解码工具（cpuinfo.cpu/mhz 可能是字符串或数字）----

    public static double asDouble(Object o, double def) {
        if (o == null || o == JSONObject.NULL) return def;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    public static long asLong(Object o, long def) {
        if (o == null || o == JSONObject.NULL) return def;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return def; }
    }
}
