package com.acme.hdfsperf.jmx;

import java.time.Instant;
import java.util.*;

public final class JmxSnapshot {
    public final Instant capturedAt = Instant.now();
    public final Map<String, Map<String, Number>> endpoints = new TreeMap<>();
    public final List<String> errors = new ArrayList<>();
}
