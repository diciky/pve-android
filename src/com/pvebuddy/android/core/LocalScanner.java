package com.pvebuddy.android.core;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.net.Socket;
import java.net.InetSocketAddress;

/**
 * 局域网 PVE 扫描器（Java 对等 iOS 版，含全部修复经验）：
 * - NetworkInterface 枚举：只认 wlan、eth、ap 开头的接口，排除蜂窝（rmnet）、VPN（tun）、lo、awdl
 * - 并发 TCP 探测 8006 + HTTPS Proxmox 特征验证
 * - 支持手动指定网段
 */
public final class LocalScanner {

    public static final class Candidate {
        public final String iface, ip, prefix;
        public Candidate(String i, String ip, String p) { iface = i; this.ip = ip; prefix = p; }
        public String label() { return iface + " · " + ip + " → " + prefix + ".0/24"; }
    }

    public static final class Discovered {
        public final String ip;
        public final boolean verified;
        public Discovered(String ip, boolean v) { this.ip = ip; this.verified = v; }
    }

    public static final int PVE_PORT = 8006;
    public interface Progress { void on(String status, double progress, List<Discovered> found); }

    /** 枚举可扫描接口（排除蜂窝 rmnet**、VPN tun**、lo） */
    public static List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            List<NetworkInterface> list = Collections.list(nis);
            for (NetworkInterface ni : list) {
                String name = ni.getName() == null ? "" : ni.getName();
                if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("ppp")
                        || name.equals("lo") || name.startsWith("dummy") || name.startsWith("veth")
                        || name.startsWith("docker") || name.startsWith("br-") || name.startsWith("virbr")
                        || name.startsWith("vnic")) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                for (InetAddress a : Collections.list(addrs)) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        String ip = a.getHostAddress();
                        String[] o = ip.split("\\.");
                        if (o.length == 4) {
                            out.add(new Candidate(name, ip, o[0] + "." + o[1] + "." + o[2]));
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.get().warn("Scanner", "枚举网络接口失败: " + e);
        }
        return out;
    }

    public static boolean isValidPrefix(String s) {
        if (s == null) return false;
        String[] parts = s.split("\\.");
        if (parts.length != 3) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            if (p.length() > 1 && p.charAt(0) == '0') return false;
            try {
                int n = Integer.parseInt(p);
                if (n < 0 || n > 255) return false;
            } catch (Exception e) { return false; }
        }
        return true;
    }

    /**
     * 扫描。customPrefix 为 null 时用第一个 wlan 接口（优先）所在 /24。
     * 阻塞式，配合线程 + Progress 回调使用。
     */
    public static List<Discovered> scan(String customPrefix, Progress progress) {
        String prefix = customPrefix;
        if (prefix == null || prefix.trim().isEmpty()) {
            List<Candidate> cs = candidates();
            if (cs.isEmpty()) {
                progress.on("无法获取局域网地址，请确认已连接 Wi-Fi，或手动指定网段", 0, new ArrayList<>());
                return new ArrayList<>();
            }
            Candidate first = null;
            for (Candidate c : cs) { if (c.iface.startsWith("wlan")) { first = c; break; } }
            if (first == null) first = cs.get(0);
            prefix = first.prefix;
            progress.on("扫描网段 " + prefix + ".1–254（" + first.ip + " @ " + first.iface + "）", 0, new ArrayList<>());
        } else {
            prefix = prefix.trim();
            progress.on("手动指定网段 " + prefix + ".1–254", 0, new ArrayList<>());
        }
        AppLogger.get().info("Scanner", "开始扫描 " + prefix + ".0/24:8006");

        List<Discovered> found = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        CountDownLatch latch = new CountDownLatch(254);
        for (int i = 1; i <= 254; i++) {
            final String host = prefix + "." + i;
            pool.execute(() -> {
                try {
                    if (portOpen(host, PVE_PORT, 800)) {
                        boolean v = verifyProxmox(host);
                        Discovered d = new Discovered(host, v);
                        found.add(d);
                        progress.on("已发现 " + found.size() + " 台", done.get() / 254.0, new ArrayList<>(found));
                    }
                } finally {
                    done.incrementAndGet();
                    latch.countDown();
                    progress.on("扫描 8006 端口… " + (done.get() * 100 / 254) + "%",
                            done.get() / 254.0, new ArrayList<>(found));
                }
            });
        }
        try { latch.await(45, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
        pool.shutdownNow();

        found.sort((a, b) -> Boolean.compare(b.verified, a.verified));
        AppLogger.get().info("Scanner", "扫描结束：发现 " + found.size() + " 台");
        progress.on(found.isEmpty() ? "扫描完成，未发现 PVE 服务器"
                : "扫描完成，发现 " + found.size() + " 台", 1.0, new ArrayList<>(found));
        return found;
    }

    private static boolean portOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static volatile javax.net.ssl.SSLSocketFactory trustAllFactory;

    /** HTTPS GET 验证 Proxmox 特征 */
    public static boolean verifyProxmox(String ip) {
        try {
            TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String t) { }
                public void checkServerTrusted(X509Certificate[] c, String t) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            if (trustAllFactory == null) {
                synchronized (LocalScanner.class) {
                    if (trustAllFactory == null) {
                        SSLContext ctx = SSLContext.getInstance("TLS");
                        ctx.init(null, new TrustManager[]{tm}, null);
                        trustAllFactory = ctx.getSocketFactory();
                    }
                }
            }
            HostnameVerifier hv = (h, s) -> true;
            HttpURLConnection c = (HttpURLConnection) new java.net.URL("https://" + ip + ":" + PVE_PORT + "/").openConnection();
            if (c instanceof HttpsURLConnection) {
                ((HttpsURLConnection) c).setSSLSocketFactory(trustAllFactory);
                ((HttpsURLConnection) c).setHostnameVerifier(hv);
            }
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            int code = c.getResponseCode();
            String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream()).toLowerCase();
            return code >= 200 && code < 500 && (body.contains("proxmox") || body.contains("pve") || body.contains("novnc"));
        } catch (Exception e) {
            return false;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try (InputStream is = in) {
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
