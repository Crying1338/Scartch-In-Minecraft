package mcsi;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Minecraft 状态门面：玩家、实体快照、视角写入、攻击。 */
public final class Mc {
    public final Mapping map = new Mapping();
    public volatile boolean ready;
    public volatile String fail = "";
    public volatile long resolvedAt;
    private volatile double[] lastSelf = new double[]{0, 0, 0};

    public boolean init(Instrumentation inst) {
        ready = map.resolve(inst);
        fail = map.fail;
        resolvedAt = System.currentTimeMillis();
        return ready;
    }

    public boolean inGame() {
        return ready && map.player() != null;
    }

    public String playerName() {
        Object p = map.player();
        return p == null ? "" : map.name(p);
    }

    public double[] playerPos() {
        Object p = map.player();
        if (p == null) {
            return lastSelf;
        }
        double[] pos = map.pos(p);
        lastSelf = pos;
        return pos;
    }

    public float[] playerRot() {
        return map.rot(map.player());
    }

    public Object player() {
        return map.player();
    }

    public boolean setRot(float yaw, float pitch) {
        Object p = map.player();
        if (p == null) {
            return false;
        }
        boolean ok = map.setRot(p, (float) Cheat.wrap(yaw), (float) Cheat.clampPitch(pitch));
        if (!ok) {
            fail = "写视角失败：这个映射下没有可写的 setYRot/setXRot 或 yRot/xRot 字段";
        }
        return ok;
    }

    /** 一次目标快照，Scratch 端按字段取值。 */
    public static final class Ent {
        public long id;
        public String name = "";
        public String kind = "";
        public double x;
        public double y;
        public double z;
        public double dist;
        public float hp = -1;
        public float dyaw;
        public float dpitch;
        public boolean living;
        public boolean isPlayer;

        public String json() {
            return Json.obj()
                    .num("id", id)
                    .put("name", name)
                    .put("kind", kind)
                    .num("x", x)
                    .num("y", y)
                    .num("z", z)
                    .num("dist", dist)
                    .num("hp", hp)
                    .num("dyaw", dyaw)
                    .num("dpitch", dpitch)
                    .bool("living", living)
                    .bool("player", isPlayer)
                    .done();
        }
    }

    /**
     * 实体快照。
     *
     * @param range  最大距离（格）
     * @param filter mob / player / all
     * @param sort   angle / dist / hp
     */
    public List<Ent> entities(double range, String filter, String sort, int limit) {
        Object p = map.player();
        if (p == null) {
            return Collections.emptyList();
        }
        double[] pp = map.pos(p);
        float[] pr = map.rot(p);
        double selfEye = map.eyeOffset(p);
        List<Ent> out = new ArrayList<Ent>();
        String f = filter == null ? "mob" : filter.toLowerCase();
        for (Object e : map.entities()) {
            if (e == null || e == p) {
                continue;
            }
            boolean isPlayer = map.playerish(e);
            boolean living = map.living(e);
            if ("player".equals(f) && !isPlayer) {
                continue;
            }
            if ("mob".equals(f) && (isPlayer || !living)) {
                continue;
            }
            if (!map.alive(e)) {
                continue;
            }
            double[] ep = map.pos(e);
            double dx = ep[0] - pp[0];
            double dy = ep[1] - pp[1];
            double dz = ep[2] - pp[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (range > 0 && dist > range) {
                continue;
            }
            if (dist < 1.0E-6) {
                continue;
            }
            Ent s = new Ent();
            s.id = map.id(e);
            s.name = map.name(e);
            s.kind = map.typeId(e);
            s.x = ep[0];
            s.y = ep[1];
            s.z = ep[2];
            s.dist = dist;
            s.hp = map.health(e);
            s.living = living;
            s.isPlayer = isPlayer;
            double eye = map.eyeOffset(e);
            double aimY = ep[1] + (eye > 0 ? eye : 1.62);
            s.dyaw = (float) Cheat.wrap(Math.toDegrees(Math.atan2(-dx, dz)) - pr[0]);
            s.dpitch = (float) Cheat.clampPitch(-Math.toDegrees(Math.atan2(aimY - (pp[1] + selfEye), Math.hypot(dx, dz))) - pr[1]);
            out.add(s);
        }
        if ("angle".equalsIgnoreCase(sort)) {
            Collections.sort(out, new Comparator<Ent>() {
                @Override
                public int compare(Ent a, Ent b) {
                    return Double.compare(Math.hypot(a.dyaw, a.dpitch), Math.hypot(b.dyaw, b.dpitch));
                }
            });
        } else if ("hp".equalsIgnoreCase(sort)) {
            Collections.sort(out, new Comparator<Ent>() {
                @Override
                public int compare(Ent a, Ent b) {
                    return Float.compare(a.hp, b.hp);
                }
            });
        } else {
            Collections.sort(out, new Comparator<Ent>() {
                @Override
                public int compare(Ent a, Ent b) {
                    return Double.compare(a.dist, b.dist);
                }
            });
        }
        if (limit > 0 && out.size() > limit) {
            return new ArrayList<Ent>(out.subList(0, limit));
        }
        return out;
    }

    public Ent nearestInCrosshair(double range, String filter, double boxDeg) {
        for (Ent e : entities(range, filter, "angle", 128)) {
            if (Math.abs(e.dyaw) <= boxDeg && Math.abs(e.dpitch) <= boxDeg) {
                return e;
            }
        }
        return null;
    }

    public boolean attack(Ent target) {
        if (target == null) {
            return false;
        }
        for (Object e : map.entities()) {
            if (e != null && map.id(e) == target.id) {
                return map.attack(e);
            }
        }
        return false;
    }

    public String statusJson() {
        double[] pos = playerPos();
        float[] rot = playerRot();
        return Json.obj()
                .bool("ready", ready)
                .bool("inGame", inGame())
                .put("mcClass", map.mcClass == null ? "" : map.mcClass.getName())
                .put("playerClass", map.playerClass == null ? "" : map.playerClass.getName())
                .put("entityClass", map.entityClass == null ? "" : map.entityClass.getName())
                .put("levelClass", map.levelClass == null ? "" : map.levelClass.getName())
                .put("fail", fail == null ? "" : fail)
                .bool("canAim", map.canWriteRot())
                .bool("canAttack", map.canAttack())
                .num("resolvedAt", resolvedAt)
                .put("player", playerName())
                .num("entities", map.entities().size())
                .raw("pos", "[" + Json.num(pos[0]) + "," + Json.num(pos[1]) + "," + Json.num(pos[2]) + "]")
                .raw("rot", "[" + Json.num(rot[0]) + "," + Json.num(rot[1]) + "]")
                .done();
    }
}
