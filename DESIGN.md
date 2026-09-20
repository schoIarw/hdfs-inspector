# 设计与指标口径

## 设计目标

本工具用于“跟随一次真实业务文件传输做诊断”，而不是生成测试负载。上传必须使用用户已有本地文件，下载必须使用用户指定的 HDFS 文件；`observe` 模式完全不执行 HDFS 文件操作。

## 时序模型

### 上传

1. 加载 Hadoop 配置并初始化 `FileSystem`。
2. `exists`：测量 NameNode `getFileInfo` 客户端墙钟耗时。
3. `create`：测量 NameNode create、租约及输出 pipeline 初始化阶段。
4. `write`：记录每次业务缓冲写入耗时、offset、分位数和长尾。
5. `flush`：只刷新客户端标准缓冲，不主动插入 `hflush/hsync`。
6. `close`：测量剩余 DataNode packet ACK、pipeline 关闭和 NameNode complete。
7. `getFileStatus`：校验最终长度。

### 下载

1. `getFileStatus`：NameNode 元数据 RPC。
2. `open`：NameNode block location 获取及输入流初始化。
3. 首次 `read`：DataNode TCP、block token、磁盘块打开、checksum 和首字节的综合墙钟耗时。
4. 后续 `read`：逐调用延迟分布、慢调用 offset 与吞吐。
5. `close`：关闭 DataNode 输入流并校验本地文件长度。

## 指标分层

| 层次 | 代表指标 | 归因精度 |
|---|---|---|
| 客户端阶段 | exists/create/open/first-byte/close/total | 本次操作精确墙钟耗时，但一个阶段可能包含多个内部动作 |
| 客户端 I/O | read/write mean、P50/P95/P99/max、慢调用 offset | 本次操作精确值 |
| NameNode JMX | RPC queue/processing、detailed method counts、异常、FSNamesystem、GC | 节点观测窗口；共享集群可能混入其他请求 |
| DataNode JMX | read/write bytes、packet/ack、磁盘、网络、block、异常、GC | 节点观测窗口；应尽量只传实际参与节点 |
| 报告规则 | RPC 排队、处理、GC、异常、首包、close、I/O 长尾 | 启发式，不替代历史基线与日志证据 |

## 为什么不声称“每条 RPC 精确耗时”

Hadoop 公共 `FileSystem` API 不向调用者暴露 DFSClient 内部每条 RPC、每个 packet ACK 的独立时钟。要获得这种粒度通常需要修改客户端、注入 agent、开启 tracing 或修改服务端，这会引入版本耦合和额外开销。本工具选择可长期维护的公共 API + JMX 方案，并在报告里明确证据边界。

若需要进一步精确归因，可将本工具报告中的时间窗口与以下证据关联：

- NameNode slow RPC / audit log；
- `RpcDetailedActivityForPort*` 方法级指标；
- DataNode block receiver/sender 与 slow disk 日志；
- Hadoop/OpenTelemetry tracing（集群已启用时）；
- OS 层磁盘延迟、TCP retransmit 和 GC 日志。

## 失败与数据安全

- 默认不覆盖目标，不删除任何文件。
- `--delete-target=true` 仅删除本次命令的目标；上传时删除 HDFS 目标，下载时删除本地目标。
- 即使传输失败，也尽量采集结束快照并写出失败报告。
- JMX 不可达不会中断文件传输，错误会写入报告。
- JMX 密码只从环境变量读取，不输出到报告。
