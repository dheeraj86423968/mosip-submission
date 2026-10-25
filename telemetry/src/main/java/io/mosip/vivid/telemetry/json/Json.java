package io.mosip.vivid.telemetry.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer and parser sufficient for telemetry records (flat objects of strings, numbers, booleans,
 * nulls, nested objects and arrays). Kept dependency-free so the telemetry module can ship on Android and in the
 * desktop client without pulling a JSON library into the audit path.
 *
 * <p>Values written: {@link String}, {@link Number}, {@link Boolean}, {@code null}, {@link Map} (string keys),
 * {@link Collection}, {@link Enum} (as name). Values parsed: strings, numbers (as {@link Long} when integral,
 * otherwise {@link Double}), booleans, {@code null}, objects ({@link LinkedHashMap}) and arrays ({@link List}).</p>
 */
public final class Json {

    private Json() {}

    // ---- writer ----------------------------------------------------------------------------------------------

    /** Serialises a value to compact JSON. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(128);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Number) {
            writeNumber(sb, (Number) v);
        } else if (v instanceof Enum) {
            writeString(sb, ((Enum<?>) v).name());
        } else if (v instanceof Map) {
            writeObject(sb, (Map<?, ?>) v);
        } else if (v instanceof Collection) {
            writeArray(sb, (Collection<?>) v);
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                sb.append(d);
            }
        } else {
            sb.append(n.longValue());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            write(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Collection<?> items) {
        sb.append('[');
        boolean first = true;
        for (Object o : items) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            write(sb, o);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---- parser ----------------------------------------------------------------------------------------------

    /** Parses a JSON object. Throws {@link IllegalArgumentException} on malformed input or a non-object root. */
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("JSON root is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    /** Parses any JSON value. Throws {@link IllegalArgumentException} on malformed input. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s == null ? "" : s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("malformed JSON at " + pos + ": " + msg);
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        Object value() {
            char c = peek();
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        void literal(String lit) {
            if (!s.startsWith(lit, pos)) {
                throw error("expected " + lit);
            }
            pos += lit.length();
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                m.put(key, value());
                skipWs();
                char c = peek();
                pos++;
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                char c = peek();
                pos++;
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = peek();
                    pos++;
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw error("bad unicode escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("bad unicode escape");
                            }
                            pos += 4;
                            break;
                        default:
                            throw error("bad escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw error("control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        Number number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean integral = true;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    pos++;
                } else {
                    break;
                }
            }
            String t = s.substring(start, pos);
            try {
                return integral ? (Number) Long.valueOf(t) : (Number) Double.valueOf(t);
            } catch (NumberFormatException e) {
                throw error("bad number '" + t + "'");
            }
        }
    }
}
