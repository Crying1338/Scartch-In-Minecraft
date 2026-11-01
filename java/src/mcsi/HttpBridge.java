package mcsi;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 HTTP/1.1 服务（只用 java.base）。
 *
 * 为什么不用 com.sun.net.httpserver：agent 是被 attach 进游戏 JVM 的，
 * 那边的 boot layer 不一定解析了 jdk.httpserver；自己写 200 行 socket 更稳。
 */
public final class HttpBridge {

    public interface Handler {
        Reply handle(Req req) throws Exception;
    }

    public static final class Req {
        public String method = "GET";
        public String path = "/";
        public Map<String, String> query = new LinkedHashMap<String, String>();
        public Map<String, String> headers = new LinkedHashMap<String, String>();
        public String body = "";
        public Map<String, String> json = new LinkedHashMap<String, String>();

        /** 参数：先看 JSON 体，再看 query。 */
        public String str(String key, String fallback) {
            String v = json.containsKey(key) ? json.get(key) : query.get(key);
            return v == null || v.isEmpty() ? fallback : v;
        }

        public double dbl(String key, double fallback) {
            return Json.dbl(str(key, null), fallback);
        }

        public boolean on(String key, boolean fallback) {
            if (!json.containsKey(key) && !query.containsKey(key)) {
                return fallback;
            }
            return Json.truthy(str(key, ""));
        }
    }

    public static final class Reply {
        public int status = 200;
        public String type = "application/json; charset=utf-8";
        public byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        public static Reply json(String text) {
            Reply r = new Reply();
            r.body = text.getBytes(StandardCharsets.UTF_8);
            return r;
        }

        public static Reply text(String text, String type) {
            Reply r = new Reply();
            r.type = type + "; charset=utf-8";
            r.body = text.getBytes(StandardCharsets.UTF_8);
            return r;
        }

        public static Reply bytes(byte[] data, String type) {
            Reply r = new Reply();
            r.type = type;
            r.body = data;
            return r;
        }

        public static Reply error(int status, String message) {
            Reply r = new Reply();
            r.status = status;
            r.body = Json.obj().bool("ok", false).put("error", message).done().getBytes(StandardCharsets.UTF_8);
            return r;
        }
    }

    private final ServerSocket server;
    private final Handler handler;
    private final String token;
    private volatile boolean running = true;

    private final java.util.Map<String, Long> accessLog =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    private HttpBridge(ServerSocket server, String token, Handler handler) {
        this.server = server;
        this.token = token;
        this.handler = handler;
    }

    public static HttpBridge start(String host, int port, String token, Handler handler) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(host, port), 32);
        HttpBridge bridge = new HttpBridge(ss, token, handler);
        bridge.acceptLoop();
        Log.info("HTTP 监听 http://" + host + ":" + bridge.port() + (token.isEmpty() ? "" : "（带口令）"));
        return bridge;
    }

    public int port() {
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // 关闭时不需要处理
        }
    }

    private void acceptLoop() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    Socket s = null;
                    try {
                        s = server.accept();
                        final Socket socket = s;
                        Thread worker = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                serve(socket);
                            }
                        }, "mcsi-http");
                        worker.setDaemon(true);
                        worker.start();
                    } catch (IOException e) {
                        if (running) {
                            Log.error("accept 失败", e);
                        }
                    }
                }
            }
        }, "mcsi-accept");
        t.setDaemon(true);
        t.start();
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(8000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            Req req = new Req();
            req.method = parts.length > 0 ? parts[0] : "GET";
            logAccess(req.method, parts.length > 1 ? parts[1] : "/");
            String target = parts.length > 1 ? parts[1] : "/";

            int q = target.indexOf('?');
            if (q >= 0) {
                req.path = target.substring(0, q);
                parseQuery(target.substring(q + 1), req.query);
            } else {
                req.path = target;
            }

            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    String k = line.substring(0, c).trim().toLowerCase();
                    String v = line.substring(c + 1).trim();
                    req.headers.put(k, v);
                    if ("content-length".equals(k)) {
                        try {
                            contentLength = Integer.parseInt(v);
                        } catch (NumberFormatException ignored) {
                            contentLength = 0;
                        }
                    }
                }
            }
            if (contentLength > 0) {
                byte[] buf = new byte[Math.min(contentLength, 1 << 20)];
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
                req.body = new String(buf, 0, off, StandardCharsets.UTF_8);
                req.json.putAll(Json.parse(req.body));
            }

            Reply reply;
            if ("OPTIONS".equalsIgnoreCase(req.method)) {
                reply = new Reply();
                reply.body = new byte[0];
            } else if (!authorized(req)) {
                reply = Reply.error(401, "口令不对：请求头 X-MC-Token");
            } else {
                try {
                    reply = handler.handle(req);
                } catch (Throwable t) {
                    Log.error("处理 " + req.path + " 失败", t);
                    reply = Reply.error(500, String.valueOf(t));
                }
                if (reply == null) {
                    reply = Reply.error(404, "没有这个接口：" + req.path);
                }
            }

            byte[] head = ("HTTP/1.1 " + reply.status + " " + statusText(reply.status) + "\r\n"
                    + "Content-Type: " + reply.type + "\r\n"
                    + "Content-Length: " + reply.body.length + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Access-Control-Allow-Headers: Content-Type, X-MC-Token\r\n"
                    + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                    + "Access-Control-Allow-Private-Network: true\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            out.write(head);
            out.write(reply.body);
            out.flush();
        } catch (IOException e) {
            // 客户端提前断开很常见
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    /** 1 秒节流的访问日志：排查「TurboWarp 到底有没有来取扩展」用。 */
    private void logAccess(String method, String target) {
        int q = target.indexOf('?');
        String path = q >= 0 ? target.substring(0, q) : target;
        String key = method + " " + path;
        long now = System.currentTimeMillis();
        Long prev = accessLog.get(key);
        if (prev != null && now - prev < 1000) {
            return;
        }
        accessLog.put(key, now);
        if (accessLog.size() > 200) {
            accessLog.clear();
        }
        Log.info("→ " + key);
    }

    private boolean authorized(Req req) {
        if (token == null || token.isEmpty()) {
            return true;
        }
        // 扩展脚本和 sb3 要能被 TurboWarp 直接拉取
        if (req.path.equals("/extension.js") || req.path.startsWith("/project/") || req.path.equals("/ping")) {
            return true;
        }
        return token.equals(req.headers.get("x-mc-token"));
    }

    private static void parseQuery(String s, Map<String, String> out) {
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(urlDecode(k), urlDecode(v));
        }
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
        }
        if (c < 0 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String statusText(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 404:
                return "Not Found";
            case 500:
                return "Internal Server Error";
            default:
                return "OK";
        }
    }
}
