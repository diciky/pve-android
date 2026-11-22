package com.pvebuddy.android.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 持久化：PVE 连接 / SSH 主机 / 命令历史 / 偏好。
 * 密码与 SSH 密钥用 AES-GCM 加密后存 SharedPreferences（对等 iOS Keychain 语义）。
 */
public final class Store {

    public static final class SshHost {
        public String id, name, host, username;
        public int port = 22;
        public String note = "";
        public String secretEnc; // 加密存储的密码
    }

    private static Store instance;
    private final SharedPreferences prefs;
    private SecretKeySpec aesKey;

    public static Store get(Context ctx) {
        if (instance == null) instance = new Store(ctx.getApplicationContext());
        return instance;
    }

    private Store(Context ctx) {
        prefs = ctx.getSharedPreferences("pvebuddy", Context.MODE_PRIVATE);
        deriveKey();
    }

    private void deriveKey() {
        try {
            // 设备首次运行生成随机主密钥持久化；密码加密与之绑定
            String keyB64 = prefs.getString("master_key", null);
            byte[] raw;
            if (keyB64 == null) {
                raw = new byte[32];
                new SecureRandom().nextBytes(raw);
                prefs.edit().putString("master_key", android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)).apply();
            } else {
                raw = android.util.Base64.decode(keyB64, android.util.Base64.NO_WRAP);
            }
            aesKey = new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            AppLogger.get().error("Store", "主密钥初始化失败: " + e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            AppLogger.get().error("Store", "加密失败: " + e);
            return null;
        }
    }

    public String decrypt(String enc) {
        if (enc == null) return null;
        try {
            byte[] all = android.util.Base64.decode(enc, android.util.Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            AppLogger.get().error("Store", "解密失败: " + e);
            return null;
        }
    }

    // ---- PVE 连接 ----

    public void saveConnections(org.json.JSONArray arr) {
        prefs.edit().putString("pve_connections", arr.toString()).apply();
    }

    public org.json.JSONArray loadConnections() {
        try {
            return new org.json.JSONArray(prefs.getString("pve_connections", "[]"));
        } catch (Exception e) { return new org.json.JSONArray(); }
    }

    // ---- SSH 主机 ----

    public void saveSshHosts(org.json.JSONArray arr) {
        prefs.edit().putString("ssh_hosts", arr.toString()).apply();
    }

    public org.json.JSONArray loadSshHosts() {
        try {
            return new org.json.JSONArray(prefs.getString("ssh_hosts", "[]"));
        } catch (Exception e) { return new org.json.JSONArray(); }
    }

    // ---- 命令历史 / 收藏 ----

    public void addHistory(String connKey, String cmd) {
        try {
            JSONArray hist = new JSONArray(prefs.getString("hist_" + connKey, "[]"));
            // 去重 + 最前
            JSONArray n = new JSONArray();
            n.put(cmd);
            for (int i = 0; i < hist.length() && n.length() < 500; i++) {
                if (!cmd.equals(hist.getString(i))) n.put(hist.getString(i));
            }
            prefs.edit().putString("hist_" + connKey, n.toString()).apply();
        } catch (Exception ignore) { }
    }

    public String[] history(String connKey) {
        try {
            JSONArray hist = new JSONArray(prefs.getString("hist_" + connKey, "[]"));
            String[] out = new String[hist.length()];
            for (int i = 0; i < hist.length(); i++) out[i] = hist.getString(i);
            return out;
        } catch (Exception e) { return new String[0]; }
    }

    public void saveFavorites(org.json.JSONArray arr) {
        prefs.edit().putString("favorites", arr.toString()).apply();
    }

    public org.json.JSONArray loadFavorites() {
        try { return new org.json.JSONArray(prefs.getString("favorites", "[]")); }
        catch (Exception e) { return new org.json.JSONArray(); }
    }

    // ---- 偏好 ----

    public boolean bool(String key, boolean def) { return prefs.getBoolean(key, def); }
    public void put(String key, boolean v) { prefs.edit().putBoolean(key, v).apply(); }
    public String str(String key, String def) { return prefs.getString(key, def); }
    public void put(String key, String v) { prefs.edit().putString(key, v).apply(); }
}
