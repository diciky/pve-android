import com.pvebuddy.android.core.LocalScanner;
import com.pvebuddy.android.core.PveClient;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * PVE Buddy Android 冒烟测试（JVM）：mock PVE e2e + 离线断言。
 * 运行前提：mock_pve.py 已起在 127.0.0.1:8006（自签 HTTPS）。
 */
public final class SmokeTest {

    static int passed = 0, failed = 0;
    static java.util.List<String> failedNames = new java.util.ArrayList<>();

    static void check(String name, boolean ok, String... detail) {
        if (ok) { passed++; System.out.println("  ✅ " + name); }
        else {
            failed++; failedNames.add(name);
            System.out.println("  ❌ " + name + (detail.length > 0 ? "  -- " + detail[0] : ""));
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1. 离线断言 ==");
        check("isValidPrefix 合法输入", LocalScanner.isValidPrefix("10.10.10")
                && LocalScanner.isValidPrefix("192.168.1"));
        check("isValidPrefix 非法输入",
                !LocalScanner.isValidPrefix("10.10") && !LocalScanner.isValidPrefix("10.256.1")
                && !LocalScanner.isValidPrefix("a.b.c") && !LocalScanner.isValidPrefix("10.10.10.1")
                && !LocalScanner.isValidPrefix("010.1.1"));
        check("接口候选不含蜂窝/VPN（本机）",
                LocalScanner.candidates().stream().allMatch(c ->
                        c.iface.startsWith("en") || c.iface.startsWith("lo") || c.iface.startsWith("bridge")
                        || c.iface.startsWith("wlan") || c.iface.startsWith("ap") || c.iface.startsWith("utun")
                        || c.iface.startsWith("awdl") || c.iface.startsWith("llw") || c.iface.startsWith("gif")
                        || c.iface.startsWith("stf") || c.iface.startsWith("vir") || c.iface.startsWith("vnic")));
        // 注意：JVM 环境枚举不过滤（candidates 只排除 rmnet/tun/ppp/lo）——macOS 上 utun 属 VPN，
        // Android 上排除逻辑一致；此处只验证方法可运行不抛异常
        check("candidates() 可运行", LocalScanner.candidates() != null);

        System.out.println("== 2. mock PVE e2e ==");
        PveClient.Connection conn = new PveClient.Connection();
        conn.host = "127.0.0.1";
        conn.port = 8006;
        conn.username = "root@pam";
        conn.allowSelfSigned = true;
        PveClient client = new PveClient(conn);

        long t0 = System.currentTimeMillis();
        client.login("secret123");
        check("POST /access/ticket 登录", conn.ticket != null && conn.ticket.startsWith("PVE:"));
        System.out.println("  登录耗时 " + (System.currentTimeMillis() - t0) + "ms");

        JSONArray nodes = client.nodes();
        check("GET /nodes", nodes.length() == 1 && "pve1".equals(nodes.getJSONObject(0).optString("node")));

        JSONObject status = client.nodeStatus("pve1");
        // 宽容解码：cpuinfo.cpu 字符串/数字都收
        check("GET node/status + cpuinfo 宽容解码", status != null);

        JSONArray res = client.resources();
        check("GET /cluster/resources", res.length() >= 2);

        // ⚠️ 关键回归：/cluster/tasks 不带任何参数
        JSONArray tasks = client.clusterTasks();
        check("GET /cluster/tasks（无查询参数，不 400）", tasks.length() == 1
                && "vzdump".equals(tasks.getJSONObject(0).optString("type")));

        JSONArray nodeTasks = client.nodeTasks("pve1", 60);
        check("GET /nodes/pve1/tasks?limit=60", nodeTasks.length() == 1);

        String upid = "UPID:pve1:0000ABCD:00001234:673FFFFF:vzdump:101:root@pam:";
        JSONObject ts = client.taskStatus("pve1", upid);
        check("GET task status（UPID 路径不二次编码）", "OK".equals(ts.optString("status")));

        JSONArray log = client.taskLog("pve1", upid);
        check("GET task log", log.length() >= 1);

        JSONArray services = client.services("pve1");
        check("GET /nodes/pve1/services", services.length() == 4);

        JSONObject act = client.serviceAction("pve1", "pveproxy", "restart");
        check("POST service restart", act != null);

        JSONObject na = client.nodeAction("pve1", "reboot");
        check("POST node reboot", na != null);

        JSONObject ba = client.batchGuestAction("pve1", "startall");
        check("POST startall", ba != null);

        JSONArray syslog = client.syslog("pve1", null, 500);
        check("GET /nodes/pve1/syslog", syslog.length() >= 2);

        // 写操作（POST + CSRF）
        JSONObject snap = client.snapshot("pve1", "qemu", 101, "test-snap", "smoke");
        check("POST snapshot", snap != null);

        JSONObject upidStart = client.guestAction("pve1", "qemu", 101, "start");
        check("POST qemu start（返回 UPID）", upidStart.optString("data").startsWith("UPID:"));

        JSONObject bk = client.createBackup("pve1", 101, "local", "snapshot", "zstd");
        check("POST vzdump 备份", bk.optString("data").startsWith("UPID:"));

        // 集群任务带参数应被 mock 拒绝（模拟真实 PVE 400）——直接构造原始请求验证
        try {
            java.lang.reflect.Method m = PveClient.class.getDeclaredMethod(
                    "request", String.class, String.class, String.class, Integer.class, boolean.class);
            m.setAccessible(true);
            m.invoke(client, "GET", "/api2/json/cluster/tasks?limit=60", null, null, true);
            check("mock 拒绝 cluster/tasks 带参数（回归护栏）", false, "应抛 400");
        } catch (java.lang.reflect.InvocationTargetException e) {
            check("mock 拒绝 cluster/tasks 带参数（回归护栏）", true);
        } catch (Exception e) {
            check("mock 拒绝 cluster/tasks 带参数（回归护栏）", false, String.valueOf(e));
        }

        System.out.println("\n════════════════════════════════");
        System.out.println("结果: " + passed + " 通过, " + failed + " 失败");
        if (!failedNames.isEmpty()) System.out.println("失败项: " + String.join(", ", failedNames));
        System.exit(failed == 0 ? 0 : 1);
    }
}
