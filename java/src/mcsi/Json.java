package mcsi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写。agent 跑在游戏的 JVM 里，只能用 java.base，
 * 所以这里不引 Gson/Jackson：写用 Obj/arr，读只解析扁平对象（够协议用了）。
 */
public final class Json {
    private Json() {
    }

    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    /** JSON 里不许出现 NaN / Infinity。 */
    public static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    public static String str(String s) {
        return "\"" + esc(s) + "\"";
    }

    public static final class Obj {
        private final StringBuilder sb = new StringBuilder("{");
        private boolean first = true;

        private void key(String k) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(esc(k)).append("\":");
        }

        public Obj put(String k, String v) {
            key(k);
            sb.append(v == null ? "null" : str(v));
            return this;
        }

        public Obj num(String k, double v) {
            key(k);
            sb.append(Json.num(v));
            return this;
        }

        public Obj num(String k, long v) {
            key(k);
            sb.append(v);
            return this;
        }

        public Obj bool(String k, boolean v) {
            key(k);
            sb.append(v);
            return this;
        }

        /** 直接塞一段已经序列化好的 JSON。 */
        public Obj raw(String k, String raw) {
            key(k);
            sb.append(raw == null ? "null" : raw);
            return this;
        }

        public String done() {
            return sb.append('}').toString();
        }

        @Override
        public String toString() {
            return done();
        }
    }

    public static Obj obj() {
        return new Obj();
    }

    public static String arr(List<String> items) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(items.get(i));
        }
        return b.append(']').toString();
    }

    public static List<String> list() {
        return new ArrayList<String>();
    }

    /**
     * 解析扁平对象：{"a":1,"b":"x","c":true,"d":{"k":1},"e":[1,2]}。
     * 值一律转成字符串，嵌套结构整体保留原文。解析失败返回空 map，不抛异常。
     */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (text == null) {
            return out;
        }
        Scan s = new Scan(text);
        s.ws();
        if (!s.eat('{')) {
            return out;
        }
        s.ws();
        if (s.eat('}')) {
            return out;
        }
        while (s.more()) {
            s.ws();
            String key = s.string();
            if (key == null) {
                return out;
            }
            s.ws();
            if (!s.eat(':')) {
                return out;
            }
            s.ws();
            String value = s.value();
            out.put(key, value == null ? "" : value);
            s.ws();
            if (s.eat(',')) {
                continue;
            }
            s.eat('}');
            break;
        }
        return out;
    }

    public static boolean truthy(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s) || "开".equals(s);
    }

    public static double dbl(String v, double fallback) {
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static long lng(String v, long fallback) {
        return (long) dbl(v, fallback);
    }

    private static final class Scan {
        private final String s;
        private int i;

        Scan(String s) {
            this.s = s;
        }

        boolean more() {
            return i < s.length();
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        boolean eat(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        String string() {
            if (!eat('"')) {
                return null;
            }
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n':
                            b.append('\n');
                            break;
                        case 'r':
                            b.append('\r');
                            break;
                        case 't':
                            b.append('\t');
                            break;
                        case 'u':
                            if (i + 4 <= s.length()) {
                                b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            break;
                        default:
                            b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            return null;
        }

        String value() {
            if (i < s.length() && s.charAt(i) == '"') {
                return string();
            }
            int start = i;
            int depth = 0;
            boolean inStr = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (inStr) {
                    if (c == '\\') {
                        i += 2;
                        continue;
                    }
                    if (c == '"') {
                        inStr = false;
                    }
                    i++;
                    continue;
                }
                if (c == '"') {
                    inStr = true;
                    i++;
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (c == ',' && depth == 0) {
                    break;
                }
                i++;
            }
            return s.substring(start, i).trim();
        }
    }
}
