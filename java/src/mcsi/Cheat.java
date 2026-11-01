package mcsi;

import java.util.List;

/**
 * 作弊内核。跑在游戏的 JVM 里：一个 25ms 的脉冲线程读玩家 / 实体状态，
 * 按模块开关写视角、发起攻击，结果留在 volatile 字段里等 HTTP 端来取。
 *
 * 速度 / 步长 / 灵敏度步长这些参数直接对应 Scratch 面板上的滑杆，改值不用重编译。
 */
public final class Cheat {

    public final Mc mc;
    public volatile boolean running;

    // ---- 模块开关 ----
    public volatile boolean aimOn;
    public volatile boolean triggerOn;
    public volatile boolean espOn = true;

    // ---- 参数（Scratch 面板可改）----
    public volatile double range = 4.0;          // 瞄准范围（格）
    public volatile double speed = 300.0;        // 角速度上限（度 / 秒）
    public volatile double step = 8.0;           // 单帧最大转动（度）
    public volatile double fov = 30.0;           // 自瞄 FOV（度）
    public volatile double sensStep = 0.15;      // 视角量化步进（≈ MC 灵敏度一格）
    public volatile double yawOffset;
    public volatile double pitchOffset;
    public volatile double triggerBox = 2.0;     // 准星容差（度）
    public volatile long triggerCooldown = 130;  // 触发冷却（毫秒）
    public volatile boolean smooth = true;
    public volatile String targetFilter = "mob"; // mob / player / all
    public volatile String sort = "angle";       // angle / dist / hp
    public volatile String triggerMode = "left"; // left / right / off

    // ---- 对外快照 ----
    public volatile String targetName = "";
    public volatile long targetId = -1;
    public volatile double targetDist;
    public volatile double targetDyaw;
    public volatile double targetDpitch;
    public volatile float targetHp = -1;
    public volatile long ticks;
    public volatile double hz;
    public volatile String lastNote = "";
    public volatile long lastAimAt;
    public volatile long lastAttackAt;
    public volatile double lastStepDeg;

    private Thread pulse;
    private long lastTickAt;
    private volatile boolean stopRequested;

