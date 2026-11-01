package mcsi;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft 反射适配层。
 *
 * 同一个 JVM 里的 MC 可能是三种名字体系：
 *   Mojang 官方映射     net.minecraft.client.Minecraft#player, #level
 *   Yarn / Intermediary net.minecraft.class_310#field_1724, field_1687
 *   Forge / NeoForge SRG net.minecraft.client.Minecraft#f_91074_, f_91073_
 * 所以这里全部按「候选名优先 + 结构探测兜底」两路解析，解析结果可通过 /mappings 导出。
 */
public final class Mapping {

    // ---------------------------------------------------------------- 候选名表
    private static final String[] N_MC = {"net.minecraft.client.Minecraft", "net.minecraft.class_310"};
    private static final String[] N_INSTANCE_FIELD = {"instance", "field_1700", "f_91002_"};
    private static final String[] N_GET_INSTANCE = {"getInstance", "method_1551", "m_91087_"};
    private static final String[] N_PLAYER = {"player", "field_1724", "f_91074_", "thePlayer"};
    private static final String[] N_LEVEL = {"level", "world", "field_1687", "f_91073_"};
    private static final String[] N_GAME_MODE = {"gameMode", "field_1761", "f_91072_", "playerController"};
    private static final String[] N_ENTITIES = {"entitiesForRendering", "method_18122", "m_104735_",
            "getEntities", "getAllEntities", "entities"};
    private static final String[] N_GET_X = {"getX", "method_23317", "m_20185_"};
    private static final String[] N_GET_Y = {"getY", "method_23318", "m_20186_"};
    private static final String[] N_GET_Z = {"getZ", "method_23319", "m_20187_"};
    private static final String[] N_GET_YAW = {"getYRot", "method_36454", "m_146908_", "getYaw"};
    private static final String[] N_GET_PITCH = {"getXRot", "method_36455", "m_146909_", "getPitch"};
    private static final String[] N_SET_YAW = {"setYRot", "method_36456", "m_146922_", "setYaw"};
    private static final String[] N_SET_PITCH = {"setXRot", "method_36457", "m_146923_", "setPitch"};
    private static final String[] N_F_YAW = {"yRot", "yaw", "field_5982", "f_19857_"};
    private static final String[] N_F_PITCH = {"xRot", "pitch", "field_5967", "f_19858_"};
    private static final String[] N_F_X = {"x", "field_6008"};
    private static final String[] N_F_Y = {"y", "field_6009"};
    private static final String[] N_F_Z = {"z", "field_6010"};
    private static final String[] N_ATTACK = {"attack", "method_2905", "m_105269_"};
    private static final String[] N_GET_NAME = {"getName", "method_5477", "m_7755_", "getDisplayName"};
    private static final String[] N_GET_TYPE = {"getType", "method_5864", "m_6096_"};
    private static final String[] N_GET_HEALTH = {"getHealth", "method_6032", "m_21223_"};
    private static final String[] N_IS_ALIVE = {"isAlive", "method_5805", "m_6084_"};
    private static final String[] N_GET_ID = {"getId", "method_5628", "m_19879_"};
    private static final String[] N_GET_EYE = {"getEyeHeight", "method_18381", "m_20193_"};
    private static final String[] N_EXECUTE = {"execute", "method_1550", "m_6937_"};

    public Instrumentation inst;
    public boolean ready;
    public String fail = "";

    public Class<?> mcClass;
    public Class<?> playerClass;
    public Class<?> levelClass;
    public Class<?> entityClass;

    private Field fInstance;
    private Method mGetInstance;
    private Field fPlayer;
    private Field fLevel;
    private Field fGameMode;
    private Method mEntities;
    private Method mAttack;
    private Method mExecute;

    private Method mGetX;
    private Method mGetY;
    private Method mGetZ;
    private Method mGetYaw;
    private Method mGetPitch;
    private Method mSetYaw;
    private Method mSetPitch;
    private Method mGetEye;
    private Field fX;
    private Field fY;
    private Field fZ;
    private Field fYaw;
    private Field fPitch;
    private boolean rotProbed;

    private final Map<Class<?>, Method> nameCache = new IdentityHashMap<Class<?>, Method>();
    private final Map<Class<?>, Method> typeCache = new IdentityHashMap<Class<?>, Method>();
    private final Map<Class<?>, Method> healthCache = new IdentityHashMap<Class<?>, Method>();
    private final Map<Class<?>, Method> aliveCache = new IdentityHashMap<Class<?>, Method>();
    private final Map<Class<?>, Method> idCache = new IdentityHashMap<Class<?>, Method>();

