---
name: 功能建议
about: 提出一个新功能或改进建议
title: "[Feature] "
labels: enhancement
assignees: ""
---

## 背景 / 要解决的问题

<!-- 这个建议解决了什么实际问题？如果没有这个功能，现状有什么痛点？ -->

## 建议方案

<!-- 你期望的实现方式；如果涉及接口变更，请给出大致的 API 设计 -->

## 涉及的层次

- [ ] kb-api（HTTP 边界：Controller / DTO / 过滤器）
- [ ] kb-app（应用编排：检索、索引管线、评测、发布、导入等）
- [ ] kb-domain（领域算法或新的出站端口）
- [ ] kb-infrastructure（新的中间件或模型 Provider 实现）
- [ ] schema（需要新增 Flyway 迁移）
- [ ] 跨仓（同时涉及 kb-rag-parser / kb-rag-web / kb-rag-deploy）

## 替代方案

<!-- 是否考虑过其他方案？为什么最终倾向于上面的建议？ -->

## 补充信息

<!-- 相关 issue / 参考链接 / 截图；若与既有契约（kb-rag-deploy/docs/M*-CONTRACTS.md）有冲突请说明 -->
