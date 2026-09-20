package com.acme.hdfsperf.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public final class Arguments {
    public enum Command { UPLOAD, DOWNLOAD, OBSERVE }
    private final Command command;
    private final Map<String, String> values;
    private final boolean help;

    private Arguments(Command command, Map<String, String> values, boolean help) {
        this.command = command;
        this.values = Collections.unmodifiableMap(values);
        this.help = help;
    }

    public static Arguments parse(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            return new Arguments(Command.OBSERVE, Collections.emptyMap(), true);
        }
        Command cmd;
        try { cmd = Command.valueOf(args[0].toUpperCase(Locale.ROOT)); }
        catch (Exception e) { throw new IllegalArgumentException("首个参数必须是 upload、download 或 observe"); }

        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--")) throw new IllegalArgumentException("未知参数: " + token);
            String key = token.substring(2);
            if (key.trim().isEmpty()) throw new IllegalArgumentException("空参数名");
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("参数 --" + key + " 缺少值");
            }
            map.put(key, args[++i]);
        }
        if (cmd != Command.OBSERVE) {
            require(map, "source");
            require(map, "target");
        }
        if (cmd == Command.OBSERVE && !map.containsKey("nn-jmx") && !map.containsKey("dn-jmx")) {
            throw new IllegalArgumentException("observe 至少需要 --nn-jmx 或 --dn-jmx");
        }
        return new Arguments(cmd, map, false);
    }

    private static void require(Map<String, String> map, String key) {
        if (!map.containsKey(key) || map.get(key).trim().isEmpty()) throw new IllegalArgumentException("缺少 --" + key);
    }

    public Command command() { return command; }
    public boolean help() { return help; }
    public String get(String key) { return values.get(key); }
    public String get(String key, String fallback) { return values.getOrDefault(key, fallback); }
    public int getInt(String key, int fallback) {
        String v = values.get(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("--" + key + " 必须是整数"); }
    }
    public boolean getBoolean(String key, boolean fallback) {
        String v = values.get(key);
        if (v == null) return fallback;
        if ("true".equalsIgnoreCase(v)) return true;
        if ("false".equalsIgnoreCase(v)) return false;
        throw new IllegalArgumentException("--" + key + " 必须是 true 或 false");
    }
    public Path reportDirectory() { return Paths.get(get("report", "./reports")).toAbsolutePath().normalize(); }
    public List<String> csv(String key) {
        String v = values.get(key);
        if (v == null || v.trim().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String item : v.split(",")) if (!item.trim().isEmpty()) out.add(item.trim());
        return out;
    }
    public Map<String, String> safeValues() {
        Map<String, String> copy = new LinkedHashMap<>(values);
        copy.remove("jmx-password");
        return copy;
    }

    public static String usage() {
        return String.join(System.lineSeparator(),
            "HDFS Performance Inspector（非压测）",
            "用法:",
            "  upload   --source <本地文件> --target <HDFS URI> [选项]",
            "  download --source <HDFS URI> --target <本地文件> [选项]",
            "  observe  --duration <秒> --nn-jmx <URL,...> 或 --dn-jmx <URL,...> [选项]",
            "选项:",
            "  --conf <Hadoop配置目录>  --report <报告目录>",
            "  --nn-jmx <URL,...>       --dn-jmx <URL,...>",
            "  --buffer-size <字节>     --slow-ms <毫秒>",
            "  --overwrite true|false   --delete-target true|false",
            "  --jmx-user <用户名>（密码从 HDFS_PERF_JMX_PASSWORD 环境变量读取）");
    }
}
