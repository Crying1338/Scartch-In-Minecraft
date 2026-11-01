package mcsi;

import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * agent 入口。
 *
 * 由 loader/McAttach.java 用 com.sun.tools.attach 注入到 java(w).exe，
 * 之后在游戏进程内起一个 127.0.0.1 上的 HTTP 端点，Scratch 扩展直接打它。
 *
 * args 形如： port=8766;host=127.0.0.1;token=;log=C:\path\mcsi.log;ext=...;sb3=...
 */
public final class Agent {

    public static final String NAME = "ScratchMC";
    public static final String VERSION = "1.0.0";

    private static volatile Mc mc;
    private static volatile Cheat cheat;
    private static volatile HttpBridge bridge;
    private static volatile Instrumentation instrumentation;
    private static volatile int port = 8766;
    private static volatile String host = "127.0.0.1";

    private Agent() {
    }

    public static void premain(String args, Instrumentation inst) {
        boot(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        boot(args, inst);
    }

    private static synchronized void boot(String args, Instrumentation inst) {
        if (bridge != null) {
            Log.info(NAME + " 已经在跑了，端口 " + bridge.port() + "，忽略重复注入");
            return;
        }
        instrumentation = inst;
        Map<String, String> a = parseArgs(args);
        host = a.containsKey("host") ? a.get("host") : "127.0.0.1";
        port = (int) Json.lng(a.get("port"), 8766);
        String token = a.containsKey("token") ? a.get("token") : "";
        // 游戏的控制台经常被启动器藏起来，默认再写一份日志到临时目录
        if (a.containsKey("log")) {
            Log.toFile(a.get("log"));
        } else {
            Log.toFile(System.getProperty("java.io.tmpdir") + java.io.File.separator + "mcsi-agent.log");
        }

        Log.info("==================== " + NAME + " v" + VERSION + " 注入成功 ====================");
        Log.info("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
                + " / pid " + pid());

        mc = new Mc();
        mc.init(inst);
        cheat = new Cheat(mc);
        cheat.start();

        try {
            bridge = HttpBridge.start(host, port, token,
                    new Routes(mc, cheat, port).withFiles(a.get("ext"), a.get("sb3")));
        } catch (Throwable t) {
            Log.error("HTTP 起不来（端口被占？换 port=87xx 再试）", t);
            return;
        }
        startResolver(inst);
        Log.info("扩展地址：http://" + host + ":" + port + "/extension.js");
        Log.info("工程地址：http://" + host + ":" + port + "/project/ScratchMC.sb3");
    }

    /** 主菜单 / 世界重载都很常见：没就绪就每 3 秒重试一次映射解析。 */
    private static void startResolver(final Instrumentation inst) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (bridge != null) {
                    try {
                        if (!mc.ready) {
                            mc.init(inst);
                        }
                        Thread.sleep(mc.ready ? 5000 : 3000);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                }
            }
        }, "mcsi-resolver");
        t.setDaemon(true);
        t.start();
    }

    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    public static HttpBridge bridgeRef() {
        return bridge;
    }

    public static void markStopped() {
        bridge = null;
        if (cheat != null) {
            cheat.stop();
        }
    }

    public static long pid() {
        try {
            String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return Long.parseLong(n.substring(0, n.indexOf('@')));
        } catch (Throwable t) {
            return -1;
        }
    }

    static Map<String, String> parseArgs(String args) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (args == null) {
            return out;
        }
        for (String piece : args.split("[;,]")) {
            if (piece.trim().isEmpty()) {
                continue;
            }
            int eq = piece.indexOf('=');
            if (eq < 0) {
                out.put(piece.trim().toLowerCase(), "1");
            } else {
                out.put(piece.substring(0, eq).trim().toLowerCase(), piece.substring(eq + 1).trim());
            }
        }
        return out;
    }
}
