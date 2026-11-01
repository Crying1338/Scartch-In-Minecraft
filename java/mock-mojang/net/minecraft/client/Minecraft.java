package net.minecraft.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自测用假 MC（Mojang 映射风格）。
 * 类名必须精确等于 net.minecraft.client.Minecraft，其余类同包即可。
 */
public class Minecraft {
    public static final Minecraft INSTANCE = new Minecraft();

    public LocalPlayer player;
    public ClientLevel level = new ClientLevel();
    public MultiPlayerGameMode gameMode = new MultiPlayerGameMode();

    public static Minecraft getInstance() {
        return INSTANCE;
    }

    public void execute(Runnable task) {
        task.run();
    }

    /** SelfTest 调这个把世界填起来。 */
    public static void bootstrap() {
        Minecraft m = getInstance();
        m.level = new ClientLevel();
        m.gameMode = new MultiPlayerGameMode();

        LocalPlayer self = new LocalPlayer();
        self.id = 100;
        self.name = "Steve";
        self.hp = 20f;
        self.x = 0.5;
        self.y = 64;
        self.z = 0.5;
        self.yRot = 45f;
        self.xRot = 12.5f;
        m.player = self;

        for (int i = 0; i < 3; i++) {
            Zombie z = new Zombie();
            z.id = 200 + i;
            z.name = "Zombie" + i;
            z.hp = 20f - i * 3;
            z.x = 1.5 + i * 0.4;
            z.y = 64;
            z.z = 2.0 + i * 0.7;
            m.level.add(z);
        }

        LocalPlayer other = new LocalPlayer();
        other.id = 300;
        other.name = "Alex";
        other.hp = 20f;
        other.x = -6;
        other.y = 64;
        other.z = 3;
        m.level.add(other);
    }
}

class Entity {
    // 三个 double：Mapping 的结构探测靠这个认 Entity
    double x;
    double y;
    double z;
    float yRot;
    float xRot;
    int id;
    String name = "entity";

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getYRot() {
        return yRot;
    }

    public void setYRot(float v) {
        yRot = v;
    }

    public float getXRot() {
        return xRot;
    }

    public void setXRot(float v) {
        xRot = v;
    }

    public double getEyeHeight() {
        return 1.62;
    }

    public String getName() {
        return name;
    }

    public Object getType() {
        return "minecraft:" + name.toLowerCase();
    }

    public boolean isAlive() {
        return true;
    }

    public int getId() {
        return id;
    }
}

class LivingEntity extends Entity {
    float hp = 20f;

    public float getHealth() {
        return hp;
    }
}

class Zombie extends LivingEntity {
}

class LocalPlayer extends Entity {
    float hp = 20f;

    public float getHealth() {
        return hp;
    }
}

class ClientLevel {
    private final List<Entity> entities = new ArrayList<Entity>();

    void add(Entity e) {
        entities.add(e);
    }

    public Iterable<Entity> entitiesForRendering() {
        return Collections.unmodifiableList(entities);
    }
}

class MultiPlayerGameMode {
    String lastTarget = "";

    public void attack(LocalPlayer player, Entity target) {
        lastTarget = target.getName();
    }
}
