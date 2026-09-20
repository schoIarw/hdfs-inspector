# HDFS Performance Inspector

一个纯 Java、非压测式的 HDFS 上传/下载性能分析工具。工具只分析一次指定的真实文件传输，或只观察一段时间内的 Hadoop JMX 指标，不生成并发流量、不循环施压。

## 能力边界

- 精确记录客户端可见的阶段耗时：初始化、NameNode 元数据调用、创建/打开文件、首包写入/首字节读取、数据流、flush、close/complete、端到端耗时。
- 统计每次 `read/write` 的调用耗时分布、吞吐、P50/P95/P99、最大停顿、短读、零字节读等。
- 对操作前后的 NameNode、DataNode JMX 快照做差，输出 RPC 队列/处理时间、调用量、异常、DataNode 磁盘、网络、GC、堆、线程、块与容量等指标。
- 输出 JSON、HTML 和纯文本报告，并给出诊断结论与排查建议。
- 不修改 HDFS 服务端，不注入字节码，不调用 `hflush/hsync` 制造额外 RPC。

> 注意：Hadoop 公共客户端 API 不暴露“当前一次操作的每条 NameNode/DataNode RPC 耗时”。JMX 是节点级累计指标，因此报告将其标记为“观测窗口差值”；共享集群并发较高时不能将其冒充为本次操作独占耗时。客户端阶段计时则是本次操作的精确值。

## 构建

需要 64 位 JDK 8 与 Maven 3.8+：

```bash
mvn clean package
```

生成：

- `target/hdfs-perf-inspector-0.1.0.jar`：可执行 fat JAR；
- `target/hdfs-perf-inspector-0.1.0-linux-x86_64.tar.gz`：Linux x86_64 安装介质；
- `target/hdfs-perf-inspector-0.1.0-linux-x86_64.zip`：ZIP 安装介质。

完整部署步骤见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)，指标口径和结果判读见 [docs/REPORT_GUIDE.md](docs/REPORT_GUIDE.md)。

## 上传分析

```bash
java -jar target/hdfs-perf-inspector-0.1.0.jar upload \
  --source /data/business.dat \
  --target hdfs://nameservice1/diagnose/business.dat \
  --conf /etc/hadoop/conf \
  --nn-jmx http://nn1:9870,http://nn2:9870 \
  --dn-jmx http://dn1:9864,http://dn2:9864 \
  --report ./reports \
  --overwrite false
```

## 下载分析

```bash
java -jar target/hdfs-perf-inspector-0.1.0.jar download \
  --source hdfs://nameservice1/path/business.dat \
  --target /data/business.dat.copy \
  --conf /etc/hadoop/conf \
  --nn-jmx http://nn1:9870 \
  --dn-jmx http://dn1:9864,http://dn2:9864 \
  --report ./reports
```

## 只观察、不传输

```bash
java -jar target/hdfs-perf-inspector-0.1.0.jar observe \
  --duration 60 \
  --nn-jmx http://nn1:9870 \
  --dn-jmx http://dn1:9864,http://dn2:9864 \
  --report ./reports
```

JMX 地址既可写 Web UI 根地址，也可写完整 `/jmx` 地址。生产环境开启鉴权时，可通过 `--jmx-user` 和环境变量 `HDFS_PERF_JMX_PASSWORD` 提供 Basic Auth；密码不会写入报告。Kerberos/SPNEGO JMX 网关建议在网关侧转换认证，工具当前不接收 keytab 参数。

## 常用选项

| 参数 | 含义 | 默认值 |
|---|---|---|
| `--buffer-size` | 客户端读写缓冲区 | Hadoop 配置或 64 KiB |
| `--sample-limit` | 保留的最慢调用样本数 | 20 |
| `--slow-ms` | 慢读写调用阈值 | 100 ms |
| `--jmx-timeout-ms` | 单个 JMX 请求超时 | 5000 ms |
| `--delete-target` | 分析完成后删除目标文件 | false |
| `--overwrite` | 上传时允许覆盖 | false |

`--delete-target` 默认关闭，避免破坏业务数据。工具不会自行创建测试文件。

## JMX 开放建议

确认 NameNode/DataNode HTTP 端点可访问 `/jmx`。如集群禁止直连，可只运行传输阶段分析；报告会记录 JMX 不可达，不影响文件操作。建议传入实际承载该文件块的 DataNode 地址，以减少无关节点噪声。

## 报告解读

- `clientPhases`：本次 Java 客户端实际阶段计时，可直接归因。
- `ioLatency`：本次传输的 read/write 分位数与慢调用样本。
- `jmxDelta`：观测窗口内节点累计值差，属于相关性证据。
- `jmxGaugeBefore/After`：容量、队列深度、堆、线程等瞬时值，不做差。
- `findings`：按严重级别输出原因、证据和建议。

## 安全说明

报告会包含 HDFS URI、节点地址和性能指标。若路径敏感，分享报告前请脱敏。工具不会在报告中保存 JMX 密码。
