---
name: Bug 反馈
about: 报告一个可复现的问题
title: "[Bug] "
labels: bug
assignees: ""
---

## 问题描述

<!-- 简述现象 -->

## 复现步骤

1.
2.
3.

## 期望行为

<!-- 你原本期望发生什么 -->

## 实际行为

<!-- 实际发生了什么，附报错信息 / 截图 / request_id -->

## 环境信息

- 版本 / commit：
- 部署模式：lite（VECTOR_ENGINE=es）/ full（VECTOR_ENGINE=milvus）
- 是否配置 `DASHSCOPE_API_KEY`（零 Key 模式 / 全功能模式）：
- 是否启用 GraphRAG（`NEO4J_URI` 是否为空）：
- JDK 版本（`java -version`）与操作系统：
- 中间件版本：MySQL / Elasticsearch（是否装 ik 插件）/ MinIO / Milvus / Neo4j

## 相关日志

<!--
请带上 request_id：它在入口过滤器生成，写进日志 MDC 并透传到 parser，
是串联整条链路最快的抓手。检索类问题请一并附上响应里的 degraded 与 applied 两段。
-->

```
粘贴关键日志
```
