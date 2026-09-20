package com.acme.hdfsperf.core;

import com.acme.hdfsperf.model.AnalysisReport;
import java.util.*;

final class LatencyRecorder {
    private final String operation;
    private final double slowMs;
    private final int sampleLimit;
    private final List<Long> nanos = new ArrayList<>();
    private final PriorityQueue<AnalysisReport.SlowCall> slowest = new PriorityQueue<>(Comparator.comparingDouble(s -> s.durationMs));
    private long bytes;
    private long zero;
    private long shortCalls;
    private long sequence;

    LatencyRecorder(String operation, double slowMs, int sampleLimit) {
        this.operation = operation; this.slowMs = slowMs; this.sampleLimit = sampleLimit;
    }

    synchronized void record(long durationNanos, int actualBytes, int requestedBytes, long offset) {
        sequence++;
        nanos.add(durationNanos);
        if (actualBytes > 0) bytes += actualBytes;
        if (actualBytes == 0) zero++;
        if (actualBytes >= 0 && actualBytes < requestedBytes) shortCalls++;
        double ms = durationNanos / 1_000_000.0;
        AnalysisReport.SlowCall call = new AnalysisReport.SlowCall(sequence, actualBytes, ms, offset);
        if (slowest.size() < sampleLimit) slowest.offer(call);
        else if (slowest.peek().durationMs < ms) { slowest.poll(); slowest.offer(call); }
    }

    synchronized AnalysisReport.IoStatistics snapshot() {
        AnalysisReport.IoStatistics s = new AnalysisReport.IoStatistics();
        s.operation = operation; s.calls = nanos.size(); s.bytes = bytes;
        if (nanos.isEmpty()) return s;
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        long sum = 0; long slow = 0;
        for (long n : sorted) { sum += n; if (n / 1_000_000.0 >= slowMs) slow++; }
        s.meanMs = sum / (double) sorted.size() / 1_000_000.0;
        s.p50Ms = percentile(sorted, 0.50); s.p95Ms = percentile(sorted, 0.95);
        s.p99Ms = percentile(sorted, 0.99); s.maxMs = sorted.get(sorted.size() - 1) / 1_000_000.0;
        s.slowCalls = slow; s.zeroByteCalls = zero; s.shortCalls = shortCalls;
        List<AnalysisReport.SlowCall> calls = new ArrayList<>(slowest);
        calls.sort(Comparator.comparingDouble((AnalysisReport.SlowCall c) -> c.durationMs).reversed());
        s.slowest.addAll(calls);
        return s;
    }

    private static double percentile(List<Long> sorted, double p) {
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, index)) / 1_000_000.0;
    }
}
