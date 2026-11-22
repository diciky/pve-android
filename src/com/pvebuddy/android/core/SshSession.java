package com.pvebuddy.android.core;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * SSH 会话（JSch 0.1.55）：PTY shell、输出回调、写入。
 * 支持密码认证与 Ed25519/其他密钥（JSch 内置）。
 */
public final class SshSession {

    public interface Listener { void onLine(String text); void onState(String state, String detail); }

    private Session session;
    private ChannelShell channel;
    private OutputStream stdin;
    private volatile boolean closed;
    private Thread reader;

    /** 连接并启动 PTY shell（阻塞建立，输出异步回调） */
    public void connect(String host, int port, String username, String password,
                        String privateKeyPem, Listener listener) throws Exception {
        closed = false;
        JSch jsch = new JSch();
        if (privateKeyPem != null && !privateKeyPem.isEmpty()) {
            jsch.addIdentity("pve-key", privateKeyPem.getBytes(StandardCharsets.UTF_8), null, new byte[0]);
        }
        session = jsch.getSession(username, host, port);
        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }
        Properties cfg = new Properties();
        cfg.put("StrictHostKeyChecking", "no");
        cfg.put("PreferredAuthentications", "publickey,password,keyboard-interactive");
        session.setConfig(cfg);
        session.setTimeout(10000);
        AppLogger.get().info("SSH", "连接 " + username + "@" + host + ":" + port);
        session.connect();
        listener.onState("connected", host);

        channel = (ChannelShell) session.openChannel("shell");
        // PTY：100 列 x 32 行，xterm
        channel.setPtyType("xterm", 100, 32, 0, 0);
        channel.setAgentForwarding(false);
        InputStream out = channel.getInputStream();
        stdin = channel.getOutputStream();
        channel.connect(5000);

        reader = new Thread(() -> {
            byte[] buf = new byte[4096];
            StringBuilder pending = new StringBuilder();
            try {
                int n;
                while (!closed && (n = out.read(buf)) > 0) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    pending.append(chunk);
                    listener.onLine(pending.toString());
                    pending.setLength(0);
                }
            } catch (Exception e) {
                if (!closed) listener.onState("error", String.valueOf(e));
            }
            listener.onState("closed", "会话结束");
        }, "ssh-reader");
        reader.start();
        AppLogger.get().info("SSH", host + " 连接成功");
    }

    public void write(String data) {
        try {
            if (stdin != null) stdin.write(data.getBytes(StandardCharsets.UTF_8));
            if (stdin != null) stdin.flush();
        } catch (Exception e) {
            AppLogger.get().warn("SSH", "写入失败: " + e);
        }
    }

    public void resize(int cols, int rows) {
        try { if (channel != null) channel.setPtySize(cols, rows, 0, 0); } catch (Exception ignore) { }
    }

    public void close() {
        closed = true;
        try { if (channel != null && channel.isConnected()) channel.disconnect(); } catch (Exception ignore) { }
        try { if (session != null && session.isConnected()) session.disconnect(); } catch (Exception ignore) { }
        AppLogger.get().info("SSH", "会话关闭");
    }

    public boolean isConnected() {
        return channel != null && channel.isConnected() && !closed;
    }
}
