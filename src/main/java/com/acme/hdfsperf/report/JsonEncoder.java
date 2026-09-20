package com.acme.hdfsperf.report;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.*;

final class JsonEncoder {
    private JsonEncoder() {}
    static String encode(Object value) { StringBuilder b = new StringBuilder(); append(b, value, 0); return b.toString(); }

    @SuppressWarnings("unchecked")
    private static void append(StringBuilder b, Object value, int level) {
        if (value == null) { b.append("null"); return; }
        if (value instanceof String || value instanceof Character || value instanceof TemporalAccessor) {
            quote(b, String.valueOf(value)); return;
        }
        if (value instanceof Boolean) { b.append(value); return; }
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isFinite(d)) b.append(value); else b.append("null");
            return;
        }
        if (value instanceof Map) {
            b.append('{'); boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) b.append(','); newline(b, level + 1); quote(b, String.valueOf(e.getKey())); b.append(": ");
                append(b, e.getValue(), level + 1); first = false;
            }
            if (!first) newline(b, level); b.append('}'); return;
        }
        if (value instanceof Iterable) {
            b.append('['); boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) b.append(','); newline(b, level + 1); append(b, item, level + 1); first = false;
            }
            if (!first) newline(b, level); b.append(']'); return;
        }
        if (value.getClass().isArray()) {
            List<Object> list = new ArrayList<>(); for (int i = 0; i < Array.getLength(value); i++) list.add(Array.get(value, i));
            append(b, list, level); return;
        }
        throw new IllegalArgumentException("不支持的 JSON 类型: " + value.getClass());
    }
    private static void newline(StringBuilder b, int level) { b.append('\n'); for (int i = 0; i < level; i++) b.append("  "); }
    private static void quote(StringBuilder b, String value) {
        b.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break; case '\\': b.append("\\\\"); break;
                case '\b': b.append("\\b"); break; case '\f': b.append("\\f"); break;
                case '\n': b.append("\\n"); break; case '\r': b.append("\\r"); break; case '\t': b.append("\\t"); break;
                default: if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else b.append(c);
            }
        }
        b.append('"');
    }
}
