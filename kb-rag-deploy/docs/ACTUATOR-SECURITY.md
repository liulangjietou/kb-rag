# Actuator 安全部署指南

kb-rag-server 将 Actuator 作为独立的运维平面处理，不把健康信息和 Prometheus 指标与
`20000` 业务 API 共同暴露。

## 安全默认值

| 配置 | 默认值 | 作用 |
|---|---:|---|
| `SERVER_PORT` | `20000` | 管理台与开放 API 业务端口 |
| `MANAGEMENT_SERVER_PORT` | `20003` | Actuator 独立端口 |
| `MANAGEMENT_SERVER_ADDRESS` | `127.0.0.1` | 只允许本机访问管理端口 |

管理端点只暴露 `health`、`info`、`prometheus`。`health` 默认只返回聚合状态，不返回
MySQL、Elasticsearch、MinIO、Qdrant 或 Neo4j 的组件名称、地址与失败详情；具体原因从应用
日志排查。

本机验证：

```bash
curl -fsS http://127.0.0.1:20003/actuator/health
curl -fsS http://127.0.0.1:20003/actuator/prometheus | head
```

访问 `http://127.0.0.1:20000/actuator/health` 应得到 404，证明管理平面没有挂在业务端口。

## 远程 Prometheus 抓取

优先让 Prometheus 与 kb-rag-server 位于同一主机，或者通过 SSH 隧道、服务网格等受控通道
访问回环端口。确实需要监听非回环地址时，可以显式配置：

```bash
MANAGEMENT_SERVER_ADDRESS=0.0.0.0
MANAGEMENT_SERVER_PORT=20003
```

这两个配置只负责监听，不提供应用层身份认证。修改为非回环地址前必须同时满足：

1. 云安全组或主机防火墙只允许 Prometheus 固定来源地址访问 `20003`。
2. 不把 `20003` 发布到公网；跨不可信网络时，在前置反向代理启用 TLS 与身份认证。
3. 反向代理只转发 `/actuator/prometheus` 和必要的健康路径，不开放其他路径。
4. `MANAGEMENT_SERVER_PORT` 不得与 `SERVER_PORT` 相同，否则会失去独立监听器提供的隔离边界。

项目不复用控制台 Bearer Token 保护 Actuator：监控采集器不是控制台用户，把两类凭据混在
同一认证链中会扩大控制台 Token 的分发范围。需要远程采集时，认证与来源限制应收敛在专用的
运维网络或反向代理边界。
