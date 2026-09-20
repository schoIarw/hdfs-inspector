package com.acme.hdfsperf.model;

import java.time.Instant;
import java.util.*;

public final class AnalysisReport {
    public String toolVersion = "1.0.0";
    public String reportId = UUID.randomUUID().toString();
    public Instant startedAt = Instant.now();
    public Instant finishedAt;
    public String command;
    public String source;
    public String target;
    public boolean success;
    public String error;
    public long bytes;
    public double throughputMiBps;
    public final Map<String, String> environment = new LinkedHashMap<>();
    public final Map<String, String> options = new LinkedHashMap<>();
    public final List<Phase> clientPhases = new ArrayList<>();
    public IoStatistics ioLatency;
    public final Map<String, Map<String, Number>> jmxDelta = new TreeMap<>();
    public final Map<String, Map<String, Number>> jmxGaugeBefore = new TreeMap<>();
    public final Map<String, Map<String, Number>> jmxGaugeAfter = new TreeMap<>();
    public final List<String> jmxErrors = new ArrayList<>();
    public final List<Finding> findings = new ArrayList<>();

    public void phase(String name, long nanos, String attribution) {
        clientPhases.add(new Phase(name, nanos / 1_000_000.0, attribution));
    }

    public static final class Phase {
        public final String name;
        public final double durationMs;
        public final String attribution;
        public Phase(String name, double durationMs, String attribution) {
            this.name = name; this.durationMs = durationMs; this.attribution = attribution;
        }
    }

    public static final class IoStatistics {
        public String operation;
        public long calls;
        public long bytes;
        public double meanMs;
        public double p50Ms;
        public double p95Ms;
        public double p99Ms;
        public double maxMs;
        public long slowCalls;
        public long zeroByteCalls;
        public long shortCalls;
        public final List<SlowCall> slowest = new ArrayList<>();
    }

    public static final class SlowCall {
        public final long sequence;
        public final int bytes;
        public final double durationMs;
        public final long offset;
        public SlowCall(long sequence, int bytes, double durationMs, long offset) {
            this.sequence = sequence; this.bytes = bytes; this.durationMs = durationMs; this.offset = offset;
        }
    }

    public static final class Finding {
        public final String severity;
        public final String title;
        public final String evidence;
        public final String recommendation;
        public Finding(String severity, String title, String evidence, String recommendation) {
            this.severity = severity; this.title = title; this.evidence = evidence; this.recommendation = recommendation;
        }
    }
}
