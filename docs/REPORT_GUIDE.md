# 输出结果解读手册

## 1. 报告文件

每次执行生成同一时间戳的三份报告：

- HTML：人工阅读和快速定位；
- JSON：保留完整指标、原始证据，适合归档和自动比对；
- TXT：终端查看和工单粘贴。

先看 `success/error`，再看 `findings`，最后按“客户端阶段 → I/O 长尾 → JMX → 集群日志”的顺序验证。

## 2. 指标的归因边界

| 报告区域 | 含义 | 能否归因到本次传输 |
|---|---|---|
| `clientPhases` | Java 客户端看到的阶段墙钟时间 | 可以，但一个阶段可能包含多个内部动作 |
| `ioLatency` | 本次 read/write 调用的分位数和慢调用 offset | 可以 |
| `jmxDelta` | 操作窗口内节点累计计数器差值 | 不可独占归因，可能混入其他业务 |
| `jmxGaugeBefore/After` | 队列、堆、线程、容量等瞬时值 | 只能做相关性证据 |
| `findings` | 基于上述证据的启发式诊断 | 必须结合基线、日志复核 |

公共 Hadoop API 不提供每个 DFSClient 内部 RPC 或每个 DataNode packet ACK 的独立 trace。因此报告不会把节点 JMX 平均时间冒充为某一次请求的精确 RPC 耗时。

## 3. 上传阶段

| 阶段 | 主要覆盖 | 异常时优先检查 |
|---|---|---|
| `filesystem_initialize` | 配置、DNS、Kerberos、FileSystem 初始化 | DNS、票据、NameNode 可达性、首次连接冷启动 |
| `namenode_exists_rpc` | NameNode `getFileInfo` | RPC 队列、NameNode GC、网络 RTT |
| `namenode_create_and_pipeline` | create、租约、输出流和 pipeline 初始化 | safe mode、权限、块分配、DataNode 可用性 |
| `first_write` | 首个写调用及可能的 pipeline 建立 | DN 连接、block token、TLS、跨机架网络 |
| `client_flush` | Java/HDFS 客户端缓冲刷新 | 持续偏高时检查客户端 CPU/GC；本工具不主动 hflush/hsync |
| `datanode_ack_and_namenode_complete` | 剩余 packet ACK、pipeline 关闭、complete | 慢盘、坏盘、重传、pipeline 恢复、NN complete |
| `transfer_end_to_end` | create 到 close | 用于端到端吞吐，不等同于磁盘裸吞吐 |

## 4. 下载阶段

| 阶段 | 主要覆盖 | 异常时优先检查 |
|---|---|---|
| `namenode_get_file_info_rpc` | 文件元数据读取 | NameNode RPC 队列/处理、GC、网络 RTT |
| `namenode_open_rpc` | open、block location、输入流初始化 | 块位置返回、NN 延迟、客户端初始化 |
| `first_byte` | DN 连接、token、块打开、checksum、首字节 | DN 慢盘、连接建立、跨机架、短路读配置 |
| `hdfs_input_close` | 输入流关闭 | 连接异常或客户端停顿 |
| `transfer_end_to_end` | open 到本地输出完成 | 同时包含本地文件写入影响 |

## 5. I/O 延迟分位数

- `meanMs` 只能看平均水平，不能替代长尾；
- `p95Ms/p99Ms/maxMs` 用于判断抖动；
- `slowCalls` 由 `--slow-ms` 定义；
- `slowest.offset` 可把慢调用定位到文件偏移，再关联块位置与 DataNode；
- `shortCalls` 在下载中不一定是错误，但集中出现且吞吐下降时应检查网络和 DataNode；
- 小文件调用样本过少时，P95/P99 统计意义有限。

不要机械使用固定阈值。建议按同一集群、文件大小区间、读写方向建立 P50/P95/P99 基线，并比较同环比。

## 6. 常见证据组合

| 现象组合 | 更可能的方向 | 下一步证据 |
|---|---|---|
| NN 客户端阶段高，NN RPC queue time 同期增加 | NameNode 排队 | RPC 队列长度、handler 数、GC、热点方法 |
| NN 阶段高，但 queue/processing 无明显变化 | 客户端到 NN 路径或认证 | DNS、TCP RTT、Kerberos、客户端 GC |
| `first_byte` 高，后续吞吐正常 | 连接/块打开启动延迟 | DN 日志、TCP 建连、block token、慢盘日志 |
| write P99 高且 close 高 | DataNode pipeline/ACK 长尾 | DN 磁盘延迟、重传、pipeline recovery、坏盘 |
| 吞吐低但 RPC 正常 | 数据面、客户端或本地盘 | DN 网络/磁盘、CPU、GC、本地目标盘 |
| JMX 指标高但客户端阶段正常 | 共享窗口中的其他业务 | 缩小窗口、限定参与节点、关联 request/audit 日志 |
| GC 时间增长且客户端多个阶段同时抖动 | JVM 停顿相关 | GC 日志、堆使用、分配速率、线程 dump |

## 7. 排障建议顺序

1. 确认执行成功、文件大小和副本数正确；
2. 找出耗时最高的客户端阶段；
3. 查看 I/O P95/P99 和慢调用 offset；
4. 用 JMX 差分判断 NN 排队、处理、DN 磁盘/网络、GC 是否同窗变化；
5. 根据 `file.dataNodes` 和 `file.topologyPaths` 定位节点；
6. 关联 NameNode audit/slow RPC、DataNode slow disk/block receiver 日志；
7. 与同条件历史基线比较，形成结论。

单次报告用于定位，不用于容量结论或压测结论。
