import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 agent 注入到 java(w).exe。
 *
 *   java --add-modules jdk.attach -cp loader McAttach --list
 *   java --add-modules jdk.attach -cp loader McAttach --pid 1234 --jar java/build/mcsi-agent.jar --port 8766
 *
 * 不传 --pid 时按主类名猜 Minecraft（net.minecraft.main / KnotClient / BootstrapLauncher）。
 * 注入需要和目标进程同一权限级别：游戏以管理员跑，这里也要管理员。
 */
public final class McAttach {

    private static final String[] HINTS = {
            "net.minecraft.client.main.main",
            "net.minecraft.main",
            "knotclient",
            "bootstraplauncher",
            "fabric",
            "forge",
            "minecraft",
    };

    public static void main(String[] args) throws Exception {
        String pid = null;
        String jar = null;
        String port = "8791";
        String token = "";
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--pid".equals(a) && i + 1 < args.length) {
                pid = args[++i];
            } else if ("--jar".equals(a) && i + 1 < args.length) {
                jar = args[++i];
            } else if ("--port".equals(a) && i + 1 < args.length) {
                port = args[++i];
            } else if ("--token".equals(a) && i + 1 < args.length) {
                token = args[++i];
            } else if ("--list".equals(a)) {
                list();
                return;
            } else if ("--help".equals(a) || "-h".equals(a)) {
                usage();
                return;
            }
        }

        if (jar == null) {
            jar = "java/build/mcsi-agent.jar";
        }
        File jarFile = new File(jar);
        if (!jarFile.isFile()) {
            System.out.println("找不到 agent jar：" + jarFile.getAbsolutePath());
            System.out.println("先跑一遍 build.ps1，或者用 --jar 指到实际位置");
            return;
        }

        List<VirtualMachineDescriptor> vms = VirtualMachine.list();
        if (pid == null) {
            printVms(vms);
            pid = pick(vms);
            if (pid == null) {
                System.out.println();
                System.out.println("没自动认出 Minecraft 进程；从上面列表里挑一个用 --pid 指定");
                return;
            }
            System.out.println("自动选择 pid = " + pid);
        }

        String opts = "port=" + port + (token.isEmpty() ? "" : ";token=" + token);
        System.out.println("attach " + pid + "  agent=" + jarFile.getAbsolutePath() + "  args=" + opts);

        // 目标 JVM 刚起来时，第一次 loadAgent 有时会「返回成功但 agentmain 没跑」。
        // 所以注入完必须自己探一下端口，不通就重试。
        int portNo = 8791;
        try {
            for (String piece : opts.split(";")) {
                if (piece.startsWith("port=")) {
                    portNo = Integer.parseInt(piece.substring(5).trim());
                }
            }
        } catch (Throwable ignored) {
            portNo = 8791;
        }

        boolean ready = false;
        for (int attempt = 1; attempt <= 3 && !ready; attempt++) {
            try {
                VirtualMachine vm = VirtualMachine.attach(pid);
                try {
                    vm.loadAgent(jarFile.getAbsolutePath(), opts);
                } finally {
                    vm.detach();
                }
            } catch (Throwable t) {
                System.out.println("attach failed: " + t);
                System.out.println("  - pid alive?      run --list again");
                System.out.println("  - privilege?      game started as admin => run this as admin too");
                System.out.println("  - attach blocked? -XX:+DisableAttachMechanism or hardened launcher");
                return;
            }
            for (int i = 0; i < 20 && !ready; i++) {
                ready = probe(portNo, 400);
                if (!ready) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            if (!ready) {
                System.out.println("  第 " + attempt + " 次注入没生效（端口 " + portNo + " 没起来），重试...");
            }
        }

        if (!ready) {
            System.out.println("注入没生效：agentmain 没跑起来。");
            System.out.println("  - 目标进程是不是已经被注入过又把端口关了？先 /status 看看");
            System.out.println("  - 端口 " + portNo + " 被别的程序占了？换 --port 8792");
            System.out.println("  - 看目标进程的控制台/日志里的 [mcsi] 行");
            return;
        }

        System.out.println("注入完成，HTTP 已就绪。");
        System.out.println("  状态: http://127.0.0.1:" + portNo + "/status");
        System.out.println("  扩展: http://127.0.0.1:" + portNo + "/extension.js");
        System.out.println("  工程: http://127.0.0.1:" + portNo + "/project/ScratchMC.scb3".replace(".scb3", ".sb3"));
        System.out.println();
        System.out.println("  用浏览器打开 /extension.js 应该看到 JS 源码；");
        System.out.println("  只要这里返回的是 JSON 或连不上，TurboWarp 就会报 Error in sandboxed script。");
    }

    /** 直接打一次 /ping，确认注入端真的在听。 */
    private static boolean probe(int port, int timeoutMs) {
        java.net.Socket socket = new java.net.Socket();
        try {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            java.io.OutputStream out = socket.getOutputStream();
            out.write(("GET /ping HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n")
                    .getBytes("UTF-8"));
            out.flush();
            byte[] buf = new byte[1024];
            int n = socket.getInputStream().read(buf);
            return n > 0 && new String(buf, 0, n, "UTF-8").contains("200");
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static void list() {
        printVms(VirtualMachine.list());
    }

    private static void printVms(List<VirtualMachineDescriptor> vms) {
        System.out.println("可注入的 JVM：");
        for (VirtualMachineDescriptor d : vms) {
            System.out.println("  pid=" + d.id() + "  " + d.displayName());
        }
        System.out.println();
    }

    private static String pick(List<VirtualMachineDescriptor> vms) {
        List<String> hits = new ArrayList<String>();
        for (VirtualMachineDescriptor d : vms) {
            String name = d.displayName() == null ? "" : d.displayName().toLowerCase();
            for (String h : HINTS) {
                if (name.contains(h)) {
                    hits.add(d.id());
                    break;
                }
            }
        }
        if (hits.size() == 1) {
            return hits.get(0);
        }
        if (hits.size() > 1) {
            System.out.println("认出多个候选：" + hits + "，请用 --pid 指定");
        }
        return null;
    }

    private static void usage() {
        System.out.println("用法：");
        System.out.println("  McAttach --list");
        System.out.println("  McAttach [--pid 1234] [--jar java/build/mcsi-agent.jar] [--port 8766] [--token 口令]");
    }
}
