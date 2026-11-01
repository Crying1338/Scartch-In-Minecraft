package mcsi;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * HTTP 路由表：agent 和 SelfTest 共用同一份，保证「本地自测通过」和「真注入」行为一致。
 */
public final class Routes implements HttpBridge.Handler {

    private final Mc mc;
    private final Cheat cheat;
    private final int port;
    private volatile String extDiskPath = "";
    private volatile String sb3DiskPath = "";

    public Routes(Mc mc, Cheat cheat, int port) {
        this.mc = mc;
        this.cheat = cheat;
        this.port = port;
    }

    public Routes withFiles(String extensionPath, String sb3Path) {
        this.extDiskPath = extensionPath == null ? "" : extensionPath;
        this.sb3DiskPath = sb3Path == null ? "" : sb3Path;
        return this;
    }

    @Override
    public HttpBridge.Reply handle(HttpBridge.Req req) throws Exception {
        String p = req.path;

        if (p.equals("/ping") || p.equals("/")) {
            return HttpBridge.Reply.json(Json.obj()
                    .bool("ok", true)
                    .put("name", Agent.NAME)
                    .put("version", Agent.VERSION)
                    .num("pid", Agent.pid())
                    .num("port", port)
                    .put("jvm", System.getProperty("java.vm.name"))
                    .put("path", p)
                    .done());
        }

        if (p.equals("/extension.js")) {
            return staticFile("web/extension.js", extDiskPath, "application/javascript; charset=utf-8");
        }
        if (p.equals("/project/ScratchMC.sb3") || p.equals("/project/project.sb3")) {
            return staticFile("web/ScratchMC.sb3", sb3DiskPath, "application/octet-stream");
        }

        if (p.equals("/status")) {
            return HttpBridge.Reply.json(Json.obj()
                    .bool("ok", true)
                    .put("name", Agent.NAME)
                    .put("version", Agent.VERSION)
                    .num("pid", Agent.pid())
                    .num("port", port)
                    .raw("game", mc.statusJson())
                    .raw("modules", cheat.modulesJson())
                    .raw("params", cheat.paramsJson())
                    .raw("target", cheat.targetJson())
                    .raw("pulse", cheat.pulseJson())
                    .done());
        }

        if (p.equals("/player")) {
            double[] pos = mc.playerPos();
            float[] rot = mc.playerRot();
            return HttpBridge.Reply.json(Json.obj()
                    .bool("ok", true)
                    .put("name", mc.playerName())
                    .raw("pos", "[" + Json.num(pos[0]) + "," + Json.num(pos[1]) + "," + Json.num(pos[2]) + "]")
                    .raw("rot", "[" + Json.num(rot[0]) + "," + Json.num(rot[1]) + "]")
                    .done());
        }

        if (p.equals("/entities")) {
            double range = req.dbl("range", cheat.range);
            String filter = req.str("filter", cheat.targetFilter);
            String sort = req.str("sort", "dist");
            int limit = (int) Json.lng(req.str("limit", "32"), 32);
            List<Mc.Ent> list = mc.entities(range, filter, sort, Math.min(limit, 256));
            List<String> items = Json.list();
            for (Mc.Ent e : list) {
                items.add(e.json());
            }
            return HttpBridge.Reply.json(Json.obj()
                    .bool("ok", true)
                    .num("count", list.size())
                    .raw("entities", Json.arr(items))
                    .done());
        }

        if (p.equals("/target")) {
            return HttpBridge.Reply.json(Json.obj().bool("ok", true).raw("target", cheat.targetJson()).done());
        }

        if (p.equals("/pulse")) {
            return HttpBridge.Reply.json(Json.obj().bool("ok", true).raw("pulse", cheat.pulseJson()).done());
        }

        if (p.equals("/mappings")) {
            return HttpBridge.Reply.json(Json.obj()
                    .bool("ok", true)
                    .put("report", mc.map.report())
                    .put("fail", mc.fail)
                    .done());
        }

        if (p.equals("/module")) {
            String name = req.str("name", "");
            boolean on = req.on("on", Json.truthy(req.str("value", "false")));
            String note = cheat.setModule(name, on);
            return HttpBridge.Reply.json(Json.obj()
                    .bool("ok", true).put("note", note).raw("modules", cheat.modulesJson()).done());
        }

        if (p.equals("/params")) {
            String note = cheat.setParam(req.str("key", ""), req.str("value", ""));
            return HttpBridge.Reply.json(Json.obj()
                    .bool("ok", true).put("note", note).raw("params", cheat.paramsJson()).done());
        }

        if (p.equals("/look")) {
            boolean relative = req.json.containsKey("dyaw") || req.json.containsKey("dpitch")
                    || req.query.containsKey("dyaw");
            float[] cur = mc.playerRot();
            float yaw;
            float pitch;
            if (relative) {
                yaw = (float) (cur[0] + req.dbl("dyaw", 0));
                pitch = (float) (cur[1] + req.dbl("dpitch", 0));
            } else {
                yaw = (float) req.dbl("yaw", cur[0]);
                pitch = (float) req.dbl("pitch", cur[1]);
            }
            boolean ok = mc.setRot(yaw, pitch);
            float[] now = mc.playerRot();
            return HttpBridge.Reply.json(Json.obj()
                    .bool("ok", ok)
                    .raw("rot", "[" + Json.num(now[0]) + "," + Json.num(now[1]) + "]")
                    .put("note", ok ? "已转向" : "这个映射下写不了视角")
                    .done());
        }

        if (p.equals("/attack")) {
            boolean ok = mc.attack(cheat.snapshotTarget());
            return HttpBridge.Reply.json(Json.obj()
                    .bool("ok", ok)
                    .put("note", ok ? "已攻击 " + cheat.targetName : "没有可攻击的目标")
                    .done());
        }

        if (p.equals("/resolve")) {
            boolean ok = mc.init(Agent.instrumentation());
            return HttpBridge.Reply.json(Json.obj()
                    .bool("ok", ok)
                    .raw("game", mc.statusJson())
                    .put("report", mc.map.report())
                    .done());
        }

        if (p.equals("/shutdown")) {
            cheat.stop();
            HttpBridge holder = Agent.bridgeRef();
            Agent.markStopped();
            if (holder != null) {
                holder.stop();
            }
            return HttpBridge.Reply.json(Json.obj().bool("ok", true).put("note", "已停止注入端").done());
        }

        return HttpBridge.Reply.error(404, "没有这个接口：" + p);
    }

    private HttpBridge.Reply staticFile(String resource, String diskPath, String type) throws Exception {
        byte[] data = null;
        if (diskPath != null && !diskPath.isEmpty()) {
            try {
                data = readAll(new FileInputStream(diskPath));
            } catch (Throwable ignored) {
                data = null;
            }
        }
        if (data == null) {
            InputStream in = Routes.class.getResourceAsStream("/" + resource);
            if (in != null) {
                data = readAll(in);
            }
        }
        if (data == null) {
            if (type.indexOf("javascript") >= 0) {
                // TurboWarp 会把响应体当 JS 执行，返回 JSON 只会得到 "Error in sandboxed script"。
                return HttpBridge.Reply.text("console.error('[ScratchMC] jar 里没有 " + resource + "');\n"
                        + "throw new Error('ScratchMC: 注入端 jar 里没有 " + resource
                        + " —— 重新执行 build.ps1，重启游戏后重新注入');\n", "application/javascript");
            }
            return HttpBridge.Reply.error(404, "jar 里没有 " + resource + "（打包时把扩展/工程一起塞进 jar）");
        }
        return HttpBridge.Reply.bytes(data, type);
    }

    static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
