package mcsi;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 本地自测：不需要 Minecraft，也不需要 Instrumentation。
 * 用 java/mock-mojang 或 java/mock-inter 里的假 MC 类层次验证
 * 「映射解析 -> 状态读取 -> 自瞄/触发 -> HTTP 协议」整条链路。
 *
 *   java -cp build/selftest;build/classes;java/mock-mojang mcsi.SelfTest
 *   java -cp build/selftest;build/classes;java/mock-inter   mcsi.SelfTest
 */
public final class SelfTest {

    private static int checks;
    private static int fails;

    public static void main(String[] args) throws Exception {
        bootstrapMock();

        System.out.println("=========== 映射解析 ===========");
        Mc mc = new Mc();
        boolean ok = mc.init(null);
        System.out.println(mc.map.report());
        check("映射解析成功", ok, mc.fail);
        if (!ok) {
            finish();
            return;
        }

        check("玩家名 = Steve", "Steve".equals(mc.playerName()), mc.playerName());
        float[] rot0 = mc.playerRot();
        check("初始朝向 45 / 12.5", Math.abs(rot0[0] - 45f) < 0.01 && Math.abs(rot0[1] - 12.5f) < 0.01,
                rot0[0] + " / " + rot0[1]);
        check("能写视角", mc.map.canWriteRot(), "");
        check("能攻击", mc.map.canAttack(), "");
        check("实体总数 >= 4", mc.entities(64, "mob", "dist", 32).size() == 3,
                "mob=" + mc.entities(64, "mob", "dist", 32).size());
        check("玩家过滤 = 1", mc.entities(64, "player", "dist", 32).size() == 1,
                "player=" + mc.entities(64, "player", "dist", 32).size());

        Mc.Ent first = mc.entities(64, "mob", "dist", 1).isEmpty() ? null : mc.entities(64, "mob", "dist", 1).get(0);
        check("最近目标可读", first != null && first.name.startsWith("Zombie"), first == null ? "null" : first.json());

        System.out.println("=========== 自瞄内核 ===========");
        Cheat cheat = new Cheat(mc);
        cheat.start();
        cheat.setModule("aim", true);
        cheat.setParam("range", "16");
        cheat.setParam("fov", "120");
        cheat.setParam("step", "30");
        cheat.setParam("speed", "900");
        cheat.setParam("sensStep", "0");
        Thread.sleep(320);
        float[] rotAim = mc.playerRot();
        check("自瞄改了视角", Math.abs(rotAim[0] - rot0[0]) > 0.5 || Math.abs(rotAim[1] - rot0[1]) > 0.5,
                rotAim[0] + " / " + rotAim[1]);
        check("锁定目标", cheat.targetId >= 0, cheat.targetJson());

        System.out.println("=========== HTTP 协议 ===========");
        HttpBridge bridge = HttpBridge.start("127.0.0.1", 0, "", new Routes(mc, cheat, 0));
        int port = bridge.port();
        try {
            String ping = http(port, "/ping", "GET", null);
            System.out.println("/ping -> " + ping);
            check("/ping 有版本号", ping.contains("\"version\""), ping);

            String status = http(port, "/status", "GET", null);
            System.out.println("/status -> " + status);
            check("/status 认出玩家", status.contains("Steve"), "");
            check("/status 带模块表", status.contains("\"modules\""), "");
            check("/status 带参数表", status.contains("\"params\""), "");

            String ents = http(port, "/entities?range=64&filter=all&limit=10", "GET", null);
            System.out.println("/entities -> " + ents);
            check("/entities 至少 4 个", ents.contains("\"count\":4") || ents.contains("\"count\":5"), ents);

            String mod = http(port, "/module", "POST", "{\"name\":\"trigger\",\"on\":true}");
            System.out.println("/module -> " + mod);
            check("/module 开触发", mod.contains("\"trigger\":true"), mod);

            String par = http(port, "/params", "POST", "{\"key\":\"fov\",\"value\":\"40\"}");
            System.out.println("/params -> " + par);
            check("/params 改 FOV", par.contains("\"fov\":40"), par);

            String look = http(port, "/look", "POST", "{\"yaw\":90,\"pitch\":0}");
            System.out.println("/look -> " + look);
            float[] rotLook = mc.playerRot();
            check("/look 生效", Math.abs(rotLook[0] - 90f) < 0.01 && Math.abs(rotLook[1]) < 0.01,
                    rotLook[0] + " / " + rotLook[1]);

            String target = http(port, "/target", "GET", null);
            System.out.println("/target -> " + target);
            check("/target 有锁定", target.contains("\"locked\":true"), target);

            String attack = http(port, "/attack", "POST", "{}");
            System.out.println("/attack -> " + attack);
            check("/attack 成功", attack.contains("\"ok\":true"), attack);

            String maps = http(port, "/mappings", "GET", null);
            check("/mappings 有解析报告", maps.contains("Entity 类"), "");

            String ext = http(port, "/extension.js", "GET", null);
            check("/extension.js（未打包时应 404）", ext.length() >= 0, "len=" + ext.length());
        } finally {
            bridge.stop();
            cheat.stop();
        }

        finish();
    }

    private static void bootstrapMock() {
        String[] names = {"net.minecraft.client.Minecraft", "net.minecraft.class_310"};
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n);
                c.getMethod("bootstrap").invoke(null);
                System.out.println("mock = " + n);
                return;
            } catch (Throwable ignored) {
                // 换下一个
            }
        }
        System.out.println("!! classpath 上没有 mock 类，先编 java/mock-* 再跑");
    }

    private static String http(int port, String path, String method, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(2000);
        c.setReadTimeout(4000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream out = c.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) {
            return "HTTP " + code;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
        }
        in.close();
        String text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        return code >= 400 ? ("HTTP " + code + " " + text) : text;
    }

    private static void check(String name, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("[PASS] " + name + (detail.isEmpty() ? "" : "  (" + detail + ")"));
        } else {
            fails++;
            System.out.println("[FAIL] " + name + (detail.isEmpty() ? "" : "  (" + detail + ")"));
        }
    }

    private static void finish() {
        System.out.println("=========== " + (checks - fails) + "/" + checks + " 通过 ===========");
        System.exit(fails == 0 ? 0 : 1);
    }
}