    private final StringBuilder trace = new StringBuilder();

    // ------------------------------------------------------------------ 解析

    public boolean resolve(Instrumentation instrumentation) {
        this.inst = instrumentation;
        trace.setLength(0);
        try {
            mcClass = classByNames(N_MC);
            if (mcClass != null) {
                note("Minecraft 类 = " + mcClass.getName() + "（名字候选）");
            } else {
                mcClass = scanSingleton();
                if (mcClass != null) {
                    note("Minecraft 类 = " + mcClass.getName() + "（结构探测：static 自身类型字段）");
                }
            }
            if (mcClass == null) {
                return die("没找到 Minecraft 主类：进程里既没有 net.minecraft.client.Minecraft，也没有 class_310");
            }

            fInstance = fieldByName(mcClass, true, N_INSTANCE_FIELD, mcClass);
            mGetInstance = methodByName(mcClass, mcClass, false, N_GET_INSTANCE);
            if (fInstance == null && mGetInstance == null) {
                fInstance = sniffInstanceField();
            }
            note(fInstance != null ? "单例字段 = " + fInstance.getName()
                    : (mGetInstance != null ? "单例方法 = " + mGetInstance.getName() + "()" : "单例来源未定"));

            fPlayer = fieldByName(mcClass, false, N_PLAYER, null);
            if (fPlayer != null) {
                note("player 字段 = " + fPlayer.getName() + "（名字候选）");
            } else {
                fPlayer = sniffPlayerField();
                if (fPlayer != null) {
                    note("player 字段 = " + fPlayer.getName() + "（结构探测）");
                }
            }
            if (fPlayer == null) {
                return die("没找到 Minecraft 的本地玩家字段（player / field_1724 / f_91074_）");
            }
            playerClass = fPlayer.getType();
            note("LocalPlayer 类 = " + playerClass.getName());

            fLevel = fieldByName(mcClass, false, N_LEVEL, null);
            if (fLevel != null) {
                note("level 字段 = " + fLevel.getName() + "（名字候选）");
            } else {
                fLevel = sniffLevelField();
                if (fLevel != null) {
                    note("level 字段 = " + fLevel.getName() + "（结构探测）");
                }
            }
            levelClass = fLevel == null ? null : fLevel.getType();
            if (levelClass != null) {
                note("Level 类 = " + levelClass.getName());
            }

            entityClass = findEntityClass(playerClass);
            note("Entity 类 = " + entityClass.getName());

            fGameMode = fieldByName(mcClass, false, N_GAME_MODE, null);
            if (fGameMode != null) {
                note("gameMode 字段 = " + fGameMode.getName());
                mAttack = methodByName(fGameMode.getType(), void.class, false, N_ATTACK);
                if (mAttack == null) {
                    mAttack = attackCandidate(fGameMode.getType());
                }
                note(mAttack == null ? "attack 未找到：触发模块走不了 native attack"
                        : "attack = " + mAttack.getName() + "(" + mAttack.getParameterCount() + " 参)");
            }

            if (levelClass != null) {
                mEntities = methodByName(levelClass, Iterable.class, false, N_ENTITIES);
                if (mEntities == null) {
                    mEntities = sniffEntityEnumerator(levelClass);
                }
                note(mEntities == null ? "实体枚举未找到：ESP / 自瞄拿不到目标"
                        : "实体枚举 = " + mEntities.getName() + "()");
            }

            resolveEntityAccessors();
            mExecute = findExecute();
            note(mExecute == null ? "主线程投递未找到：直接写字段（跨线程，一般也能生效）"
                    : "主线程投递 = " + mExecute.getName() + "(Runnable)");
            ready = true;
            return true;
        } catch (Throwable t) {
            Log.error("映射解析异常", t);
            return die("映射解析异常：" + t);
        }
    }

