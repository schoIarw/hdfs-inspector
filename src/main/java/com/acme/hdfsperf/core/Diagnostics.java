package com.acme.hdfsperf.core;

import com.acme.hdfsperf.model.AnalysisReport;
import java.util.*;

final class Diagnostics {
    private Diagnostics() {}

    static void evaluate(AnalysisReport r, int slowThresholdMs) {
        if (!r.success) {
            r.findings.add(new AnalysisReport.Finding("ERROR", "文件操作失败", safe(r.error),
                "优先检查报告中的失败阶段、HDFS 路径权限、NameNode 可用性、DataNode pipeline 和客户端异常栈。"));
        }
        if (!r.jmxErrors.isEmpty()) {
            r.findings.add(new AnalysisReport.Finding("WARN", "部分 JMX 指标不可用",
                String.join("；", r.jmxErrors), "检查节点 Web/JMX 端口、网络 ACL、认证配置；传输阶段计时仍然有效。"));
        }
        if (r.ioLatency != null && r.ioLatency.calls > 0) {
            if (r.ioLatency.p99Ms >= slowThresholdMs) {
                r.findings.add(new AnalysisReport.Finding("WARN", "客户端 I/O 长尾明显",
                    String.format(Locale.ROOT, "P99=%.2f ms，max=%.2f ms，慢调用=%d", r.ioLatency.p99Ms, r.ioLatency.maxMs, r.ioLatency.slowCalls),
                    "结合慢调用 offset 定位 block 边界，并核对对应 DataNode 的磁盘延迟、网络丢包、GC pause 与 pipeline 重建。"));
            }
            if (r.ioLatency.shortCalls > r.ioLatency.calls / 4 && "read".equals(r.ioLatency.operation)) {
                r.findings.add(new AnalysisReport.Finding("INFO", "短读比例较高",
                    "shortReads=" + r.ioLatency.shortCalls + "/" + r.ioLatency.calls,
                    "检查客户端 buffer、block 边界、DataNode 网络分片；短读本身不一定是故障。"));
            }
        }
        scanJmx(r);
        phaseRules(r);
        if (r.findings.isEmpty()) {
            r.findings.add(new AnalysisReport.Finding("OK", "未发现明显性能异常",
                String.format(Locale.ROOT, "端到端吞吐 %.2f MiB/s，未命中当前规则阈值", r.throughputMiBps),
                "将本报告与同文件类型、同副本数、同时间段的历史基线比较后再判断健康度。"));
        }
        r.findings.add(new AnalysisReport.Finding("INFO", "JMX 归因说明",
            "NameNode/DataNode JMX 是观测窗口内节点累计指标，可能包含其他并发请求。",
            "低并发窗口可信度更高；生产高并发时结合 RPC audit/slow RPC 日志、trace ID 与 DataNode 日志交叉验证。"));
    }

    private static void scanJmx(AnalysisReport r) {
        double exceptions = 0, failures = 0, gcTime = 0, rpcQueue = 0, rpcProcess = 0;
        for (Map<String, Number> metrics : r.jmxDelta.values()) {
            for (Map.Entry<String, Number> e : metrics.entrySet()) {
                String k = e.getKey().toLowerCase(Locale.ROOT);
                double v = e.getValue().doubleValue();
                if (k.contains("exception")) exceptions += v;
                if (k.contains("failure") || k.contains("error")) failures += v;
                if (k.contains("gctime")) gcTime += v;
            }
        }
        for (Map<String, Number> metrics : r.jmxGaugeAfter.values()) {
            for (Map.Entry<String, Number> e : metrics.entrySet()) {
                String k = e.getKey().toLowerCase(Locale.ROOT);
                if (k.contains("rpcqueuetimeavgtime")) rpcQueue = Math.max(rpcQueue, e.getValue().doubleValue());
                if (k.contains("rpcprocessingtimeavgtime")) rpcProcess = Math.max(rpcProcess, e.getValue().doubleValue());
            }
        }
        if (exceptions + failures > 0) r.findings.add(new AnalysisReport.Finding("ERROR", "观测窗口出现 RPC/服务异常",
            "exceptions=" + exceptions + "，failures/errors=" + failures,
            "按节点检查 NameNode/DataNode 日志、RPC detailed activity、磁盘故障与 pipeline recovery。"));
        if (rpcQueue > 50) r.findings.add(new AnalysisReport.Finding("WARN", "NameNode RPC 排队偏高",
            String.format(Locale.ROOT, "最大 RpcQueueTimeAvgTime=%.2f ms", rpcQueue),
            "检查 handler 饱和、客户端小文件风暴、锁竞争、GC、JournalNode 延迟及主机 CPU。"));
        if (rpcProcess > 100) r.findings.add(new AnalysisReport.Finding("WARN", "RPC 服务处理偏慢",
            String.format(Locale.ROOT, "最大 RpcProcessingTimeAvgTime=%.2f ms", rpcProcess),
            "结合 RpcDetailedActivity 找到具体方法，再检查 FSNamesystem 锁、磁盘与下游依赖。"));
        if (gcTime > 1000) r.findings.add(new AnalysisReport.Finding("WARN", "观测窗口 GC 时间较长",
            String.format(Locale.ROOT, "累计 GC 时间增量=%.0f ms", gcTime),
            "核对节点 GC 日志、堆占用、暂停次数及 RPC 延迟时间线。"));
    }

    private static void phaseRules(AnalysisReport r) {
        for (AnalysisReport.Phase p : r.clientPhases) {
            if (p.name.contains("namenode") && p.durationMs > 500) {
                r.findings.add(new AnalysisReport.Finding("WARN", "NameNode 客户端阶段耗时偏高",
                    p.name + "=" + String.format(Locale.ROOT, "%.2f ms", p.durationMs),
                    "拆分检查 DNS/Kerberos、RPC queue/processing、Active NN 切换、元数据锁与 edit log 同步。"));
            }
            if ((p.name.equals("first_byte") || p.name.equals("first_write")) && p.durationMs > 500) {
                r.findings.add(new AnalysisReport.Finding("WARN", "DataNode 首包阶段耗时偏高",
                    p.name + "=" + String.format(Locale.ROOT, "%.2f ms", p.durationMs),
                    "检查 DataNode 选择、TCP 建连、block token、磁盘打开、校验和及 pipeline 建立。"));
            }
            if (p.name.equals("datanode_ack_and_namenode_complete") && p.durationMs > 1000) {
                r.findings.add(new AnalysisReport.Finding("WARN", "上传收尾/ACK 阶段耗时偏高",
                    String.format(Locale.ROOT, "close/complete=%.2f ms", p.durationMs),
                    "重点检查慢 DataNode、packet ACK、pipeline recovery、磁盘 sync 与 NameNode complete RPC。"));
            }
        }
    }

    private static String safe(String s) { return s == null ? "未知错误" : s; }
}
