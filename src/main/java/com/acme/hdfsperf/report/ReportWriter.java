package com.acme.hdfsperf.report;

import com.acme.hdfsperf.model.AnalysisReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class ReportWriter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    public Output write(AnalysisReport r, Path directory) throws IOException {
        Files.createDirectories(directory);
        String base = "hdfs-perf-" + r.command + "-" + FILE_TIME.format(r.startedAt);
        Path json = directory.resolve(base + ".json");
        Path html = directory.resolve(base + ".html");
        Path text = directory.resolve(base + ".txt");
        Files.write(json, JsonEncoder.encode(toMap(r)).getBytes(StandardCharsets.UTF_8));
        Files.write(html, html(r).getBytes(StandardCharsets.UTF_8));
        Files.write(text, text(r).getBytes(StandardCharsets.UTF_8));
        return new Output(json.toAbsolutePath(), html.toAbsolutePath(), text.toAbsolutePath());
    }

    private Map<String, Object> toMap(AnalysisReport r) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", "1.0"); root.put("toolVersion", r.toolVersion); root.put("reportId", r.reportId);
        root.put("startedAt", r.startedAt); root.put("finishedAt", r.finishedAt); root.put("command", r.command);
        root.put("source", r.source); root.put("target", r.target); root.put("success", r.success); root.put("error", r.error);
        root.put("bytes", r.bytes); root.put("throughputMiBps", r.throughputMiBps);
        root.put("environment", r.environment); root.put("options", r.options);
        List<Object> phases = new ArrayList<>();
        for (AnalysisReport.Phase p : r.clientPhases) phases.add(map("name", p.name, "durationMs", p.durationMs, "attribution", p.attribution));
        root.put("clientPhases", phases);
        if (r.ioLatency != null) {
            AnalysisReport.IoStatistics s = r.ioLatency;
            List<Object> samples = new ArrayList<>();
            for (AnalysisReport.SlowCall c : s.slowest) samples.add(map("sequence", c.sequence, "bytes", c.bytes, "durationMs", c.durationMs, "offset", c.offset));
            root.put("ioLatency", map("operation", s.operation, "calls", s.calls, "bytes", s.bytes, "meanMs", s.meanMs,
                "p50Ms", s.p50Ms, "p95Ms", s.p95Ms, "p99Ms", s.p99Ms, "maxMs", s.maxMs,
                "slowCalls", s.slowCalls, "zeroByteCalls", s.zeroByteCalls, "shortCalls", s.shortCalls, "slowest", samples));
        } else root.put("ioLatency", null);
        root.put("jmxAttribution", "观测窗口节点级累计/瞬时指标，可能包含其他并发请求，不等同于单请求 RPC trace");
        root.put("jmxDelta", r.jmxDelta); root.put("jmxGaugeBefore", r.jmxGaugeBefore); root.put("jmxGaugeAfter", r.jmxGaugeAfter);
        root.put("jmxErrors", r.jmxErrors);
        List<Object> findings = new ArrayList<>();
        for (AnalysisReport.Finding f : r.findings) findings.add(map("severity", f.severity, "title", f.title, "evidence", f.evidence, "recommendation", f.recommendation));
        root.put("findings", findings);
        return root;
    }

    private String html(AnalysisReport r) {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            .append("<title>HDFS 性能分析报告</title><style>")
            .append("body{font:14px/1.55 system-ui,sans-serif;color:#222;margin:0;background:#f5f7fa}main{max-width:1180px;margin:24px auto;padding:0 20px}")
            .append("h1{color:#b91c1c}h2{margin-top:28px}section{background:#fff;padding:18px 22px;margin:14px 0;border:1px solid #e5e7eb;border-radius:8px}")
            .append("table{width:100%;border-collapse:collapse}th,td{padding:8px;border-bottom:1px solid #eee;text-align:left;vertical-align:top}th{background:#fafafa}")
            .append(".OK{color:#15803d}.INFO{color:#0369a1}.WARN{color:#b45309}.ERROR{color:#b91c1c}.metric{font-size:24px;font-weight:700}.note{color:#666}")
            .append("</style></head><body><main><h1>HDFS 性能分析报告</h1>");
        b.append("<section><table>").append(row("状态", r.success ? "SUCCESS" : "FAILED"))
            .append(row("操作", esc(r.command))).append(row("源", esc(r.source))).append(row("目标", esc(r.target)))
            .append(row("数据量", humanBytes(r.bytes))).append(row("端到端吞吐", fmt(r.throughputMiBps) + " MiB/s"))
            .append(row("时间", esc(String.valueOf(r.startedAt)) + " — " + esc(String.valueOf(r.finishedAt)))).append("</table></section>");
        b.append("<h2>诊断结论</h2><section><table><tr><th>级别</th><th>结论</th><th>证据</th><th>建议</th></tr>");
        for (AnalysisReport.Finding f : r.findings) b.append("<tr><td class=\"").append(esc(f.severity)).append("\">").append(esc(f.severity))
            .append("</td><td>").append(esc(f.title)).append("</td><td>").append(esc(f.evidence)).append("</td><td>").append(esc(f.recommendation)).append("</td></tr>");
        b.append("</table></section><h2>客户端阶段</h2><section><table><tr><th>阶段</th><th>耗时(ms)</th><th>归因范围</th></tr>");
        for (AnalysisReport.Phase p : r.clientPhases) b.append("<tr><td>").append(esc(p.name)).append("</td><td>").append(fmt(p.durationMs))
            .append("</td><td>").append(esc(p.attribution)).append("</td></tr>");
        b.append("</table></section>");
        if (r.ioLatency != null) {
            AnalysisReport.IoStatistics s = r.ioLatency;
            b.append("<h2>I/O 调用延迟</h2><section><table>")
                .append(row("操作/调用数", esc(s.operation) + " / " + s.calls)).append(row("平均 / P50 / P95 / P99 / Max (ms)",
                    fmt(s.meanMs)+" / "+fmt(s.p50Ms)+" / "+fmt(s.p95Ms)+" / "+fmt(s.p99Ms)+" / "+fmt(s.maxMs)))
                .append(row("慢调用 / 短调用 / 零字节调用", s.slowCalls+" / "+s.shortCalls+" / "+s.zeroByteCalls)).append("</table>");
            if (!s.slowest.isEmpty()) {
                b.append("<h3>最慢调用样本</h3><table><tr><th>序号</th><th>Offset</th><th>字节</th><th>耗时(ms)</th></tr>");
                for (AnalysisReport.SlowCall c : s.slowest) b.append("<tr><td>").append(c.sequence).append("</td><td>").append(c.offset)
                    .append("</td><td>").append(c.bytes).append("</td><td>").append(fmt(c.durationMs)).append("</td></tr>");
                b.append("</table>");
            }
            b.append("</section>");
        }
        b.append("<h2>JMX 观测窗口差值</h2><p class=\"note\">节点级指标可能包含并发请求；完整 before/after 与所有数值见 JSON 报告。</p>");
        for (Map.Entry<String, Map<String, Number>> endpoint : r.jmxDelta.entrySet()) {
            b.append("<section><h3>").append(esc(endpoint.getKey())).append("</h3><table><tr><th>指标</th><th>差值</th></tr>");
            if (endpoint.getValue().isEmpty()) b.append("<tr><td colspan=\"2\">无可计算的非零 counter 差值</td></tr>");
            for (Map.Entry<String, Number> m : endpoint.getValue().entrySet()) b.append("<tr><td>").append(esc(m.getKey())).append("</td><td>").append(esc(String.valueOf(m.getValue()))).append("</td></tr>");
            b.append("</table></section>");
        }
        if (!r.jmxErrors.isEmpty()) { b.append("<h2>JMX 采集错误</h2><section><ul>"); for (String e : r.jmxErrors) b.append("<li>").append(esc(e)).append("</li>"); b.append("</ul></section>"); }
        return b.append("</main></body></html>").toString();
    }

    private String text(AnalysisReport r) {
        StringBuilder b = new StringBuilder("HDFS 性能分析报告\n==================\n");
        b.append("状态: ").append(r.success ? "SUCCESS" : "FAILED").append('\n').append("操作: ").append(r.command).append('\n')
            .append("源: ").append(r.source).append('\n').append("目标: ").append(r.target).append('\n')
            .append("数据量: ").append(humanBytes(r.bytes)).append('\n').append("吞吐: ").append(fmt(r.throughputMiBps)).append(" MiB/s\n\n客户端阶段:\n");
        for (AnalysisReport.Phase p : r.clientPhases) b.append("- ").append(p.name).append(": ").append(fmt(p.durationMs)).append(" ms [").append(p.attribution).append("]\n");
        if (r.ioLatency != null) b.append("\nI/O延迟: mean=").append(fmt(r.ioLatency.meanMs)).append(" ms, p95=").append(fmt(r.ioLatency.p95Ms))
            .append(" ms, p99=").append(fmt(r.ioLatency.p99Ms)).append(" ms, max=").append(fmt(r.ioLatency.maxMs)).append(" ms\n");
        b.append("\n诊断结论:\n");
        for (AnalysisReport.Finding f : r.findings) b.append("- [").append(f.severity).append("] ").append(f.title).append("\n  证据: ").append(f.evidence).append("\n  建议: ").append(f.recommendation).append('\n');
        b.append("\n说明: 完整 JMX 原始指标、差值及慢调用样本请查看 JSON 报告。\n");
        return b.toString();
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]); return m;
    }
    private static String row(String key, Object value) { return "<tr><th>"+esc(key)+"</th><td>"+String.valueOf(value)+"</td></tr>"; }
    private static String fmt(double n) { return String.format(Locale.ROOT, "%.2f", n); }
    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B"; double v = bytes; String[] u = {"KiB","MiB","GiB","TiB"}; int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1); return fmt(v) + " " + u[i];
    }
    private static String esc(Object value) {
        if (value == null) return ""; return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static final class Output {
        public final Path json, html, text;
        Output(Path json, Path html, Path text) { this.json = json; this.html = html; this.text = text; }
    }
}
