package mcsi;

/**
 * 端到端测试用的「假 Minecraft 进程」：加载 mock 世界后挂住，
 * 让 McAttach 从外部 attach 进来 —— 验证「attach + loadAgent + 映射解析 + HTTP」全链路。
 */
public final class MockHost {
    public static void main(String[] args) throws Exception {
        String[] names = {"net.minecraft.client.Minecraft", "net.minecraft.class_310"};
        boolean loaded = false;
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n);
                c.getMethod("bootstrap").invoke(null);
                System.out.println("mock = " + n);
                loaded = true;
                break;
            } catch (Throwable ignored) {
                // 换下一个
            }
        }
        if (!loaded) {
            System.out.println("classpath 上没有 mock");
        }
        long pid = ProcessHandle.current().pid();
        System.out.println("MockHost ready pid=" + pid);
        System.out.flush();
        Thread.sleep(120000);
        System.out.println("MockHost exit");
    }
}
