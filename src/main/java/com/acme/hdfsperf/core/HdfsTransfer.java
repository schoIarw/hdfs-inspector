package com.acme.hdfsperf.core;

import com.acme.hdfsperf.cli.Arguments;
import com.acme.hdfsperf.model.AnalysisReport;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class HdfsTransfer {
    private final Arguments args;
    private final AnalysisReport report;
    private final int bufferSize;
    private final LatencyRecorder recorder;

    HdfsTransfer(Arguments args, AnalysisReport report) {
        this.args = args;
        this.report = report;
        this.bufferSize = args.getInt("buffer-size", 64 * 1024);
        this.recorder = new LatencyRecorder(args.command() == Arguments.Command.UPLOAD ? "write" : "read",
            args.getInt("slow-ms", 100), args.getInt("sample-limit", 20));
    }

    void execute() throws Exception {
        if (bufferSize < 4096) throw new IllegalArgumentException("--buffer-size 不能小于 4096");
        Configuration conf = buildConfiguration();
        if (args.command() == Arguments.Command.UPLOAD) upload(conf); else download(conf);
        report.ioLatency = recorder.snapshot();
    }

    private Configuration buildConfiguration() {
        long start = System.nanoTime();
        Configuration conf = new Configuration();
        String dir = args.get("conf");
        if (dir != null) {
            java.nio.file.Path base = java.nio.file.Paths.get(dir);
            addIfPresent(conf, base.resolve("core-site.xml"));
            addIfPresent(conf, base.resolve("hdfs-site.xml"));
        }
        recordConfiguration(conf);
        report.phase("load_hadoop_configuration", System.nanoTime() - start, "本地配置解析");
        return conf;
    }

    private void recordConfiguration(Configuration conf) {
        String[] keys = {
            "fs.defaultFS", "dfs.blocksize", "dfs.replication", "io.file.buffer.size",
            "dfs.client-write-packet-size", "dfs.client.socket-timeout",
            "dfs.client.hedged.read.threadpool.size", "dfs.client.hedged.read.threshold.millis",
            "dfs.client.read.shortcircuit", "dfs.client.domain.socket.data.traffic",
            "ipc.client.connect.timeout", "ipc.client.connect.max.retries",
            "ipc.client.rpc-timeout.ms", "hadoop.security.authentication"
        };
        for (String key : keys) {
            String value = conf.get(key);
            if (value != null) report.environment.put("config." + key, value);
        }
        report.environment.put("effectiveBufferSize", String.valueOf(bufferSize));
    }

    private static void addIfPresent(Configuration conf, java.nio.file.Path path) {
        if (Files.isRegularFile(path)) conf.addResource(new org.apache.hadoop.fs.Path(path.toUri()));
    }

    private void upload(Configuration conf) throws Exception {
        java.nio.file.Path local = java.nio.file.Paths.get(args.get("source")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(local)) throw new FileNotFoundException("本地源文件不存在: " + local);
        report.bytes = Files.size(local);
        Path target = new Path(args.get("target"));
        URI uri = target.toUri();

        long fsStart = System.nanoTime();
        try (FileSystem fs = FileSystem.get(uri, conf)) {
            report.phase("filesystem_initialize", System.nanoTime() - fsStart, "客户端初始化、DNS/Kerberos及可能的 NameNode 握手");
            report.environment.put("filesystemUri", fs.getUri().toString());
            report.environment.put("hadoopVersion", org.apache.hadoop.util.VersionInfo.getVersion());

            long statusStart = System.nanoTime();
            boolean existed = fs.exists(target);
            report.phase("namenode_exists_rpc", System.nanoTime() - statusStart, "NameNode getFileInfo RPC（精确客户端墙钟时间）");
            boolean overwrite = args.getBoolean("overwrite", false);
            if (existed && !overwrite) throw new IOException("目标已存在且 --overwrite=false: " + target);

            long total = System.nanoTime();
            try (InputStream in = new BufferedInputStream(Files.newInputStream(local), bufferSize)) {
                long createStart = System.nanoTime();
                FSDataOutputStream out = fs.create(target, overwrite, bufferSize);
                report.phase("namenode_create_and_pipeline", System.nanoTime() - createStart,
                    "NameNode create RPC + 客户端输出流/数据管道初始化");
                try (FSDataOutputStream managed = out) {
                    byte[] buf = new byte[bufferSize];
                    long offset = 0;
                    boolean first = true;
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (n == 0) continue;
                        long callStart = System.nanoTime();
                        managed.write(buf, 0, n);
                        long elapsed = System.nanoTime() - callStart;
                        recorder.record(elapsed, n, n, offset);
                        if (first) {
                            report.phase("first_write", elapsed, "首次写入客户端缓冲/可能触发 DataNode pipeline");
                            first = false;
                        }
                        offset += n;
                    }
                    long flushStart = System.nanoTime();
                    managed.flush();
                    report.phase("client_flush", System.nanoTime() - flushStart, "Java/HDFS 客户端缓冲刷新；未主动调用 hflush/hsync");
                    long closeStart = System.nanoTime();
                    managed.close();
                    report.phase("datanode_ack_and_namenode_complete", System.nanoTime() - closeStart,
                        "等待剩余 packet ACK、关闭 pipeline、NameNode complete RPC");
                }
            }
            long elapsed = System.nanoTime() - total;
            report.phase("transfer_end_to_end", elapsed, "文件 create 至 close 的端到端耗时");
            report.throughputMiBps = mibPerSecond(report.bytes, elapsed);

            long verifyStart = System.nanoTime();
            FileStatus status = fs.getFileStatus(target);
            report.phase("namenode_verify_rpc", System.nanoTime() - verifyStart, "NameNode getFileInfo RPC");
            if (status.getLen() != report.bytes) throw new IOException("上传后文件长度不一致: expected=" + report.bytes + ", actual=" + status.getLen());
            recordFileLayout(fs, status);
            maybeDeleteHdfs(fs, target);
        }
    }

    private void download(Configuration conf) throws Exception {
        Path source = new Path(args.get("source"));
        java.nio.file.Path local = java.nio.file.Paths.get(args.get("target")).toAbsolutePath().normalize();
        if (Files.exists(local) && !args.getBoolean("overwrite", false)) {
            throw new IOException("本地目标已存在且 --overwrite=false: " + local);
        }
        if (local.getParent() != null) Files.createDirectories(local.getParent());
        long fsStart = System.nanoTime();
        try (FileSystem fs = FileSystem.get(source.toUri(), conf)) {
            report.phase("filesystem_initialize", System.nanoTime() - fsStart, "客户端初始化、DNS/Kerberos及可能的 NameNode 握手");
            report.environment.put("filesystemUri", fs.getUri().toString());
            report.environment.put("hadoopVersion", org.apache.hadoop.util.VersionInfo.getVersion());

            long statusStart = System.nanoTime();
            FileStatus status = fs.getFileStatus(source);
            report.phase("namenode_get_file_info_rpc", System.nanoTime() - statusStart, "NameNode getFileInfo RPC（精确客户端墙钟时间）");
            report.bytes = status.getLen();
            recordFileLayout(fs, status);

            long total = System.nanoTime();
            long openStart = System.nanoTime();
            FSDataInputStream in = fs.open(source, bufferSize);
            report.phase("namenode_open_rpc", System.nanoTime() - openStart, "NameNode open/getBlockLocations + 输入流初始化");
            try (FSDataInputStream managed = in;
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(local,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), bufferSize)) {
                byte[] buf = new byte[bufferSize];
                long offset = 0;
                boolean first = true;
                while (true) {
                    long readStart = System.nanoTime();
                    int n = managed.read(buf);
                    long elapsed = System.nanoTime() - readStart;
                    recorder.record(elapsed, n, bufferSize, offset);
                    if (first && n > 0) {
                        report.phase("first_byte", elapsed, "DataNode 连接、block token、校验和验证及首字节读取");
                        first = false;
                    }
                    if (n < 0) break;
                    if (n == 0) continue;
                    out.write(buf, 0, n);
                    offset += n;
                }
                long flushStart = System.nanoTime();
                out.flush();
                report.phase("local_file_flush", System.nanoTime() - flushStart, "本地 Java 输出缓冲刷新（不等同于 fsync）");
                long closeStart = System.nanoTime();
                managed.close();
                report.phase("hdfs_input_close", System.nanoTime() - closeStart, "DataNode 输入流关闭");
            }
            long elapsed = System.nanoTime() - total;
            report.phase("transfer_end_to_end", elapsed, "HDFS open 至本地输出关闭的端到端耗时");
            report.throughputMiBps = mibPerSecond(report.bytes, elapsed);
            long actual = Files.size(local);
            if (actual != report.bytes) throw new IOException("下载后文件长度不一致: expected=" + report.bytes + ", actual=" + actual);
            if (args.getBoolean("delete-target", false)) Files.delete(local);
        }
    }

    private void maybeDeleteHdfs(FileSystem fs, Path target) throws IOException {
        if (args.getBoolean("delete-target", false) && !fs.delete(target, false)) {
            throw new IOException("--delete-target=true，但删除目标失败: " + target);
        }
    }

    private void recordFileLayout(FileSystem fs, FileStatus status) throws IOException {
        report.environment.put("file.length", String.valueOf(status.getLen()));
        report.environment.put("file.blockSize", String.valueOf(status.getBlockSize()));
        report.environment.put("file.replication", String.valueOf(status.getReplication()));
        long start = System.nanoTime();
        BlockLocation[] blocks = fs.getFileBlockLocations(status, 0, status.getLen());
        report.phase("namenode_block_locations_rpc", System.nanoTime() - start, "NameNode getBlockLocations RPC；用于定位实际 DataNode");
        report.environment.put("file.blockCount", String.valueOf(blocks.length));
        Set<String> hosts = new TreeSet<>();
        Set<String> topology = new TreeSet<>();
        for (BlockLocation block : blocks) {
            Collections.addAll(hosts, block.getHosts());
            Collections.addAll(topology, block.getTopologyPaths());
        }
        report.environment.put("file.dataNodes", String.join(",", hosts));
        report.environment.put("file.topologyPaths", String.join(",", topology));
    }

    private static double mibPerSecond(long bytes, long nanos) {
        if (nanos <= 0) return 0;
        return bytes / 1048576.0 / (nanos / (double) TimeUnit.SECONDS.toNanos(1));
    }
}
