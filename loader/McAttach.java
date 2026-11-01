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
        System.out.println("注入完成。");
        System.out.println("  状态: http://127.0.0.1:" + port + "/status");
        System.out.println("  扩展: http://127.0.0.1:" + port + "/extension.js");
        System.out.println("  工程: http://127.0.0.1:" + port + "/project/ScratchMC.sb3");
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
