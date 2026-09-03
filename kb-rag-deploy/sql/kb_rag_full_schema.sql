-- ============================================================================
-- kb-rag 全量建表语句（含 RBAC 权限码 / 内置角色 / 默认租户种子数据）
--
-- 生成方式：把 main 分支（86bd9ae）db/migration 下的 V1~V25 全部 25 个 Flyway
--           迁移脚本，按版本号顺序在一个全新空库上完整执行一遍，再用
--           mysqldump --no-data 导出最终结构。不是手工拼接迁移脚本，也不是
--           从开发库导出，因此不含任何开发数据留下的痕迹（AUTO_INCREMENT
--           起始值已归零，租户克隆出来的角色实例不会混进种子数据）。
-- 数据库：MySQL 8.0.36，utf8mb4 / utf8mb4_general_ci，共 48 张表
--
-- 用途说明：本文件是「结构快照存档」，不是新的 Flyway 迁移脚本，不要放进
--           kb-rag-server/kb-api/src/main/resources/db/migration/。
--           全新环境的正确建库方式仍然是启动 kb-rag-server，由 Flyway 按
--           V1~V25 顺序自动建表（application.yml 中 flyway.enabled: true）。
--           本文件适用于：查阅表结构、离线环境手工建库、结构评审与比对。
--
-- 关于"菜单数据"：项目没有独立的菜单表。RBAC 采用"权限码 + 模块分组"模型
--           （t_kb_permission，21 个权限码分 7 个模块），前端菜单与路由按权限码
--           控制显隐，因此文件末尾的权限码目录就是完整的菜单数据。
--
-- 种子数据按依赖顺序排列：t_kb_tenant 必须在 t_kb_role 之前，因为角色行的
--           tenant_id 全部指向默认租户 tnt_default0000000。
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================================
-- 一、表结构（48 张）
-- ============================================================================

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_admin_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `user_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '控制台对外暴露的业务标识',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识',
  `username` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录名',
  `display_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '展示名，域账号首次登录时取登录名兜底',
  `email` varchar(256) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '邮箱，仅作展示与联系用途',
  `source` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'LOCAL' COMMENT '账号来源：LOCAL 本地建号 / LDAP 域账号 / OIDC / SAML / CAS 单点登录建号',
  `status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'ENABLED' COMMENT '账号状态：ENABLED 启用 / DISABLED 停用（停用即拒绝登录并撤销会话）',
  `password_hash` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'BCrypt 摘要，接口永不回传；域账号为 null（密码由域控校验）',
  `must_change_password` tinyint NOT NULL DEFAULT '1' COMMENT '置 1 时登录后强制改密',
  `last_login_at` datetime DEFAULT NULL COMMENT '最近一次登录成功时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='控制台用户账号，本地建号与域账号单点登录共用一张表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_annotation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `annotation_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属知识库',
  `doc_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属文档，跨版本保持稳定',
  `document_version_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '执行本次操作时所针对的文档版本',
  `chunk_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作目标分片；合并或拆分时为第一个源分片',
  `annotation_type` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作类型：EDIT 编辑 / TOGGLE 启停 / MERGE 合并 / SPLIT 拆分',
  `payload` json DEFAULT NULL COMMENT '操作载荷：源分片 id、拆分偏移、摘录片段、启停状态',
  `chunk_text_hash` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '归一化文本摘要，驱动跨版本的标注继承',
  `inherit_status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '继承状态：NOT_INHERITED 未继承 / AUTO_INHERITED 自动继承 / REDONE 已重做',
  `idempotency_key` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '幂等键，用于识别重复提交的同一次操作',
  `operator` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '执行本次操作的控制台账号',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_annotation_id` (`annotation_id`),
  KEY `idx_kb_id` (`kb_id`),
  KEY `idx_doc_id` (`doc_id`),
  KEY `idx_document_version_id` (`document_version_id`),
  KEY `idx_chunk_id` (`chunk_id`),
  KEY `idx_chunk_text_hash` (`chunk_text_hash`),
  KEY `idx_idempotency_key` (`idempotency_key`),
  KEY `idx_doc_type` (`doc_id`,`annotation_type`),
  KEY `idx_doc_inherit` (`doc_id`,`inherit_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='人工分片操作审计流水';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_api_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `audit_log_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '控制台查询接口对外暴露的业务标识',
  `key_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '发起调用的 API Key 标识，绝不记录明文 Key',
  `app_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '被调用的应用',
  `app_version_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '实际服务的应用版本，请求被拒绝时为 null',
  `target_stage` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '被调用的版本阶段：RELEASE 正式 / BETA 灰度',
  `endpoint` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '被调用的端点：search/chat',
  `query_digest` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '脱敏并截断后的查询文本',
  `hit_doc_ids` json DEFAULT NULL COMMENT '返回结果所属的文档 id 列表',
  `latency_ms` int NOT NULL DEFAULT '0' COMMENT '服务端耗时，毫秒',
  `degraded` json DEFAULT NULL COMMENT '本次调用的降级标记',
  `override_keys` json DEFAULT NULL COMMENT '本次生效的请求级覆盖参数，对应需求文档第 5 节',
  `error_code` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '调用被拒绝时的业务错误码',
  `request_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '本次调用的链路追踪 id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_audit_log_id` (`audit_log_id`),
  KEY `idx_key_id` (`key_id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_key_created` (`key_id`,`created_at`),
  KEY `idx_version_stage` (`app_version_id`,`target_stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='对外接口调用审计，超过保留期后归档到对象存储';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_api_key` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `key_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识',
  `name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '该 Key 所签发给的调用方展示名称',
  `key_hash` char(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '明文 Key 的 SHA-256 摘要',
  `prefix` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '仅用于展示的形式：前缀段加末尾 4 位',
  `status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED 启用 / DISABLED 停用',
  `qps_limit` int NOT NULL DEFAULT '10' COMMENT '该 Key 的令牌桶速率',
  `app_scope` json DEFAULT NULL COMMENT '授权的应用 id 列表，为 null 时授权全部应用',
  `last_used_at` datetime DEFAULT NULL COMMENT '最近一次认证成功的时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key_id` (`key_id`),
  UNIQUE KEY `uk_key_hash` (`key_hash`),
  KEY `idx_status` (`status`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='开放接口的 API Key：摘要存储、授权范围与配额';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_app` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `app_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识',
  `name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '展示名称',
  `description` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '自由文本描述',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_id` (`app_id`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库应用';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_app_version` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `app_version_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `app_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属应用',
  `version` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '展示版本号，依次为 V1.0、V2.0 ……',
  `status` varchar(24) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'DRAFT' COMMENT '版本状态：DRAFT/TESTING/GATING/GATE_PASSED/GATE_LOG_ONLY/GATE_BLOCKED/RELEASED/SUPERSEDED',
  `config` json NOT NULL COMMENT '完整的检索与提示词配置快照',
  `gate_dataset_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '门禁基线评测数据集，为 null 时跳过门禁',
  `gate_run_ids` json DEFAULT NULL COMMENT '双跑的两个运行 id，候选版本在前',
  `gate_verdict` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '门禁三态结论：PASS 通过 / BLOCKED 拦截 / LOG_ONLY 仅记录',
  `gate_reason` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'gate_verdict 背后归类后的原因',
  `gate_report` json DEFAULT NULL COMMENT '双方交集指标与容差阈值',
  `force_released` tinyint NOT NULL DEFAULT '0' COMMENT '管理员强制发布时置 1',
  `force_operator` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '强制发布的操作人，用于审计',
  `changelog` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '版本说明',
  `released_at` datetime DEFAULT NULL COMMENT '本版本最近一次成为发布版的时间',
  `visible_version_ids` json DEFAULT NULL COMMENT '按知识库冻结的 document_version_id 集合，为 null 时回落到实时别名',
  `index_snapshots` json DEFAULT NULL COMMENT '发布时冻结的物理索引集合，为 null 时回落到实时别名',
  `released_slot` varchar(64) COLLATE utf8mb4_general_ci GENERATED ALWAYS AS (if(((`status` = _utf8mb4'RELEASED') and (`deleted` = 0)),`app_id`,NULL)) VIRTUAL COMMENT '发布槽位虚拟列，仅发布版求值为 app_id，其余为 NULL',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_version_id` (`app_version_id`),
  UNIQUE KEY `uk_released_slot` (`released_slot`),
  KEY `idx_app_id` (`app_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应用版本：配置快照与发布状态机';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_auth_session` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `session_key` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Sa-Token 的存储键，如 satoken:login:token:xxx',
  `session_value` mediumtext COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Sa-Token 的存储值，会话对象序列化后为 JSON',
  `expires_at` datetime DEFAULT NULL COMMENT '绝对过期时刻；NULL 表示永不过期，对应 Sa-Token 的 NEVER_EXPIRE',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_key` (`session_key`),
  KEY `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='控制台会话存储（Sa-Token KV），cache.provider=local 时启用';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_auth_token` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `token_hash` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '不透明 bearer 令牌的 SHA-256 摘要，也是唯一的存储形式',
  `username` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '会话所属的控制台账号',
  `expires_at` datetime NOT NULL COMMENT '绝对过期时刻，等于签发时间加上配置的 TTL',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token_hash` (`token_hash`),
  KEY `idx_username` (`username`),
  KEY `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='控制台会话令牌，可跨进程重启存活';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `chunk_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '业务标识，同时也是检索引擎侧的主键',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属知识库',
  `doc_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属文档',
  `document_version_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属文档版本，检索隔离必填',
  `content` mediumtext COLLATE utf8mb4_general_ci COMMENT '分片正文，MySQL 是事实源',
  `chunk_text_hash` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '归一化文本的 SHA-256',
  `parent_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '父子切分时的父分片 id',
  `parent_start_offset` int DEFAULT NULL COMMENT '本子分片在父分片正文中的起始偏移，未知时为 null',
  `parent_end_offset` int DEFAULT NULL COMMENT '本子分片在父分片正文中的结束偏移（不含），未知时为 null',
  `seq` int NOT NULL DEFAULT '0' COMMENT '在本版本内的排序序号',
  `chunk_type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'TEXT' COMMENT '分片类型：TEXT/IMAGE/CHAT_LOG',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '置 0 时该分片不参与检索',
  `embedding_status` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '向量化状态：PENDING/DONE/FAILED/SKIPPED',
  `metadata` json DEFAULT NULL COMMENT '不可过滤的元数据，仅存 MySQL',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chunk_id` (`chunk_id`),
  KEY `idx_kb_id` (`kb_id`),
  KEY `idx_doc_id` (`doc_id`),
  KEY `idx_document_version_id` (`document_version_id`),
  KEY `idx_chunk_text_hash` (`chunk_text_hash`),
  KEY `idx_parent_id` (`parent_id`),
  KEY `idx_embedding_status` (`embedding_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='分片事实源';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_chunk_index_sync` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `chunk_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '分片业务 id',
  `physical_index_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '物理索引名或集合名',
  `engine` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '引擎类型：es/qdrant',
  `status` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '同步状态：PENDING/SYNCED/FAILED',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '已重试次数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chunk_index` (`chunk_id`,`physical_index_name`),
  KEY `idx_status` (`status`),
  KEY `idx_index_status` (`physical_index_name`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='分片在各物理索引上的同步状态';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_doc_acl` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `document_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '文档业务标识，引用 t_kb_document.doc_id',
  `role_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '被授权角色业务标识，引用 t_kb_role.role_id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  KEY `idx_doc` (`document_id`),
  KEY `idx_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='受限文档与授权角色的绑定关系，visibility=RESTRICTED 时参与判定';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `doc_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属知识库',
  `file_name` varchar(512) COLLATE utf8mb4_general_ci NOT NULL COMMENT '上传时的原始文件名',
  `file_ext` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '小写扩展名，不含点号',
  `file_size` bigint NOT NULL DEFAULT '0' COMMENT '原始文件字节数',
  `current_version_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生效版本指针',
  `process_status` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '处理状态：UPLOADED/PARSING/PARSE_FAILED/PARSED/INDEXING/INDEXED/INDEX_FAILED',
  `publish_status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PUBLISHED' COMMENT '发布状态：DRAFT 草稿 / PENDING_REVIEW 待审核 / PUBLISHED 已发布 / REJECTED 已驳回',
  `review_note` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近一次的驳回理由，审核通过时清空',
  `effective_at` datetime DEFAULT NULL COMMENT '生效时间，从该时刻起可检索；为 null 表示不设下界',
  `expires_at` datetime DEFAULT NULL COMMENT '失效时间，在该时刻之前可检索；为 null 表示不设上界',
  `trashed` tinyint NOT NULL DEFAULT '0' COMMENT '文档在回收站中时置 1',
  `trashed_at` datetime DEFAULT NULL COMMENT '进入回收站的时间，驱动彻底清除任务',
  `config_stale` tinyint NOT NULL DEFAULT '0' COMMENT '生效版本使用了旧配置时置 1',
  `fail_reason` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '归类后的失败原因',
  `source_key` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '逻辑来源标识，聊天导入为 chat:{session_id}，普通上传为 null',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  `visibility` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'INHERIT' COMMENT '可见性：INHERIT 继承库可见性 / RESTRICTED 仅授权角色可读内容',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_id` (`doc_id`),
  KEY `idx_kb_id` (`kb_id`),
  KEY `idx_process_status` (`process_status`),
  KEY `idx_kb_status` (`kb_id`,`process_status`),
  KEY `idx_config_stale` (`kb_id`,`config_stale`),
  KEY `idx_kb_source_key` (`kb_id`,`source_key`),
  KEY `idx_kb_publish` (`kb_id`,`publish_status`),
  KEY `idx_kb_trashed` (`kb_id`,`trashed`),
  KEY `idx_trashed_at` (`trashed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='文档主记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_document_version` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `version_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `doc_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属文档',
  `version` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '版本号 major.minor，从 1.0 开始',
  `minio_object` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '原始文件的对象存储 key',
  `parsed_object` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '解析产物 markdown 的对象存储 key',
  `content_hash` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '原始字节流的 SHA-256',
  `parse_fingerprint` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '解析阶段入参的指纹',
  `chunk_fingerprint` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '切分阶段入参的指纹',
  `embedding_version` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '本次构建使用的向量模型',
  `status` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '版本状态：BUILDING/BUILD_FAILED/READY/ACTIVE/ARCHIVED',
  `active_flag` tinyint DEFAULT NULL COMMENT '唯一生效版本置 1，其余为 NULL',
  `changelog` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '版本列表展示的变更说明',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_version_id` (`version_id`),
  UNIQUE KEY `uk_doc_version` (`doc_id`,`version`),
  UNIQUE KEY `uk_doc_active` (`doc_id`,`active_flag`),
  KEY `idx_doc_id` (`doc_id`),
  KEY `idx_status` (`status`),
  KEY `idx_content_hash` (`content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='文档版本';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_eval_case` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `case_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `dataset_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属数据集',
  `query` text COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户查询',
  `messages` json DEFAULT NULL COMMENT '可选的对话历史数组',
  `expected_answer` text COLLATE utf8mb4_general_ci COMMENT '参考答案，仅 LLM-as-judge 会用到',
  `anchor_type` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '证据锚点类型：SPAN 片段级 / DOCUMENT 文档级',
  `evidences` json NOT NULL COMMENT '证据数组，元素为 {doc_id, span, annotated_version_id}',
  `status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '用例状态：ACTIVE 生效 / EVIDENCE_STALE 证据失效 / DEPRECATED 已废弃',
  `source` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'MANUAL' COMMENT '用例来源：MANUAL 手工 / DEBUG_PAGE 调试页转化 / IMPORTED 导入',
  `note` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '运营人员的自由文本备注',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  `expected_refusal` tinyint NOT NULL DEFAULT '0' COMMENT '正确答案是否应因资料不足而拒答',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_case_id` (`case_id`),
  KEY `idx_dataset_id` (`dataset_id`),
  KEY `idx_status` (`status`),
  KEY `idx_dataset_status` (`dataset_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='评测用例：查询、可选对话历史与证据锚点';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_eval_dataset` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `dataset_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属知识库',
  `name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '展示名称',
  `description` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '自由文本描述',
  `dataset_revision` int NOT NULL DEFAULT '0' COMMENT '用例集修订号，任一用例变更即加一',
  `case_count` int NOT NULL DEFAULT '0' COMMENT '未废弃用例的冗余计数，派生字段',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dataset_id` (`dataset_id`),
  KEY `idx_kb_id` (`kb_id`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='评测数据集';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_eval_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `result_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `run_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属评测运行',
  `case_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '被评判的用例',
  `hit` tinyint NOT NULL DEFAULT '0' COMMENT '用例在 Top K 内被召回时置 1',
  `hit_rank` int DEFAULT NULL COMMENT '首个命中的名次（从 1 开始），未命中为 null',
  `overlap_ratios` json DEFAULT NULL COMMENT '每条证据的最佳聚合覆盖率',
  `recalled_chunk_ids` json DEFAULT NULL COMMENT '本用例 Top K 返回的分片 id 列表',
  `degraded` json DEFAULT NULL COMMENT '评判过程中观察到的降级标记',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '本用例经历的自动重试次数',
  `judge_score` int DEFAULT NULL COMMENT 'LLM-as-judge 打分，未评判时为 null',
  `judge_reason` varchar(2048) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'LLM-as-judge 给出的自由文本理由',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  `evidence_hit_count` int NOT NULL DEFAULT '0' COMMENT 'Top K 内被覆盖的证据数，门禁交集重算的输入',
  `evidence_total_count` int NOT NULL DEFAULT '0' COMMENT '该用例声明的证据总数，门禁交集重算的输入',
  `generated_answer` mediumtext COLLATE utf8mb4_general_ci COMMENT '复用生产问答链路生成的最终答案',
  `answer_judge_requested` tinyint NOT NULL DEFAULT '0' COMMENT '本 case 是否需要生成并评判最终答案',
  `generation_latency_ms` int DEFAULT NULL COMMENT '最终答案生成耗时，毫秒',
  `answer_score` int DEFAULT NULL COMMENT '最终答案五维评分均值，1-5',
  `answer_correctness` int DEFAULT NULL COMMENT '最终答案正确性评分，1-5',
  `answer_faithfulness` int DEFAULT NULL COMMENT '最终答案忠实度评分，1-5',
  `answer_completeness` int DEFAULT NULL COMMENT '最终答案完整性评分，1-5',
  `citation_correctness` int DEFAULT NULL COMMENT '引用正确性评分，1-5',
  `citation_completeness` int DEFAULT NULL COMMENT '引用完整性评分，1-5',
  `refusal_correct` tinyint DEFAULT NULL COMMENT '是否作出正确的回答或拒答决策',
  `answer_judge_reason` varchar(2048) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最终答案评分理由或评判失败说明',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_result_id` (`result_id`),
  KEY `idx_run_id` (`run_id`),
  KEY `idx_case_id` (`case_id`),
  KEY `idx_run_hit` (`run_id`,`hit`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='评测运行下逐用例的评判结果';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_eval_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `run_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `dataset_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '本次运行所测的数据集',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属知识库，为列表查询做的反范式冗余',
  `dataset_revision` int NOT NULL COMMENT '创建运行时快照下来的数据集修订号',
  `corpus_fingerprint` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '语料指纹，标识本次运行所面对的生效语料状态',
  `retrieval_config` json NOT NULL COMMENT '本次运行使用的标签、模式与检索参数',
  `judge_model` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'LLM-as-judge 模型，未开启评判时为 null',
  `judge_prompt_version` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '评判提示词版本，未开启评判时为 null',
  `status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PENDING' COMMENT '运行状态：PENDING/RUNNING/SUCCESS/FAILED',
  `metrics` json DEFAULT NULL COMMENT '分组指标（含 95% 置信区间），运行结束前为 null',
  `case_total` int NOT NULL DEFAULT '0' COMMENT '运行开始时数据集内的用例总数',
  `case_effective` int NOT NULL DEFAULT '0' COMMENT '实际参与评判的用例数',
  `case_stale` int NOT NULL DEFAULT '0' COMMENT '因证据失效被跳过的用例数',
  `case_degraded` int NOT NULL DEFAULT '0' COMMENT '自动重试后仍处于降级状态的用例数',
  `fail_reason` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '仅当 status 为 FAILED 时写入的失败原因',
  `started_at` datetime DEFAULT NULL COMMENT '运行开始时间',
  `finished_at` datetime DEFAULT NULL COMMENT '运行结束时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  `answer_eval_config` json DEFAULT NULL COMMENT '答案评测使用的应用版本与完整配置快照',
  `answer_judge_model` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最终答案评判模型，未开启答案评测时为 null',
  `answer_judge_prompt_version` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最终答案评分提示词版本',
  `answer_metrics` json DEFAULT NULL COMMENT '最终答案质量聚合指标',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_id` (`run_id`),
  KEY `idx_dataset_id` (`dataset_id`),
  KEY `idx_status` (`status`),
  KEY `idx_dataset_created` (`dataset_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='评测运行：对单套检索配置的一次测量';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_ext_source` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `source_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '控制台对外暴露的业务标识',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '拉取到的对象所落入的知识库',
  `source_type` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '连接器类型路由键，本里程碑固定为 s3',
  `name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '面向运营人员的展示名称，知识库内唯一',
  `endpoint` varchar(512) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对象存储的服务端点',
  `region` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '对象存储的可选 region 提示',
  `bucket` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '扫描所列举的桶名',
  `prefix` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '可选的 key 前缀，用于收窄扫描范围',
  `access_key` varchar(256) COLLATE utf8mb4_general_ci NOT NULL COMMENT '桶凭据的 access key',
  `secret_key` varchar(512) COLLATE utf8mb4_general_ci NOT NULL COMMENT '桶凭据的 secret key，接口永不回传',
  `sync_enabled` tinyint NOT NULL DEFAULT '1' COMMENT '置 1 时该数据源纳入定时同步任务',
  `last_sync_at` datetime DEFAULT NULL COMMENT '最近一次同步尝试的执行时间',
  `last_sync_status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近一次同步结果：SUCCESS 全部成功 / PARTIAL 部分成功 / FAILED 失败',
  `last_error` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近一次同步失败或仅部分成功的原因，全部成功时为 null',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_id` (`source_id`),
  UNIQUE KEY `uk_kb_name` (`kb_id`,`name`),
  KEY `idx_kb` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='已注册的外部对象存储数据源，用于喂养文档';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_ext_source_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `source_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属外部数据源的业务标识',
  `object_key` varchar(1024) COLLATE utf8mb4_general_ci NOT NULL COMMENT '桶内的对象 key',
  `object_key_hash` char(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对象 key 的 SHA-256，去重与查找键',
  `etag` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近一次入库的对象正文 etag，用于判断内容是否未变',
  `doc_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '该对象所喂养的文档，首次成功前为 null',
  `last_status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近一次同步结果：SUCCESS/UNCHANGED/SKIPPED/FAILED',
  `last_error` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近一次同步失败或被跳过的原因，成功时为 null',
  `last_sync_at` datetime DEFAULT NULL COMMENT '该对象最近一次被同步任务访问的时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_object` (`source_id`,`object_key_hash`),
  KEY `idx_source` (`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部数据源逐对象的同步结果';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_ik_dict` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `word` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '下发给 ik 分词器的词条',
  `dict_type` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '词典类型：EXT 扩展词 / STOP 停用词',
  `status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED，只有启用的词条会被下发',
  `remark` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '添加该词条的原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word` (`word`),
  KEY `idx_type_status` (`dict_type`,`status`),
  KEY `idx_type_word` (`dict_type`,`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ik 分词器词典';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_image_asset` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `image_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '全局唯一的业务标识',
  `source_image_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '[[IMAGE:id]] 占位符中使用的标识',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属知识库',
  `doc_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属文档',
  `document_version_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属文档版本',
  `page_no` int DEFAULT NULL COMMENT '页码，从 1 开始；无分页概念的格式为 null',
  `kind` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '图片来源：EMBEDDED 内嵌 / PAGE_RENDER 整页渲染 / STANDALONE 独立文件',
  `object_key` varchar(512) COLLATE utf8mb4_general_ci NOT NULL COMMENT '二进制内容的对象存储 key',
  `media_type` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '二进制内容的 MIME 类型',
  `bytes` bigint NOT NULL DEFAULT '0' COMMENT '二进制内容的字节数',
  `text_proxy` mediumtext COLLATE utf8mb4_general_ci COMMENT '视觉模型产出的图片描述与文字转录',
  `status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '处理状态：PENDING/DONE/SKIPPED/FAILED',
  `fail_reason` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'status 为 FAILED 时归类后的失败原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_image_id` (`image_id`),
  UNIQUE KEY `uk_version_source` (`document_version_id`,`source_image_id`),
  KEY `idx_kb_id` (`kb_id`),
  KEY `idx_doc_id` (`doc_id`),
  KEY `idx_document_version_id` (`document_version_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='图片资产及其文本代理';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_index_registry` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属知识库',
  `engine` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '引擎类型：es/qdrant',
  `physical_index_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '三段式物理索引名',
  `alias_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '别名，所有读写都经由它',
  `is_current` tinyint NOT NULL DEFAULT '0' COMMENT '别名当前指向本行时置 1',
  `embedding_provider` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '向量模型提供方',
  `embedding_model` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '向量模型名称',
  `embedding_version` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '物理索引名中的向量模型段',
  `snapshot_version` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '物理索引名中的快照段，M1 固定为 v1',
  `schema_version` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '引擎侧字段集版本',
  `status` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '索引状态：BUILDING/ACTIVE/PENDING_CLEANUP',
  `task_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建本索引的任务 id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_physical_index_name` (`physical_index_name`),
  KEY `idx_kb_id` (`kb_id`),
  KEY `idx_alias` (`alias_name`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物理索引与别名注册表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_knowledge_base` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识',
  `name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '展示名称',
  `description` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '自由文本描述',
  `index_config` json DEFAULT NULL COMMENT '切分与向量化参数',
  `current_config_fingerprint` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '配置指纹，驱动文档的 config_stale 标记',
  `retrieval_config` json DEFAULT NULL COMMENT '知识库级别的检索默认参数，可被请求参数覆盖',
  `review_required` tinyint NOT NULL DEFAULT '0' COMMENT '置 1 时新文档的初始状态为 DRAFT 而不是 PUBLISHED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_id` (`kb_id`),
  KEY `idx_name` (`name`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_login_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `username` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '提交的用户名，账号不存在时也照样记录',
  `ip` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '来源地址',
  `success` tinyint NOT NULL DEFAULT '0' COMMENT '本次登录是否成功，1 成功',
  `reason` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '结果原因：SUCCESS/USER_NOT_FOUND/BAD_PASSWORD/ACCOUNT_LOCKED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_username_created` (`username`,`created_at`),
  KEY `idx_ip_created` (`ip`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='控制台登录审计，同时是暴力破解计数的数据来源';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_memory_app_key` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `key_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识（mak_*），不是密钥本身',
  `library_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '绑定的记忆库：一把 Key 只能读写这一个库（应用级隔离）',
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '用途备注，如「客服智能体」',
  `key_hash` char(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '明文 Key 的 SHA-256 十六进制摘要，认证按它查',
  `key_prefix` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '展示形态（前缀+末四位），列表页可见',
  `status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / DISABLED，禁用即刻生效',
  `qps_limit` int NOT NULL DEFAULT '10' COMMENT '每秒请求上限（令牌桶）',
  `last_used_at` datetime DEFAULT NULL COMMENT '最近一次成功认证时间，异步更新',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key_id` (`key_id`),
  UNIQUE KEY `uk_key_hash` (`key_hash`),
  KEY `idx_library_id` (`library_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='记忆库开放 API 的 Memory Key（kb-mk-*）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_memory_fragment_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `rule_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识（mfr_*）',
  `library_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属记忆库',
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '规则名称，库内唯一由服务层校验',
  `instruction_type` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'DEFAULT' COMMENT '指令类型：DEFAULT 内置抽取指令 / CUSTOM 自定义',
  `instruction` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '自定义抽取指令，CUSTOM 时必填',
  `auto_update` tinyint NOT NULL DEFAULT '1' COMMENT '1 时抽取会合并更新同实体旧记忆而不是只追加',
  `expire_days` int DEFAULT NULL COMMENT '记忆有效期天数（7/30/180），NULL 永不过期',
  `extract_version` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PRO' COMMENT '抽取版本：PRO 带旧记忆合并去重 / LITE 单次直抽',
  `builtin` tinyint NOT NULL DEFAULT '0' COMMENT '1 为建库预置的「默认项目」规则，可编辑不可删除',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_id` (`rule_id`),
  KEY `idx_library_id` (`library_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='记忆片段规则，每库上限 50 条由服务层守';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_memory_library` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `library_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识（ml_*）',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识',
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '记忆库名称，服务层校验同名（逻辑删除下唯一索引会挡住重建）',
  `description` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '描述，可能用于指导智能体调用的语句',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_library_id` (`library_id`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='记忆库';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_memory_node` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `node_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识（mn_*）',
  `library_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属记忆库',
  `rule_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '产生该记忆的片段规则',
  `user_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '记忆实体 ID，调用方自定义，实体间互相隔离',
  `content` text COLLATE utf8mb4_general_ci NOT NULL COMMENT '记忆内容',
  `source` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '来源：EXTRACTED 由 LLM 抽取 / CUSTOM 调用方直写',
  `meta_data` text COLLATE utf8mb4_general_ci COMMENT '调用方自定义元数据 JSON，原样存储原样返回',
  `expire_at` datetime DEFAULT NULL COMMENT '过期时间（写入时按规则 expire_days 计算），NULL 永不过期；过期记忆不再被检索',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_id` (`node_id`),
  KEY `idx_library_user` (`library_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='记忆节点（记忆片段），MySQL 为事实源，ES 存检索副本';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_memory_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `library_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属记忆库',
  `rule_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属画像规则',
  `user_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '记忆实体 ID',
  `attributes` text COLLATE utf8mb4_general_ci NOT NULL COMMENT '已提取的画像属性 JSON 对象：{字段名:值}，未提取字段不落行、读取时回落规则初始值',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_user` (`rule_id`,`user_id`),
  KEY `idx_library_user` (`library_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户画像（按实体×画像规则一行）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_memory_profile_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `rule_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识（mpr_*）',
  `library_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属记忆库',
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '画像规则名称，库内唯一由服务层校验',
  `extract_version` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PRO' COMMENT '抽取版本：PRO / LITE',
  `fields` text COLLATE utf8mb4_general_ci NOT NULL COMMENT '画像字段定义 JSON 数组：[{name,description,initial_value}]，上限 50 个',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_id` (`rule_id`),
  KEY `idx_library_id` (`library_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户画像规则，每库上限 50 条由服务层守';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_model_price` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `provider` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '供应商标识',
  `capability` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '能力：CHAT/EMBEDDING/RERANK/VISION/MULTIMODAL_EMBEDDING',
  `model` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '模型标识',
  `currency` char(3) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'ISO 4217 币种，例如 CNY/USD',
  `input_price_micros` bigint NOT NULL DEFAULT '0' COMMENT '每百万输入 Token 价格，单位为该币种的 10^-6',
  `output_price_micros` bigint NOT NULL DEFAULT '0' COMMENT '每百万输出 Token 价格，单位为该币种的 10^-6',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '1 启用，0 停用；停用后新调用记为未定价',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_capability_model` (`provider`,`capability`,`model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='模型价格配置，新调用在台账中快照当时价格';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_model_usage` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `usage_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '调用业务标识',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '承担配额与成本的租户',
  `request_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '请求关联标识，后台任务可为空',
  `source` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '来源：CONSOLE/KNOWLEDGE_API/MEMORY_API/SCHEDULED/INTERNAL',
  `source_id` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '用户、Key 或任务的安全业务标识，不存凭据',
  `provider` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '供应商标识',
  `capability` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '模型能力',
  `model` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '模型标识',
  `status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'RESERVED/SUCCEEDED/FAILED',
  `reserved_tokens` bigint NOT NULL COMMENT '调用前按输入上界和输出预算预占的 Token',
  `input_tokens` bigint NOT NULL DEFAULT '0' COMMENT '供应商返回的输入 Token，未知时为估算值',
  `output_tokens` bigint NOT NULL DEFAULT '0' COMMENT '供应商返回的输出 Token，未知时为 0',
  `total_tokens` bigint NOT NULL DEFAULT '0' COMMENT '本次配额实际结算 Token',
  `estimated` tinyint NOT NULL DEFAULT '0' COMMENT '1 表示供应商未返回用量，按预占值估算结算',
  `priced` tinyint NOT NULL DEFAULT '0' COMMENT '1 表示命中价格配置并完成成本估算',
  `currency` char(3) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '本次价格快照的 ISO 4217 币种',
  `input_price_micros` bigint DEFAULT NULL COMMENT '每百万输入 Token 价格快照',
  `output_price_micros` bigint DEFAULT NULL COMMENT '每百万输出 Token 价格快照',
  `cost_micros` bigint NOT NULL DEFAULT '0' COMMENT '估算成本，单位为币种的 10^-6',
  `error_type` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '失败类型，不记录异常正文或请求内容',
  `completed_at` datetime DEFAULT NULL COMMENT '调用结算或失败时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，固定为 0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_usage_id` (`usage_id`),
  KEY `idx_tenant_month` (`tenant_id`,`created_at`),
  KEY `idx_request_id` (`request_id`),
  KEY `idx_model` (`provider`,`capability`,`model`,`created_at`),
  KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='模型调用 Token 与成本明细台账';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_model_usage_monthly` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '租户业务标识',
  `usage_month` char(7) COLLATE utf8mb4_general_ci NOT NULL COMMENT '计费月份，格式 YYYY-MM，按 UTC+8 归属',
  `used_tokens` bigint NOT NULL DEFAULT '0' COMMENT '已经结算的 Token',
  `reserved_tokens` bigint NOT NULL DEFAULT '0' COMMENT '在途调用预占的 Token',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，固定为 0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_month` (`tenant_id`,`usage_month`),
  KEY `idx_usage_month` (`usage_month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户模型 Token 月度原子计数器';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_operation_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `audit_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '审计记录业务标识',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作者所属租户业务标识',
  `user_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作者用户业务标识',
  `username` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作者登录名，冗余存储使记录在账号删除后仍可读',
  `module` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作所属模块，如 KB / DOCUMENT / USER',
  `action` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '动作标识，如 CREATE / UPDATE / DELETE / RELEASE',
  `target_type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作对象类型，如 KNOWLEDGE_BASE / DOCUMENT / ROLE',
  `target_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作对象业务标识，批量操作可为空',
  `detail` json DEFAULT NULL COMMENT '业务 id 与摘要字段，绝不存请求体原文（口令与文档内容都从写端点过）',
  `client_ip` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户端 IP',
  `request_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '请求链路标识，与应用日志对账',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_audit_id` (`audit_id`),
  KEY `idx_tenant_created` (`tenant_id`,`created_at`),
  KEY `idx_username` (`username`),
  KEY `idx_target` (`target_type`,`target_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='管理台写操作审计，回答"谁在什么时候改了什么"';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `code` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '权限码，形如 module:action，与代码里的 PermissionCodes 常量一一对应',
  `name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '权限中文名，管理台勾选面板直接展示',
  `module` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '所属模块分组键，管理台按此聚合展示',
  `module_name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '模块中文名',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '同模块内的展示顺序',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_module` (`module`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='权限码目录，管理台角色配置面板的数据源';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_retrieval_feedback` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `feedback_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '控制台对外暴露的业务标识',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '本次查询所命中的知识库',
  `query` text COLLATE utf8mb4_general_ci NOT NULL COMMENT '调试页执行的查询原文，转化用例时按原文重放',
  `chunk_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '本次评价所针对的分片',
  `doc_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '所属文档，由服务端解析；分片已被删除时为 null',
  `verdict` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '评价结论：GOOD 好评 / BAD 差评',
  `status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'NEW' COMMENT '处理状态：NEW 待处理 / CONVERTED 已转用例 / DISMISSED 已忽略',
  `converted_case_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '由本行转化而来的评测用例 id，未转化时为 null',
  `note` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '运营人员的自由文本备注',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  `channel` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'CONSOLE' COMMENT '反馈渠道：CONSOLE 控制台 / OPEN_API 开放接口',
  `end_user_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '调用方自报的终端用户标识，平台不做真实性背书',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feedback_id` (`feedback_id`),
  KEY `idx_kb_id` (`kb_id`),
  KEY `idx_status` (`status`),
  KEY `idx_kb_status` (`kb_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='对调试结果的好评/差评，可转化为评测用例';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `role_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '控制台对外暴露的业务标识',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识',
  `code` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色码，内置角色为固定值，自定义角色由运营人员填写',
  `name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色中文名',
  `description` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '角色用途说明',
  `builtin` tinyint NOT NULL DEFAULT '0' COMMENT '置 1 为内置角色：不可删除、角色码不可改',
  `kb_scope_all` tinyint NOT NULL DEFAULT '0' COMMENT '置 1 表示该角色可见全部知识库，此时 t_kb_role_kb 的明细被忽略',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_id` (`role_id`),
  UNIQUE KEY `uk_tenant_code` (`tenant_id`,`code`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色，权限码与知识库数据范围的挂载点';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_role_kb` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `role_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色业务标识',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '该角色可见的知识库业务标识',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  KEY `idx_role` (`role_id`),
  KEY `idx_kb` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色的知识库数据范围明细，kb_scope_all=1 时不参与判定';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_role_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `role_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色业务标识',
  `permission_code` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '权限码，引用 t_kb_permission.code',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  KEY `idx_role` (`role_id`),
  KEY `idx_permission` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色与权限码的绑定关系';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_search_insight` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `insight_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '控制台对外暴露的业务标识',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '本次检索所命中的知识库',
  `source` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '调用入口：CONSOLE 控制台 / OPEN_API 开放接口',
  `query_digest` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '脱敏并截断后的查询文本，绝不存原文',
  `query_hash` char(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '归一化查询的 SHA-256，未命中报表的分组键',
  `result_count` int NOT NULL DEFAULT '0' COMMENT '本次调用返回的结果条数',
  `top_score` double DEFAULT NULL COMMENT '首条结果的得分，零命中时为 null',
  `zero_hit` tinyint NOT NULL DEFAULT '0' COMMENT '由 result_count = 0 派生，报表实际扫描的就是这一列',
  `degraded` json DEFAULT NULL COMMENT '本次调用的降级标记',
  `request_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '链路追踪 id，把本行与日志、审计关联起来',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  `app_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '开放接口调用方应用标识，控制台调试检索为空',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_insight_id` (`insight_id`),
  KEY `idx_kb_id` (`kb_id`),
  KEY `idx_query_hash` (`query_hash`),
  KEY `idx_kb_zero_created` (`kb_id`,`zero_hit`,`created_at`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_request` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='线上检索逐次留痕，超过保留期后删除';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_source_mapping` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `mapping_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '模板名称，内置与自建模板共用同一个命名空间且唯一',
  `source_type` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '本模板所读取的导出文件类型：CSV/XLSX/TXT/HTML',
  `profile_yaml` mediumtext COLLATE utf8mb4_general_ci NOT NULL COMMENT '完整 yaml 正文，每次解析调用都原样转发给解析器',
  `is_builtin` tinyint NOT NULL DEFAULT '0' COMMENT '1 内置模板，0 运营人员自建',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mapping_id` (`mapping_id`),
  UNIQUE KEY `uk_name` (`name`),
  KEY `idx_source_type` (`source_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='聊天导入映射模板，转发给解析器使用';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_sso_state` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `state_hash` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '一次性 state 的 SHA-256 摘要，也是唯一的存储形式',
  `payload` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '回调时原样取回的流程上下文，如 sso:OIDC',
  `expires_at` datetime NOT NULL COMMENT '绝对过期时刻，签发时间加上流程允许的时长',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_state_hash` (`state_hash`),
  KEY `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='单点登录流程的一次性 state';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_system_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `config_key` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '配置项键名',
  `config_value` text COLLATE utf8mb4_general_ci COMMENT '配置项值，结构化配置存 JSON',
  `description` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '配置项说明',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='全局系统配置';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `task_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '对外暴露的业务标识',
  `task_type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务类型：PARSE/INDEX/REBUILD/CLEANUP/GRAPH_EXTRACT',
  `biz_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务操作的业务实体 id',
  `status` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务状态：PENDING/RUNNING/SUCCESS/FAILED',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '已重试次数',
  `fail_reason` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '归类后的失败原因',
  `progress` int NOT NULL DEFAULT '0' COMMENT '完成百分比',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  `skipped_count` int DEFAULT NULL COMMENT '被输出校验跳过的分片数，仅 GRAPH_EXTRACT 任务使用',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`),
  KEY `idx_biz_id` (`biz_id`),
  KEY `idx_status` (`status`),
  KEY `idx_type_status` (`task_type`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='异步任务';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_tenant` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '控制台对外暴露的业务标识，tnt_ 前缀',
  `code` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '租户码，建后不可改，索引命名的租户段由它派生',
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '租户中文名',
  `status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'ENABLED' COMMENT '租户状态：ENABLED 启用 / DISABLED 停用（停用即拒绝该租户全部账号登录）',
  `builtin` tinyint NOT NULL DEFAULT '0' COMMENT '置 1 为内置租户：不可停用',
  `monthly_token_quota` bigint NOT NULL DEFAULT '0' COMMENT '每月模型 Token 配额，0 表示不限制',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_id` (`tenant_id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户，根聚合表行级隔离的挂载点';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `user_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户业务标识，引用 t_kb_admin_user.user_id',
  `role_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色业务标识，引用 t_kb_role.role_id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  `granted_by` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'MANUAL' COMMENT '授予来源：MANUAL 管理员手工授予 / LDAP_SYNC 目录组同步授予',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户与角色的绑定关系';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_web_credential` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `credential_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '控制台对外暴露的业务标识',
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识',
  `host` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '凭据生效的精确 host（带非默认端口则含端口），全局唯一',
  `auth_type` varchar(16) COLLATE utf8mb4_general_ci NOT NULL COMMENT '认证类型：BASIC 用户名密码 / HEADER 任意请求头（覆盖 Bearer 与 Cookie）',
  `username` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'BASIC 的用户名，HEADER 类型为 null',
  `secret` varchar(512) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'BASIC 存密码，HEADER 存头的完整值，接口永不回传',
  `header_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'HEADER 的头名（如 Authorization / Cookie），BASIC 类型为 null',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '置 0 时抓取不再使用该凭据，行保留',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credential_id` (`credential_id`),
  UNIQUE KEY `uk_tenant_host` (`tenant_id`,`host`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='网页导入的站点级认证凭据';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kb_web_source` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `source_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '控制台对外暴露的业务标识',
  `kb_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '抓取到的页面所落入的知识库',
  `url` varchar(2048) COLLATE utf8mb4_general_ci NOT NULL COMMENT '已注册的页面地址，http 或 https',
  `url_hash` char(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'url 的 SHA-256，去重与查找键',
  `doc_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '抓取内容所喂养的文档，首次成功前为 null',
  `file_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '派生出的稳定文件名，上传链路看到的就是它',
  `sync_enabled` tinyint NOT NULL DEFAULT '1' COMMENT '置 1 时该源纳入定时同步任务',
  `render_js` tinyint NOT NULL DEFAULT '0' COMMENT '置 1 时该源抓取走无头浏览器 JS 渲染，默认 0 静态抓取',
  `last_content_hash` char(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近一次抓取正文的 SHA-256，用于判断内容是否未变',
  `last_fetch_at` datetime DEFAULT NULL COMMENT '最近一次同步尝试的执行时间',
  `last_fetch_status` varchar(16) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近一次同步结果：SUCCESS/UNCHANGED/SKIPPED/FAILED',
  `last_error` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近一次同步失败或被跳过的原因，成功时为 null',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标记，1 表示已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_id` (`source_id`),
  UNIQUE KEY `uk_kb_url` (`kb_id`,`url_hash`),
  KEY `idx_kb_id` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='已注册的网页源，经 URL 导入喂养文档';
/*!40101 SET character_set_client = @saved_cs_client */;

-- ============================================================================
-- 二、初始化数据
--     默认租户 1 条 + 权限码目录 21 条（即"菜单数据"）
--     + 内置角色 5 个 + 角色权限绑定 53 条
-- ============================================================================

INSERT INTO `t_kb_tenant` (`id`, `tenant_id`, `code`, `name`, `status`, `builtin`, `monthly_token_quota`, `created_at`, `updated_at`, `lock_version`, `deleted`) VALUES (1,'tnt_default0000000','DEFAULT','默认租户','ENABLED',1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0);
INSERT INTO `t_kb_permission` (`id`, `code`, `name`, `module`, `module_name`, `sort_order`, `created_at`, `updated_at`, `lock_version`, `deleted`) VALUES (1,'kb:read','查看知识库与文档','KB','知识库',10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(2,'kb:write','管理知识库与索引配置','KB','知识库',20,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(3,'kb:delete','删除知识库','KB','知识库',30,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(4,'doc:write','管理文档、分片与数据源','KB','知识库',40,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(5,'doc:review','内容审核与回收站','KB','知识库',50,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(6,'search:debug','检索与问答调试','RETRIEVAL','检索',10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(7,'feedback:manage','处理检索反馈','RETRIEVAL','检索',20,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(8,'eval:read','查看评测集与评测结果','EVAL','评测',10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(9,'eval:write','维护评测集与人工标注','EVAL','评测',20,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(10,'eval:run','发起评测运行','EVAL','评测',30,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(11,'app:read','查看应用与版本','APP','应用',10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(12,'app:write','管理应用与版本配置','APP','应用',20,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(13,'app:release','提测、发布与回滚','APP','应用',30,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(14,'apikey:manage','管理对外 API Key','OPENAPI','开放接口',10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(15,'audit:read','查看调用审计与检索洞察','OPENAPI','开放接口',20,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(16,'system:config','系统设置、告警与词典','SYSTEM','系统',10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(17,'user:manage','用户管理','SYSTEM','系统',20,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(18,'role:manage','角色与权限管理','SYSTEM','系统',30,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(19,'tenant:manage','租户管理','SYSTEM','系统',40,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(20,'memory:read','查看记忆库与检索调试','MEMORY','记忆库',10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(21,'memory:write','管理记忆库、规则与 Memory Key','MEMORY','记忆库',20,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0);
INSERT INTO `t_kb_role` (`id`, `role_id`, `tenant_id`, `code`, `name`, `description`, `builtin`, `kb_scope_all`, `created_at`, `updated_at`, `lock_version`, `deleted`) VALUES (1,'role_superadmin000','tnt_default0000000','SUPER_ADMIN','超级管理员','拥有全部权限与全部知识库范围，不可删除',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(2,'role_kbadmin00000','tnt_default0000000','KB_ADMIN','知识库管理员','知识库、文档、评测与应用的完整管理权，不含用户与角色管理',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(3,'role_editor000000','tnt_default0000000','EDITOR','内容编辑','上传与维护文档，可做检索调试，不能删库也不能审核',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(4,'role_reviewer0000','tnt_default0000000','REVIEWER','内容审核员','审核文档上下架、处理检索反馈与回收站',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(5,'role_viewer000000','tnt_default0000000','VIEWER','只读用户','只读浏览与检索调试，域账号首次登录的默认角色',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0);
INSERT INTO `t_kb_role_permission` (`id`, `role_id`, `permission_code`, `created_at`, `updated_at`, `lock_version`, `deleted`) VALUES (1,'role_superadmin000','apikey:manage',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(2,'role_superadmin000','app:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(3,'role_superadmin000','app:release',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(4,'role_superadmin000','app:write',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(5,'role_superadmin000','audit:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(6,'role_superadmin000','doc:review',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(7,'role_superadmin000','doc:write',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(8,'role_superadmin000','eval:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(9,'role_superadmin000','eval:run',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(10,'role_superadmin000','eval:write',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(11,'role_superadmin000','feedback:manage',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(12,'role_superadmin000','kb:delete',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(13,'role_superadmin000','kb:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(14,'role_superadmin000','kb:write',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(15,'role_superadmin000','role:manage',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(16,'role_superadmin000','search:debug',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(17,'role_superadmin000','system:config',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(18,'role_superadmin000','user:manage',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(32,'role_kbadmin00000','apikey:manage',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(33,'role_kbadmin00000','app:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(34,'role_kbadmin00000','app:release',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(35,'role_kbadmin00000','app:write',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(36,'role_kbadmin00000','audit:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(37,'role_kbadmin00000','doc:review',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(38,'role_kbadmin00000','doc:write',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(39,'role_kbadmin00000','eval:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(40,'role_kbadmin00000','eval:run',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(41,'role_kbadmin00000','eval:write',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(42,'role_kbadmin00000','feedback:manage',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(43,'role_kbadmin00000','kb:delete',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(44,'role_kbadmin00000','kb:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(45,'role_kbadmin00000','kb:write',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(46,'role_kbadmin00000','search:debug',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(47,'role_kbadmin00000','system:config',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(63,'role_editor000000','kb:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(64,'role_editor000000','doc:write',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(65,'role_editor000000','search:debug',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(66,'role_editor000000','eval:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(67,'role_editor000000','app:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(68,'role_reviewer0000','kb:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(69,'role_reviewer0000','doc:review',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(70,'role_reviewer0000','search:debug',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(71,'role_reviewer0000','feedback:manage',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(72,'role_reviewer0000','eval:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(73,'role_viewer000000','kb:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(74,'role_viewer000000','search:debug',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(75,'role_viewer000000','eval:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(76,'role_viewer000000','app:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(77,'role_superadmin000','tenant:manage',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(78,'role_superadmin000','memory:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(79,'role_superadmin000','memory:write',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(80,'role_kbadmin00000','memory:read',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0),(81,'role_kbadmin00000','memory:write',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0);

SET FOREIGN_KEY_CHECKS = 1;
