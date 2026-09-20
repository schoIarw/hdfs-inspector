package com.acme.hdfsperf.core;

import com.acme.hdfsperf.model.AnalysisReport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LatencyRecorderTest {
    @Test void computesPercentilesAndSlowSamples() {
        LatencyRecorder r = new LatencyRecorder("read", 5, 2);
        r.record(1_000_000, 10, 10, 0);
        r.record(5_000_000, 10, 10, 10);
        r.record(20_000_000, 5, 10, 20);
        AnalysisReport.IoStatistics s = r.snapshot();
        assertEquals(3, s.calls);
        assertEquals(25, s.bytes);
        assertEquals(5.0, s.p50Ms);
        assertEquals(20.0, s.p95Ms);
        assertEquals(2, s.slowCalls);
        assertEquals(1, s.shortCalls);
        assertEquals(2, s.slowest.size());
        assertEquals(20.0, s.slowest.get(0).durationMs);
    }
}
