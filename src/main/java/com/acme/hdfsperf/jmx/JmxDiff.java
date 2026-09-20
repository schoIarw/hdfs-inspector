package com.acme.hdfsperf.jmx;

import com.acme.hdfsperf.model.AnalysisReport;
import java.util.*;

public final class JmxDiff {
    private JmxDiff() {}

    public static void apply(JmxSnapshot before, JmxSnapshot after, AnalysisReport report) {
        report.jmxErrors.addAll(before.errors);
        report.jmxErrors.addAll(after.errors);
        Set<String> endpoints = new TreeSet<>();
        endpoints.addAll(before.endpoints.keySet()); endpoints.addAll(after.endpoints.keySet());
        for (String endpoint : endpoints) {
            Map<String, Number> b = before.endpoints.getOrDefault(endpoint, Collections.emptyMap());
            Map<String, Number> a = after.endpoints.getOrDefault(endpoint, Collections.emptyMap());
            report.jmxGaugeBefore.put(endpoint, new TreeMap<>(b));
            report.jmxGaugeAfter.put(endpoint, new TreeMap<>(a));
            Map<String, Number> delta = new TreeMap<>();
            for (Map.Entry<String, Number> entry : a.entrySet()) {
                Number old = b.get(entry.getKey());
                if (old == null || !isCounter(entry.getKey())) continue;
                double d = entry.getValue().doubleValue() - old.doubleValue();
                if (d >= 0 && d != 0) delta.put(entry.getKey(), integral(entry.getValue(), old) ? (long) d : d);
            }
            report.jmxDelta.put(endpoint, delta);
        }
    }

    private static boolean integral(Number a, Number b) {
        return !(a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float);
    }

    private static boolean isCounter(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("avgtime") || k.contains("quantile") || k.contains("percentile") ||
            k.endsWith(".value") || k.contains("numopenconnections") || k.contains("queue length") ||
            k.contains("capacity") || k.contains("remaining") || k.contains("used") ||
            k.contains("load") || k.contains("threads") || k.contains("memheap") ||
            k.contains("pending") || k.contains("volume") || k.contains("numlive")) return false;
        return k.contains("num") || k.contains("count") || k.contains("total") ||
            k.contains("bytes") || k.contains("ops") || k.contains("time") ||
            k.contains("exceptions") || k.contains("failures") || k.contains("errors");
    }
}