    public Cheat(Mc mc) {
        this.mc = mc;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        stopRequested = false;
        lastTickAt = System.currentTimeMillis();
        pulse = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "mcsi-pulse");
        pulse.setDaemon(true);
        pulse.setPriority(Thread.MAX_PRIORITY - 1);
        pulse.start();
        Log.info("脉冲线程已启动（25ms / tick）");
    }

    public void stop() {
        stopRequested = true;
        running = false;
    }

    private void loop() {
        while (!stopRequested) {
            long t0 = System.currentTimeMillis();
            try {
                tick(t0);
            } catch (Throwable t) {
                lastNote = "tick 异常：" + t;
                Log.error("tick 异常", t);
            }
            long spent = System.currentTimeMillis() - t0;
            long wait = 25 - spent;
            try {
                Thread.sleep(wait > 1 ? wait : 1);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void tick(long now) {
        double dt = Math.max(0.005, Math.min(0.2, (now - lastTickAt) / 1000.0));
        lastTickAt = now;
        ticks++;
        if (dt > 0) {
            hz = 0.9 * hz + 0.1 * (1.0 / dt);
        }

        if (!mc.ready) {
            lastNote = "注入端未就绪：" + mc.fail;
            return;
        }
        if (!mc.inGame()) {
            clearTarget();
            lastNote = "不在游戏里（主菜单 / 加载中）";
            return;
        }

        double scan = range > 0 ? range : 6.0;
        List<Mc.Ent> list = mc.entities(scan, targetFilter, sort, 96);

        Mc.Ent best = null;
        double bestScore = Double.MAX_VALUE;
        for (Mc.Ent e : list) {
            double score = "dist".equalsIgnoreCase(sort) ? e.dist : Math.hypot(e.dyaw, e.dpitch);
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }

        if (best == null) {
            clearTarget();
            lastNote = "范围内没有符合条件的实体";
        } else {
            targetName = best.name;
            targetId = best.id;
            targetDist = best.dist;
            targetDyaw = best.dyaw;
            targetDpitch = best.dpitch;
            targetHp = best.hp;
            lastNote = "";
        }

        if (aimOn && best != null) {
            if (Math.abs(best.dyaw) <= fov && Math.abs(best.dpitch) <= fov) {
                applyAim(best, dt, now);
            } else {
                lastNote = "目标在 FOV 外（" + Json.num(Math.hypot(best.dyaw, best.dpitch))
                        + "° > " + Json.num(fov) + "°）";
            }
        }

        if (triggerOn && best != null && !"off".equalsIgnoreCase(triggerMode)) {
            boolean onCrosshair = Math.abs(best.dyaw) <= triggerBox && Math.abs(best.dpitch) <= triggerBox;
            if (onCrosshair && now - lastAttackAt >= triggerCooldown) {
                boolean ok = mc.attack(best);
                lastAttackAt = now;
                if (!ok) {
                    lastNote = "attack 调用不通：这个版本要走输入层触发";
                }
            }
        }
    }

    /** 一帧的转向：速度上限 + 单帧步长 + 量化，三者同时约束。 */
    private void applyAim(Mc.Ent target, double dt, long now) {
        float[] cur = mc.playerRot();
        double wantYaw = wrap(cur[0] + target.dyaw + yawOffset);
        double wantPitch = clampPitch(cur[1] + target.dpitch + pitchOffset);

        double dYaw = wrap(wantYaw - cur[0]);
        double dPitch = wantPitch - cur[1];

        double maxDeg = Math.min(step, speed * dt);
        if (maxDeg <= 0.01) {
            maxDeg = 0.01;
        }
        double applyYaw = clamp(dYaw, -maxDeg, maxDeg);
        double applyPitch = clamp(dPitch, -maxDeg, maxDeg);

        if (smooth && Math.hypot(dYaw, dPitch) < 1.2) {
            applyYaw *= 0.55;
            applyPitch *= 0.55;
        }

        double nextYaw = cur[0] + applyYaw;
        double nextPitch = cur[1] + applyPitch;
        if (sensStep > 0.0001) {
            nextYaw = quantize(nextYaw, sensStep);
            nextPitch = quantize(nextPitch, sensStep);
        }

        boolean ok = mc.setRot((float) nextYaw, (float) clampPitch(nextPitch));
        lastStepDeg = Math.hypot(applyYaw, applyPitch);
        if (ok) {
            lastAimAt = now;
            lastNote = "";
        }
    }

    private void clearTarget() {
        targetName = "";
        targetId = -1;
        targetDist = 0;
        targetDyaw = 0;
        targetDpitch = 0;
        targetHp = -1;
    }

    // -------------------------------------------------------------- 参数入口

    /** 改参数：名字大小写不敏感，也接受面板上的中文名。 */
    public String setParam(String key, String value) {
        String k = key == null ? "" : key.trim().toLowerCase();
        String v = value == null ? "" : value.trim();
        if (k.isEmpty()) {
            return "参数名为空";
        }
        if ("range".equals(k) || "瞄准范围".equals(k)) {
            range = clamp(Json.dbl(v, range), 0.5, 64);
            return "瞄准范围 = " + Json.num(range);
        }
        if ("speed".equals(k) || "速度".equals(k)) {
            speed = clamp(Json.dbl(v, speed), 10, 3600);
            return "速度 = " + Json.num(speed) + "°/s";
        }
        if ("step".equals(k) || "步长".equals(k)) {
            step = clamp(Json.dbl(v, step), 0.05, 90);
            return "步长 = " + Json.num(step) + "°";
        }
        if ("fov".equals(k) || "视角".equals(k)) {
            fov = clamp(Json.dbl(v, fov), 1, 180);
            return "FOV = " + Json.num(fov) + "°";
        }
        if ("sensstep".equals(k) || "灵敏度步长".equals(k) || "mc灵敏度步长".equals(k)) {
            sensStep = clamp(Json.dbl(v, sensStep), 0, 5);
            return "灵敏度步长 = " + Json.num(sensStep) + "°";
        }
        if ("yawoffset".equals(k) || "偏航偏移".equals(k)) {
            yawOffset = Json.dbl(v, yawOffset);
            return "偏航偏移 = " + Json.num(yawOffset);
        }
        if ("pitchoffset".equals(k) || "俯仰偏移".equals(k)) {
            pitchOffset = Json.dbl(v, pitchOffset);
            return "俯仰偏移 = " + Json.num(pitchOffset);
        }
        if ("triggerbox".equals(k)) {
            triggerBox = clamp(Json.dbl(v, triggerBox), 0.1, 30);
            return "准星容差 = " + Json.num(triggerBox) + "°";
        }
        if ("triggercooldown".equals(k)) {
            triggerCooldown = (long) clamp(Json.dbl(v, triggerCooldown), 30, 2000);
            return "触发冷却 = " + triggerCooldown + "ms";
        }
        if ("smooth".equals(k) || "平滑".equals(k)) {
            smooth = Json.truthy(v);
            return "平滑 = " + smooth;
        }
        if ("target".equals(k) || "目标".equals(k) || "targetfilter".equals(k)) {
            String f = v.toLowerCase();
            if (f.startsWith("play") || v.contains("玩家")) {
                targetFilter = "player";
            } else if (f.startsWith("all") || v.contains("全部")) {
                targetFilter = "all";
            } else {
                targetFilter = "mob";
            }
            return "目标 = " + targetFilter;
        }
        if ("sort".equals(k) || "排序".equals(k)) {
            sort = "dist".equalsIgnoreCase(v) ? "dist" : ("hp".equalsIgnoreCase(v) ? "hp" : "angle");
            return "排序 = " + sort;
        }
        if ("triggermode".equals(k) || "触发方式".equals(k)) {
            if (v.startsWith("r") || v.contains("右")) {
                triggerMode = "right";
            } else if ("off".equalsIgnoreCase(v) || v.contains("关")) {
                triggerMode = "off";
            } else {
                triggerMode = "left";
            }
            return "触发方式 = " + triggerMode;
        }
        return "未知参数：" + key;
    }

    public String setModule(String name, boolean on) {
        String n = name == null ? "" : name.trim().toLowerCase();
        if ("aim".equals(n) || "自瞄".equals(n)) {
            aimOn = on;
            return "自瞄 " + (on ? "开" : "关");
        }
        if ("trigger".equals(n) || "触发".equals(n)) {
            triggerOn = on;
            return "触发 " + (on ? "开" : "关");
        }
        if ("esp".equals(n) || "透视".equals(n)) {
            espOn = on;
            return "ESP " + (on ? "开" : "关");
        }
        return "未知模块：" + name;
    }

    /** 当前锁定的目标快照（HTTP 端要用，不能直接持有游戏对象太久）。 */
    public Mc.Ent snapshotTarget() {
        if (targetId < 0 || targetName.isEmpty()) {
            return null;
        }
        Mc.Ent e = new Mc.Ent();
        e.id = targetId;
        e.name = targetName;
        e.dist = targetDist;
        e.dyaw = (float) targetDyaw;
        e.dpitch = (float) targetDpitch;
        e.hp = targetHp;
        return e;
    }

    public String modulesJson() {
        return Json.obj()
                .bool("aim", aimOn)
                .bool("trigger", triggerOn)
                .bool("esp", espOn)
                .done();
    }

    public String paramsJson() {
        return Json.obj()
                .num("range", range)
                .num("speed", speed)
                .num("step", step)
                .num("fov", fov)
                .num("sensStep", sensStep)
                .num("yawOffset", yawOffset)
                .num("pitchOffset", pitchOffset)
                .num("triggerBox", triggerBox)
                .num("triggerCooldown", triggerCooldown)
                .bool("smooth", smooth)
                .put("target", targetFilter)
                .put("sort", sort)
                .put("triggerMode", triggerMode)
                .done();
    }

    public String targetJson() {
        return Json.obj()
                .put("name", targetName)
                .num("id", targetId)
                .num("dist", targetDist)
                .num("dyaw", targetDyaw)
                .num("dpitch", targetDpitch)
                .num("hp", targetHp)
                .bool("locked", targetId >= 0)
                .done();
    }

    public String pulseJson() {
        return Json.obj()
                .num("ticks", ticks)
                .num("hz", hz)
                .num("lastStep", lastStepDeg)
                .num("lastAimAt", lastAimAt)
                .num("lastAttackAt", lastAttackAt)
                .put("note", lastNote)
                .bool("running", running)
                .done();
    }

    // ------------------------------------------------------------------ 数学

    public static double wrap(double deg) {
        double d = deg % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    public static double clampPitch(double deg) {
        return Math.max(-90.0, Math.min(90.0, deg));
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double quantize(double v, double q) {
        return Math.rint(v / q) * q;
    }
}
