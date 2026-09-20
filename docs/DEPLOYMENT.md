# HDFS Performance Inspector 0.1.0 部署运行手册

## 1. 环境要求

- Linux x86_64；
- 64 位 JDK 或 JRE 8；
- 执行用户能够读取 Hadoop 客户端配置及待分析文件；
- 执行节点能够访问 NameNode RPC、DataNode 数据传输端口，以及可选的 HTTP JMX 端口；
- Kerberos 集群应先由执行用户完成 `kinit`，本工具复用 Hadoop 客户端当前登录上下文。

检查环境：

```bash
uname -m                    # 应输出 x86_64
java -version               # 应为 1.8.x 64-Bit
klist                       # Kerberos 集群检查票据，可选
```

## 2. 安装

```bash
tar -xzf hdfs-perf-inspector-0.1.0-linux-x86_64.tar.gz
sudo mv hdfs-perf-inspector-0.1.0-linux-x86_64 /opt/hdfs-inspector
sudo ln -s /opt/hdfs-inspector/bin/hdfs-inspector /usr/local/bin/hdfs-inspector
hdfs-inspector --help
```

如无 root 权限，可直接解压到用户目录并执行 `bin/hdfs-inspector`。

## 3. 推荐部署位置

优先部署在与生产 HDFS 客户端相同的网络区域、使用相同 Hadoop 配置和认证身份的边缘节点。否则 DNS、网络路径、机架与安全认证差异会影响结果可比性。

## 4. 上传分析

工具使用现有本地文件，只执行一次传输，不循环、不并发。建议目标使用专门诊断目录；默认不覆盖、不删除。

```bash
hdfs-inspector upload \
  --source /data/existing-business-file.dat \
  --target hdfs://nameservice1/diagnose/existing-business-file.dat \
  --conf /etc/hadoop/conf \
  --nn-jmx http://nn1:9870,http://nn2:9870 \
  --dn-jmx http://dn1:9864,http://dn2:9864 \
  --report /var/tmp/hdfs-inspector-reports \
  --overwrite false
```

## 5. 下载分析

```bash
hdfs-inspector download \
  --source hdfs://nameservice1/path/business-file.dat \
  --target /var/tmp/business-file.copy \
  --conf /etc/hadoop/conf \
  --nn-jmx http://nn1:9870,http://nn2:9870 \
  --dn-jmx http://dn1:9864,http://dn2:9864 \
  --report /var/tmp/hdfs-inspector-reports
```

## 6. 纯观察模式

用于观察一段既有业务窗口，不产生文件传输流量：

```bash
hdfs-inspector observe \
  --duration 60 \
  --nn-jmx http://nn1:9870,http://nn2:9870 \
  --dn-jmx http://dn1:9864,http://dn2:9864 \
  --report /var/tmp/hdfs-inspector-reports
```

## 7. JMX 鉴权

Basic Auth 场景不要在命令行传密码：

```bash
export HDFS_PERF_JMX_PASSWORD='***'
hdfs-inspector observe --duration 60 --jmx-user monitor \
  --nn-jmx http://nn1:9870 --report ./reports
unset HDFS_PERF_JMX_PASSWORD
```

密码不会进入报告。若 HTTP 端点使用 Kerberos/SPNEGO，建议通过受控监控网关读取 JMX；0.1.0 不直接接收 keytab。

## 8. 生产使用约束

- 上传前确认 HDFS 目标不存在，或明确指定 `--overwrite true`；
- `--delete-target true` 会删除本次命令目标，生产环境默认不要使用；
- JMX 地址尽量填写实际参与文件块读写的 DataNode，以降低共享集群噪声；
- 同一文件、同一客户端节点、相近业务时段重复采样后再比较，不以一次结果替代趋势；
- 报告可能包含 HDFS 路径、主机名和拓扑，外发前应脱敏。

## 9. 卸载

```bash
sudo rm /usr/local/bin/hdfs-inspector
sudo rm -rf /opt/hdfs-inspector
```
