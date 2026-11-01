package net.minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自测用假 MC（Yarn / Intermediary 映射风格）：类名 class_310，字段 field_1724，
 * 实体上既没有 getX() 也没有 setYRot()，逼 Mapping 走「结构探测 + 值探测」。
 */
public class class_310 {
    public static class_310 field_1700 = new class_310();

    public class_746 field_1724;
    public class_638 field_1687 = new class_638();
    public class_636 field_1761 = new class_636();

    public static class_310 method_1551() {
        return field_1700;
    }

    public void execute(Runnable task) {
        task.run();
    }

    public static void bootstrap() {
        class_310 m = method_1551();
        m.field_1687 = new class_638();
        m.field_1761 = new class_636();

        class_746 self = new class_746();
        self.field_2001 = 100;
        self.field_2002 = "Steve";
        self.field_2100 = 20f;
        self.field_1001 = 0.5;
        self.field_1002 = 64;
        self.field_1003 = 0.5;
        self.field_1004 = 45f;
        self.field_1005 = 12.5f;
        m.field_1724 = self;

        for (int i = 0; i < 3; i++) {
            class_1309 z = new class_1309();
            z.field_2001 = 200 + i;
            z.field_2002 = "Zombie" + i;
            z.field_2100 = 20f - i * 3;
            z.field_1001 = 1.5 + i * 0.4;
            z.field_1002 = 64;
            z.field_1003 = 2.0 + i * 0.7;
            m.field_1687.add(z);
        }

        class_746 other = new class_746();
        other.field_2001 = 300;
        other.field_2002 = "Alex";
        other.field_2100 = 20f;
        other.field_1001 = -6;
        other.field_1002 = 64;
        other.field_1003 = 3;
        m.field_1687.add(other);
    }
}

class class_1297 {
    double field_1001;
    double field_1002;
    double field_1003;
    float field_1004;
    float field_1005;
    int field_2001;
    String field_2002 = "entity";

    public String method_5477() {
        return field_2002;
    }

    public Object method_5864() {
        return "minecraft:" + field_2002.toLowerCase();
    }

    public boolean method_5805() {
        return true;
    }

    public int method_5628() {
        return field_2001;
    }

    public double method_18381() {
        return 1.62;
    }
}

class class_1309 extends class_1297 {
    float field_2100 = 20f;

    public float method_6032() {
        return field_2100;
    }
}

class class_746 extends class_1297 {
    float field_2100 = 20f;

    public float method_6032() {
        return field_2100;
    }
}

class class_638 {
    private final List<class_1297> field_1500 = new ArrayList<class_1297>();

    void add(class_1297 e) {
        field_1500.add(e);
    }

    public Iterable<class_1297> method_18122() {
        return Collections.unmodifiableList(field_1500);
    }
}

class class_636 {
    public String field_1900 = "";

    public void method_2905(class_746 player, class_1297 target) {
        field_1900 = target.method_5477();
    }
}
