package com.acme.hdfsperf.jmx;

import java.util.*;

final class MiniJson {
    private final String text;
    private int pos;
    private MiniJson(String text) { this.text = text; }
    static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        Object value = p.value();
        p.space();
        if (p.pos != text.length()) throw p.error("JSON 尾部存在多余内容");
        return value;
    }
    private Object value() {
        space();
        if (pos >= text.length()) throw error("JSON 意外结束");
        char c = text.charAt(pos);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (c == 't') { literal("true"); return Boolean.TRUE; }
        if (c == 'f') { literal("false"); return Boolean.FALSE; }
        if (c == 'n') { literal("null"); return null; }
        return number();
    }
    private Map<String, Object> object() {
        expect('{'); Map<String, Object> map = new LinkedHashMap<>(); space();
        if (take('}')) return map;
        while (true) {
            space(); String key = string(); space(); expect(':'); map.put(key, value()); space();
            if (take('}')) return map; expect(',');
        }
    }
    private List<Object> array() {
        expect('['); List<Object> list = new ArrayList<>(); space();
        if (take(']')) return list;
        while (true) { list.add(value()); space(); if (take(']')) return list; expect(','); }
    }
    private String string() {
        expect('"'); StringBuilder out = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') return out.toString();
            if (c == '\\') {
                if (pos >= text.length()) throw error("非法转义");
                char e = text.charAt(pos++);
                switch (e) {
                    case '"': case '\\': case '/': out.append(e); break;
                    case 'b': out.append('\b'); break; case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break; case 'r': out.append('\r'); break; case 't': out.append('\t'); break;
                    case 'u':
                        if (pos + 4 > text.length()) throw error("非法 Unicode 转义");
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16)); pos += 4; break;
                    default: throw error("未知转义字符");
                }
            } else out.append(c);
        }
        throw error("字符串未结束");
    }
    private Number number() {
        int start = pos;
        if (take('-')) {}
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        if (take('.')) while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            pos++; if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        }
        String n = text.substring(start, pos);
        try {
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.valueOf(n);
            return Long.valueOf(n);
        }
        catch (NumberFormatException e) { throw error("非法数字: " + n); }
    }
    private void literal(String expected) {
        if (!text.startsWith(expected, pos)) throw error("期望 " + expected); pos += expected.length();
    }
    private void space() { while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++; }
    private boolean take(char c) { if (pos < text.length() && text.charAt(pos) == c) { pos++; return true; } return false; }
    private void expect(char c) { if (!take(c)) throw error("期望 '" + c + "'"); }
    private IllegalArgumentException error(String msg) { return new IllegalArgumentException(msg + "，位置 " + pos); }
}