    private void resolveEntityAccessors() {
        Class<?> k = entityClass == null ? playerClass : entityClass;
        mGetX = methodByName(k, double.class, false, N_GET_X);
        mGetY = methodByName(k, double.class, false, N_GET_Y);
        mGetZ = methodByName(k, double.class, false, N_GET_Z);
        note("坐标方法 = " + (mGetX == null ? "无（用字段）" : mGetX.getName() + "/" + mGetY.getName() + "/" + mGetZ.getName()));

        mSetYaw = methodByName(k, void.class, false, N_SET_YAW);
        mSetPitch = methodByName(k, void.class, false, N_SET_PITCH);
        mGetYaw = methodByName(k, float.class, false, N_GET_YAW);
        mGetPitch = methodByName(k, float.class, false, N_GET_PITCH);
        mGetEye = methodByName(k, double.class, false, N_GET_EYE);
        if (mGetEye == null) {
            mGetEye = methodByName(k, float.class, false, N_GET_EYE);
        }
        note("朝向方法 = " + (mSetYaw == null ? "无（用字段）" : mSetYaw.getName() + "/" + mSetPitch.getName()));

        if (mGetX == null) {
            fX = anyField(k, double.class, N_F_X);
            fY = anyField(k, double.class, N_F_Y);
            fZ = anyField(k, double.class, N_F_Z);
            if (fX == null || fY == null || fZ == null) {
                Field[] triples = consecutiveDoubles(k);
                if (triples != null) {
                    fX = triples[0];
                    fY = triples[1];
                    fZ = triples[2];
                    note("坐标字段（结构探测）= " + fX.getName() + "," + fY.getName() + "," + fZ.getName());
                }
            } else {
                note("坐标字段 = " + fX.getName() + "," + fY.getName() + "," + fZ.getName());
            }
        }

        if (mSetYaw == null) {
            fYaw = anyField(k, float.class, N_F_YAW);
            fPitch = anyField(k, float.class, N_F_PITCH);
            if (fYaw != null && fPitch != null) {
                note("朝向字段 = " + fYaw.getName() + "," + fPitch.getName());
            }
        }
    }

