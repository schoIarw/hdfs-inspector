package com.acme.hdfsperf.core;

import com.acme.hdfsperf.cli.Arguments;
import com.acme.hdfsperf.jmx.*;
import com.acme.hdfsperf.model.AnalysisReport;
import com.acme.hdfsperf.report.ReportWriter;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Locale;

public final class Inspector {
    private final Arguments args;
    public Inspector(Arguments args) { this.args = args; }

    public int run() throws Exception {
        AnalysisReport report = new AnalysisReport();
        report.command = args.command().name().toLowerCase(Locale.ROOT);
        report.source = args.get("source"); report.target = args.get("target");
        report.options.putAll(args.safeValues());
        report.environment.put("javaVersion", System.getProperty("java.version"));
        report.environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        try { report.environment.put("clientHost", InetAddress.getLocalHost().getHostName()); }
        catch (Exception e) { report.environment.put("clientHost", "unknown"); }

        JmxSnapshot before = new JmxSnapshot();
        JmxSnapshot after = new JmxSnapshot();
        try (JmxCollector collector = new JmxCollector(args)) {
            if (collector.configured()) before = collector.capture();
            try {
                if (args.command() == Arguments.Command.OBSERVE) {
                    int seconds = args.getInt("duration", 60);
                    if (seconds < 1 || seconds > 86400) throw new IllegalArgumentException("--duration 范围为 1..86400 秒");
                    long start = System.nanoTime();
                    Thread.sleep(seconds * 1000L);
                    report.phase("observation_window", System.nanoTime() - start, "只读 JMX 观测，不产生 HDFS 文件流量");
                } else {
                    new HdfsTransfer(args, report).execute();
                }
                report.success = true;
            } catch (Exception e) {
                report.success = false;
                report.error = rootMessage(e);
            } finally {
                if (collector.configured()) after = collector.capture();
            }
        }
        JmxDiff.apply(before, after, report);
        report.finishedAt = Instant.now();
        Diagnostics.evaluate(report, args.getInt("slow-ms", 100));
        ReportWriter.Output output = new ReportWriter().write(report, args.reportDirectory());
        System.out.println("分析完成: " + (report.success ? "SUCCESS" : "FAILED"));
        System.out.println("JSON: " + output.json);
        System.out.println("HTML: " + output.html);
        System.out.println("TEXT: " + output.text);
        return report.success ? 0 : 1;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
    }
}
