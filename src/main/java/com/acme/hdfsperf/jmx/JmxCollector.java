package com.acme.hdfsperf.jmx;

import com.acme.hdfsperf.cli.Arguments;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class JmxCollector implements AutoCloseable {
    private static final List<String> RELEVANT = Arrays.asList(
        "rpcactivity", "rpcdetailedactivity", "namenodeactivity", "fsnamesystem",
        "jvmetrics", "jvmmetrics", "datanodeactivity", "fsdatasetstate", "ugimetrics",
        "retrycache", "snapshotinfo", "startup", "pausemonitor");

    private final List<String> endpoints = new ArrayList<>();
    private final HttpClient client;
    private final ExecutorService pool;
    private final Duration timeout;
    private final String authorization;

    public JmxCollector(Arguments args) {
        endpoints.addAll(args.csv("nn-jmx"));
        endpoints.addAll(args.csv("dn-jmx"));
        timeout = Duration.ofMillis(args.getInt("jmx-timeout-ms", 5000));
        pool = Executors.newFixedThreadPool(Math.max(1, Math.min(8, endpoints.size())));
        client = HttpClient.newBuilder().connectTimeout(timeout).executor(pool).build();
        String user = args.get("jmx-user");
        String password = System.getenv("HDFS_PERF_JMX_PASSWORD");
        authorization = user == null ? null : "Basic " + Base64.getEncoder().encodeToString(
            (user + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
    }

    public boolean configured() { return !endpoints.isEmpty(); }

    public JmxSnapshot capture() {
        JmxSnapshot snapshot = new JmxSnapshot();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (String endpoint : endpoints) {
            tasks.add(CompletableFuture.runAsync(() -> captureOne(endpoint, snapshot), pool));
        }
        for (CompletableFuture<Void> task : tasks) {
            try { task.get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS); }
            catch (Exception e) { synchronized (snapshot.errors) { snapshot.errors.add("JMX 采集任务失败: " + rootMessage(e)); } }
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private void captureOne(String endpoint, JmxSnapshot snapshot) {
        String normalized = normalize(endpoint);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(normalized)).timeout(timeout).GET()
                .header("Accept", "application/json").header("User-Agent", "hdfs-perf-inspector/1.0");
            if (authorization != null) builder.header("Authorization", authorization);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
            Object parsed = MiniJson.parse(response.body());
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("JMX 响应不是 JSON 对象");
            Object beanValue = ((Map<String, Object>) parsed).get("beans");
            if (!(beanValue instanceof List)) throw new IllegalArgumentException("JMX 响应缺少 beans 数组");
            Map<String, Number> metrics = new TreeMap<>();
            for (Object beanObj : (List<?>) beanValue) {
                if (!(beanObj instanceof Map)) continue;
                Map<String, Object> bean = (Map<String, Object>) beanObj;
                String name = String.valueOf(bean.getOrDefault("name", "unknown"));
                if (!relevant(name)) continue;
                for (Map.Entry<String, Object> entry : bean.entrySet()) {
                    if (entry.getValue() instanceof Number) {
                        metrics.put(compact(name) + "." + entry.getKey(), (Number) entry.getValue());
                    }
                }
            }
            synchronized (snapshot.endpoints) { snapshot.endpoints.put(normalized, metrics); }
        } catch (Exception e) {
            synchronized (snapshot.errors) { snapshot.errors.add(normalized + ": " + rootMessage(e)); }
        }
    }

    private static boolean relevant(String bean) {
        String lower = bean.toLowerCase(Locale.ROOT);
        for (String token : RELEVANT) if (lower.contains(token)) return true;
        return false;
    }

    private static String compact(String name) {
        String service = part(name, "service=");
        String bean = part(name, "name=");
        return (service.isEmpty() ? "Hadoop" : service) + "/" + (bean.isEmpty() ? name : bean);
    }

    private static String part(String value, String key) {
        int start = value.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = value.indexOf(',', start);
        return value.substring(start, end < 0 ? value.length() : end);
    }

    private static String normalize(String endpoint) {
        String value = endpoint.trim();
        if (!value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) value = "http://" + value;
        if (value.endsWith("/jmx")) return value;
        if (value.endsWith("/")) return value + "jmx";
        return value + "/jmx";
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
    }

    @Override public void close() { pool.shutdownNow(); }
}
