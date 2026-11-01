package mcsi;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/** agent 的日志：控制台 + 可选文件（启动器经常把游戏的 stdout 藏起来）。 */
public final class Log {
    private static volatile OutputStream file;
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS");

    private Log() {
    }

    public static void toFile(String path) {
        try {
            file = new FileOutputStream(path, true);
            info("日志写入 " + path);
        } catch (IOException e) {
            info("打开日志文件失败：" + e);
        }
    }

    public static void info(String msg) {
        String line = "[mcsi " + TS.format(new Date()) + "] " + msg;
        System.out.println(line);
        if (file != null) {
            try {
                file.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                file.flush();
            } catch (IOException ignored) {
                // 日志失败不影响注入端
            }
        }
    }

    public static void error(String msg, Throwable t) {
        info("ERROR " + msg + (t == null ? "" : " :: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
        if (t != null) {
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(4, st.length); i++) {
                info("    at " + st[i]);
            }
        }
    }
}
