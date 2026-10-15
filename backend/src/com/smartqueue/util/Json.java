package com.smartqueue.util;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer.
 * Parsed objects are LinkedHashMap, arrays are ArrayList, numbers are Long or Double.
 */
public final class Json {

    private Json() {
    }

    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder(512);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof CharSequence) {
            quote(sb, v.toString());
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof BigDecimal) {
            sb.append(((BigDecimal) v).toPlainString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
            }
        } else if (v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof LocalDateTime) {
            quote(sb, Times.toIso((LocalDateTime) v));
        } else if (v instanceof LocalDate) {
            quote(sb, v.toString());
        } else if (v instanceof Instant) {
            quote(sb, v.toString());
        } else if (v instanceof Enum) {
            quote(sb, ((Enum<?>) v).name());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v.getClass().isArray()) {
            sb.append('[');
            int n = Array.getLength(v);
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, Array.get(v, i));
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("Empty JSON");
        }
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue(0);
        p.skipWs();
        if (!p.atEnd()) {
            throw new JsonException("Unexpected data after JSON value");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("JSON object expected");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Object readValue(int depth) {
            if (depth > 64) {
                throw new JsonException("JSON nested too deeply");
            }
            if (atEnd()) {
                throw new JsonException("Unexpected end of JSON");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return readObject(depth);
                case '[':
                    return readArray(depth);
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw new JsonException("Unexpected character '" + c + "' at position " + i);
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, i)) {
                throw new JsonException("Invalid JSON literal at position " + i);
            }
            i += word.length();
        }

        Map<String, Object> readObject(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (!atEnd() && s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                if (atEnd() || s.charAt(i) != '"') {
                    throw new JsonException("Object key expected at position " + i);
                }
                String key = readString();
                skipWs();
                if (atEnd() || s.charAt(i) != ':') {
                    throw new JsonException("':' expected at position " + i);
                }
                i++;
                skipWs();
                map.put(key, readValue(depth + 1));
                skipWs();
                if (atEnd()) {
                    throw new JsonException("Unterminated object");
                }
                char c = s.charAt(i++);
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    return map;
                }
                throw new JsonException("',' or '}' expected at position " + (i - 1));
            }
        }

        List<Object> readArray(int depth) {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipWs();
            if (!atEnd() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(readValue(depth + 1));
                skipWs();
                if (atEnd()) {
                    throw new JsonException("Unterminated array");
                }
                char c = s.charAt(i++);
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    return list;
                }
                throw new JsonException("',' or ']' expected at position " + (i - 1));
            }
        }

        String readString() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                if (atEnd()) {
                    throw new JsonException("Unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new JsonException("Bad escape");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw new JsonException("Bad unicode escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw new JsonException("Bad unicode escape");
                            }
                            i += 4;
                            break;
                        default:
                            throw new JsonException("Bad escape \\" + e);
                    }
                } else if (c < 0x20) {
                    throw new JsonException("Control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        Object readNumber() {
            int start = i;
            boolean isDouble = false;
            if (s.charAt(i) == '-') {
                i++;
            }
            while (!atEnd()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isDouble = true;
                    i++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, i);
            try {
                if (!isDouble) {
                    return Long.parseLong(num);
                }
                return Double.parseDouble(num);
            } catch (NumberFormatException ex) {
                try {
                    return Double.parseDouble(num);
                } catch (NumberFormatException ex2) {
                    throw new JsonException("Invalid number '" + num + "'");
                }
            }
        }
    }
}