    /** 朝向字段名都对不上时（重度混淆），拿玩家实例做一次值探测。 */
    private void ensureRotFields() {
        if (rotProbed) {
            return;
        }
        rotProbed = true;
        if (mSetYaw != null && mSetPitch != null) {
            return;
        }
        Object p = player();
        if (p == null) {
            return;
        }
        List<Field> floats = new ArrayList<Field>();
        for (Field f : hierarchyFields(entityClass == null ? p.getClass() : entityClass)) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == float.class) {
                try {
                    f.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                floats.add(f);
            }
        }
        int n = floats.size();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i == j || (pass == 0 ? i > j : i < j)) {
                        continue;
                    }
                    Field a = floats.get(i);
                    Field b = floats.get(j);
                    float va = readFloat(a, p);
                    float vb = readFloat(b, p);
                    if (va >= -180.01f && va <= 180.01f && vb >= -90.01f && vb <= 90.01f) {
                        if (fYaw == null) {
                            fYaw = a;
                        }
                        if (fPitch == null) {
                            fPitch = b;
                        }
                        note("朝向字段（值探测）= " + a.getName() + "," + b.getName());
                        return;
                    }
                }
            }
        }
        note("朝向字段探测失败：自瞄只能读不能写");
    }

    // ------------------------------------------------------------ 数据读取

    public Object mc() {
        try {
            if (fInstance != null) {
                return fInstance.get(null);
            }
            if (mGetInstance != null) {
                return mGetInstance.invoke(null);
            }
        } catch (Throwable ignored) {
            // 主菜单 / 关闭中
        }
        return null;
    }

    public Object player() {
        Object m = mc();
        if (m == null || fPlayer == null) {
            return null;
        }
        try {
            return fPlayer.get(m);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public Object level() {
        Object m = mc();
        if (m == null || fLevel == null) {
            return null;
        }
        try {
            return fLevel.get(m);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public double[] pos(Object entity) {
        if (entity == null) {
            return new double[]{0, 0, 0};
        }
        if (mGetX != null) {
            return new double[]{asDouble(call(mGetX, entity), 0), asDouble(call(mGetY, entity), 0),
                    asDouble(call(mGetZ, entity), 0)};
        }
        if (fX != null) {
            try {
                return new double[]{fX.getDouble(entity), fY.getDouble(entity), fZ.getDouble(entity)};
            } catch (Throwable ignored) {
                // 落到默认值
            }
        }
        return new double[]{0, 0, 0};
    }

    public float[] rot(Object entity) {
        if (entity == null) {
            return new float[]{0, 0};
        }
        ensureRotFields();
        float yaw = mGetYaw != null ? (float) asDouble(call(mGetYaw, entity), 0)
                : (fYaw != null ? readFloat(fYaw, entity) : 0);
        float pitch = mGetPitch != null ? (float) asDouble(call(mGetPitch, entity), 0)
                : (fPitch != null ? readFloat(fPitch, entity) : 0);
        return new float[]{yaw, pitch};
    }

    /** 写视角。返回 false 表示这个映射下拿不到可写的朝向入口。 */
    public boolean setRot(Object entity, float yaw, float pitch) {
        if (entity == null) {
            return false;
        }
        boolean ok = false;
        if (mSetYaw != null) {
            ok |= invokeOk(mSetYaw, entity, Float.valueOf(yaw));
        } else if (fYaw != null) {
            ok |= writeFloat(fYaw, entity, yaw);
        }
        if (mSetPitch != null) {
            ok |= invokeOk(mSetPitch, entity, Float.valueOf(pitch));
        } else if (fPitch != null) {
            ok |= writeFloat(fPitch, entity, pitch);
        }
        return ok;
    }

    public boolean canWriteRot() {
        ensureRotFields();
        return (mSetYaw != null || fYaw != null) && (mSetPitch != null || fPitch != null);
    }

    public boolean canAttack() {
        return mAttack != null && fGameMode != null;
    }

    /** 把 Runnable 丢到游戏主线程（找不到 execute 就返回 false）。 */
    public boolean submit(Runnable task) {
        if (mExecute == null || task == null) {
            return false;
        }
        Object m = mc();
        if (m == null) {
            return false;
        }
        // execute(Runnable) 返回 void，invoke 成功也是 null，不能用返回值判断
        return invokeOk(mExecute, m, task);
    }

    public double eyeOffset(Object entity) {
        if (entity == null) {
            return 1.62;
        }
        if (mGetEye != null) {
            double v = asDouble(call(mGetEye, entity), 1.62);
            return v > 0 ? v : 1.62;
        }
        return 1.62;
    }

    public List<Object> entities() {
        Object lv = level();
        if (lv == null || mEntities == null) {
            return Collections.emptyList();
        }
        Object raw = call(mEntities, lv);
        List<Object> out = new ArrayList<Object>();
        if (raw instanceof Iterable) {
            try {
                for (Object o : (Iterable<?>) raw) {
                    if (o != null) {
                        out.add(o);
                    }
                }
            } catch (Throwable t) {
                Log.info("实体枚举被并发修改，跳过本帧");
            }
        } else if (raw instanceof Map) {
            for (Object o : ((Map<?, ?>) raw).values()) {
                if (o != null) {
                    out.add(o);
                }
            }
        } else if (raw instanceof Collection) {
            out.addAll((Collection<?>) raw);
        }
        return out;
    }

    public String name(Object entity) {
        if (entity == null) {
            return "";
        }
        Class<?> k = entity.getClass();
        Method m = nameCache.get(k);
        if (m == null && !nameCache.containsKey(k)) {
            m = methodByName(k, null, false, N_GET_NAME);
            nameCache.put(k, m);
        }
        if (m == null) {
            return k.getSimpleName();
        }
        Object v = call(m, entity);
        if (v == null) {
            return k.getSimpleName();
        }
        Method gs = methodByName(v.getClass(), String.class, false, "getString", "method_5414", "m_7532_");
        if (gs != null) {
            Object s = call(gs, v);
            if (s != null) {
                return String.valueOf(s);
            }
        }
        return String.valueOf(v);
    }

    public String typeId(Object entity) {
        if (entity == null) {
            return "";
        }
        Class<?> k = entity.getClass();
        Method m = typeCache.get(k);
        if (m == null && !typeCache.containsKey(k)) {
            m = methodByName(k, null, false, N_GET_TYPE);
            typeCache.put(k, m);
        }
        Object v = m == null ? null : call(m, entity);
        return v == null ? k.getSimpleName() : String.valueOf(v);
    }

    public float health(Object entity) {
        if (entity == null) {
            return -1;
        }
        Class<?> k = entity.getClass();
        Method m = healthCache.get(k);
        if (m == null && !healthCache.containsKey(k)) {
            m = methodByName(k, float.class, false, N_GET_HEALTH);
            healthCache.put(k, m);
        }
        if (m == null) {
            return -1;
        }
        return (float) asDouble(call(m, entity), -1);
    }

    public boolean living(Object entity) {
        if (entity == null) {
            return false;
        }
        Class<?> k = entity.getClass();
        if (healthCache.containsKey(k)) {
            return healthCache.get(k) != null;
        }
        Method m = methodByName(k, float.class, false, N_GET_HEALTH);
        healthCache.put(k, m);
        return m != null;
    }

    public boolean playerish(Object entity) {
        return entity != null && playerClass != null && playerClass.isAssignableFrom(entity.getClass());
    }

    public boolean alive(Object entity) {
        if (entity == null) {
            return false;
        }
        Class<?> k = entity.getClass();
        Method m = aliveCache.get(k);
        if (m == null && !aliveCache.containsKey(k)) {
            m = methodByName(k, boolean.class, false, N_IS_ALIVE);
            aliveCache.put(k, m);
        }
        if (m == null) {
            return true;
        }
        Object v = call(m, entity);
        return !(v instanceof Boolean) || (Boolean) v;
    }

    public long id(Object entity) {
        if (entity == null) {
            return -1;
        }
        Class<?> k = entity.getClass();
        Method m = idCache.get(k);
        if (m == null && !idCache.containsKey(k)) {
            m = methodByName(k, null, false, N_GET_ID);
            idCache.put(k, m);
        }
        if (m == null) {
            return System.identityHashCode(entity);
        }
        return (long) asDouble(call(m, entity), System.identityHashCode(entity));
    }

    public boolean attack(Object target) {
        Object p = player();
        Object m = mc();
        if (target == null || p == null || m == null || mAttack == null || fGameMode == null) {
            return false;
        }
        try {
            Object gm = fGameMode.get(m);
            if (gm == null) {
                return false;
            }
            int n = mAttack.getParameterCount();
            if (n == 2) {
                mAttack.invoke(gm, p, target);
            } else if (n == 1) {
                mAttack.invoke(gm, target);
            } else {
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.error("attack 调用失败", t);
            return false;
        }
    }

    public String report() {
        return trace.toString();
    }

    // ------------------------------------------------------------------ 探测

    private Class<?> classByNames(String[] names) {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        for (String n : names) {
            Class<?> c = tryLoad(n, ctx);
            if (c != null) {
                return c;
            }
        }
        if (inst != null) {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                for (String n : names) {
                    if (c.getName().equals(n)) {
                        return c;
                    }
                }
            }
        }
        return null;
    }

    private static Class<?> tryLoad(String name, ClassLoader... loaders) {
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignored) {
                // 下一个
            }
        }
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 单例探测：类里存在 static 字段，其类型就是类自己。 */
    private Class<?> scanSingleton() {
        if (inst == null) {
            return null;
        }
        Class<?> best = null;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.isInterface() || c.isArray() || c.isPrimitive() || c.getClassLoader() == null) {
                continue;
            }
            boolean self = false;
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) && f.getType() == c) {
                        self = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                continue;
            }
            if (!self) {
                continue;
            }
            if (c.getName().toLowerCase().contains("minecraft")) {
                return c;
            }
            if (best == null) {
                best = c;
            }
        }
        return best;
    }

    private Field sniffInstanceField() {
        for (Field f : hierarchyFields(mcClass)) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == mcClass) {
                try {
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Field sniffPlayerField() {
        for (Field f : hierarchyFields(mcClass)) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) || f.getType().isPrimitive() || f.getType() == mcClass) {
                continue;
            }
            if (hasTripleDoubles(f.getType())) {
                try {
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {
                    continue;
                }
            }
        }
        return null;
    }

    private Field sniffLevelField() {
        for (Field f : hierarchyFields(mcClass)) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) || f.getType().isPrimitive() || f.getType() == mcClass) {
                continue;
            }
            if (f == fPlayer) {
                continue;
            }
            for (Method m : safeMethods(f.getType())) {
                if (m.getParameterCount() == 0 && Iterable.class.isAssignableFrom(m.getReturnType())) {
                    try {
                        f.setAccessible(true);
                        return f;
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
        }
        return null;
    }

    private Method sniffEntityEnumerator(Class<?> levelCls) {
        for (Method m : safeMethods(levelCls)) {
            if (m.getParameterCount() != 0 || !Iterable.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            Object lv = level();
            if (lv == null) {
                return m;
            }
            Object r = call(m, lv);
            if (!(r instanceof Iterable)) {
                continue;
            }
            for (Object o : (Iterable<?>) r) {
                if (o != null && entityClass != null && entityClass.isAssignableFrom(o.getClass())) {
                    return m;
                }
            }
        }
        return null;
    }

    private Method attackCandidate(Class<?> holder) {
        for (Method m : safeMethods(holder)) {
            if (m.getParameterCount() == 2 && Void.TYPE.equals(m.getReturnType())) {
                Class<?>[] p = m.getParameterTypes();
                if (!p[0].isPrimitive() && !p[1].isPrimitive()) {
                    try {
                        m.setAccessible(true);
                        return m;
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** Minecraft 继承自事件循环基类的 execute(Runnable)：把任务排到游戏主线程。 */
    private Method findExecute() {
        for (Class<?> k = mcClass; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : safeMethods(k)) {
                if (!nameIn(m.getName(), N_EXECUTE) || m.getParameterCount() != 1) {
                    continue;
                }
                if (m.getParameterTypes()[0] != Runnable.class) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m;
                } catch (Throwable ignored) {
                    continue;
                }
            }
        }
        return null;
    }

    /** 从 LocalPlayer 往上找第一个带三个 double 的祖先，那就是 Entity。 */
    private Class<?> findEntityClass(Class<?> from) {
        Class<?> best = null;
        for (Class<?> k = from; k != null && k != Object.class; k = k.getSuperclass()) {
            if (hasTripleDoubles(k)) {
                best = k;
            }
        }
        return best == null ? from : best;
    }

    // ------------------------------------------------------------- 反射工具

    private static boolean hasTripleDoubles(Class<?> c) {
        return consecutiveDoubles(c) != null;
    }

    private static Field[] consecutiveDoubles(Class<?> c) {
        List<Field> ds = new ArrayList<Field>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : safeFields(k)) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != double.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                ds.add(f);
            }
            if (ds.size() >= 3) {
                break;
            }
        }
        if (ds.size() < 3) {
            return null;
        }
        return new Field[]{ds.get(0), ds.get(1), ds.get(2)};
    }

    private Field fieldByName(Class<?> c, boolean staticOnly, String[] names, Class<?> type) {
        for (Field f : hierarchyFields(c)) {
            if (staticOnly && !Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (type != null && f.getType() != type) {
                continue;
            }
            if (!nameIn(f.getName(), names)) {
                continue;
            }
            try {
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                continue;
            }
        }
        return null;
    }

    private static Field anyField(Class<?> c, Class<?> type, String[] names) {
        for (Field f : hierarchyFields(c)) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != type || !nameIn(f.getName(), names)) {
                continue;
            }
            try {
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                continue;
            }
        }
        return null;
    }

    private static Method methodByName(Class<?> c, Class<?> ret, boolean staticOnly, String... names) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : safeMethods(k)) {
                if (!nameIn(m.getName(), names)) {
                    continue;
                }
                if (staticOnly && !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (!retOk(ret, m.getReturnType())) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m;
                } catch (Throwable ignored) {
                    continue;
                }
            }
        }
        return null;
    }

    private static boolean retOk(Class<?> want, Class<?> actual) {
        if (want == null) {
            return true;
        }
        if (want.isPrimitive()) {
            return want == actual;
        }
        return want.isAssignableFrom(actual);
    }

    private static boolean nameIn(String name, String[] names) {
        for (String n : names) {
            if (n.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Field[] safeFields(Class<?> c) {
        try {
            return c.getDeclaredFields();
        } catch (Throwable t) {
            return new Field[0];
        }
    }

    private static Method[] safeMethods(Class<?> c) {
        try {
            return c.getDeclaredMethods();
        } catch (Throwable t) {
            return new Method[0];
        }
    }

    private static List<Field> hierarchyFields(Class<?> c) {
        List<Field> out = new ArrayList<Field>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : safeFields(k)) {
                out.add(f);
            }
        }
        return out;
    }

    private static Object call(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** void 方法 invoke 成功时返回 null，所以单独判断「有没有抛异常」。 */
    private static boolean invokeOk(Method m, Object target, Object... args) {
        try {
            m.invoke(target, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static double asDouble(Object v, double fallback) {
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }

    private static float readFloat(Field f, Object target) {
        try {
            return f.getFloat(target);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static boolean writeFloat(Field f, Object target, float v) {
        try {
            f.setFloat(target, v);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void note(String line) {
        trace.append(line).append('\n');
        Log.info(line);
    }

    private boolean die(String why) {
        fail = why;
        ready = false;
        note("失败：" + why);
        return false;
    }
}
