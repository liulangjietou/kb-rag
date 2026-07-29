-- 里程碑 M4b 的结构增量：评测子系统。
-- 基线与 V1-V4 发布后不再修改，因此后续每一次变更都以独立的迁移脚本落地。
SET NAMES utf8mb4;

CREATE TABLE t_kb_eval_dataset
(
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    dataset_id       VARCHAR(64)  NOT NULL COMMENT '对外暴露的业务标识',
    kb_id            VARCHAR(64)  NOT NULL COMMENT '所属知识库',
    name             VARCHAR(128) NOT NULL COMMENT '展示名称',
    description      VARCHAR(1024)         DEFAULT NULL COMMENT '自由文本描述',
    -- 用例的新增、编辑、删除和状态变更都会让它加一；每次评测运行会把这个值快照下来，
    -- 于是当两次运行之间用例集发生了变动，对比接口就能拒绝把它们并排展示。
    dataset_revision INT          NOT NULL DEFAULT 0 COMMENT '用例集修订号，任一用例变更即加一',
    case_count       INT          NOT NULL DEFAULT 0 COMMENT '未废弃用例的冗余计数，派生字段',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dataset_id (dataset_id),
    KEY idx_kb_id (kb_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='评测数据集';

CREATE TABLE t_kb_eval_case
(
    id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    case_id          VARCHAR(64) NOT NULL COMMENT '对外暴露的业务标识',
    dataset_id       VARCHAR(64) NOT NULL COMMENT '所属数据集',
    query            TEXT        NOT NULL COMMENT '用户查询',
    -- 对话历史，对应需求文档 4.5 节「多轮用例」。为 NULL 时该用例是单轮的。
    messages         JSON                 DEFAULT NULL COMMENT '可选的对话历史数组',
    expected_answer  TEXT                 DEFAULT NULL COMMENT '参考答案，仅 LLM-as-judge 会用到',
    anchor_type      VARCHAR(16) NOT NULL COMMENT '证据锚点类型：SPAN 片段级 / DOCUMENT 文档级',
    -- 锚定到 doc_id 而不是 chunk id，这样切分策略变更后用例依然成立；
    -- anchor_type 为 DOCUMENT 时每个元素的 span 都是 null。参见 EvalEvidence。
    evidences        JSON        NOT NULL COMMENT '证据数组，元素为 {doc_id, span, annotated_version_id}',
    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '用例状态：ACTIVE 生效 / EVIDENCE_STALE 证据失效 / DEPRECATED 已废弃',
    source           VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT '用例来源：MANUAL 手工 / DEBUG_PAGE 调试页转化 / IMPORTED 导入',
    note             VARCHAR(1024)        DEFAULT NULL COMMENT '运营人员的自由文本备注',
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version     INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted          TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_id (case_id),
    KEY idx_dataset_id (dataset_id),
    KEY idx_status (status),
    KEY idx_dataset_status (dataset_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='评测用例：查询、可选对话历史与证据锚点';

CREATE TABLE t_kb_eval_run
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    run_id               VARCHAR(64)  NOT NULL COMMENT '对外暴露的业务标识',
    dataset_id           VARCHAR(64)  NOT NULL COMMENT '本次运行所测的数据集',
    kb_id                VARCHAR(64)  NOT NULL COMMENT '所属知识库，为列表查询做的反范式冗余',
    dataset_revision     INT          NOT NULL COMMENT '创建运行时快照下来的数据集修订号',
    -- 由生效版本集合、向量模型版本和词典版本一起哈希而成，对应需求文档 4.6 节。
    -- 只有 dataset_revision 与本指纹都一致，两次运行才可以对比。
    corpus_fingerprint   VARCHAR(64)  NOT NULL COMMENT '语料指纹，标识本次运行所面对的生效语料状态',
    -- 完整存下一份 EvalRetrievalConfig JSON，含它的标签与模式：报告页直接从这个文档还原出
    -- 该运行对应的那一列，不需要再从若干个独立列拼装回去。
    retrieval_config     JSON         NOT NULL COMMENT '本次运行使用的标签、模式与检索参数',
    judge_model          VARCHAR(128)          DEFAULT NULL COMMENT 'LLM-as-judge 模型，未开启评判时为 null',
    judge_prompt_version VARCHAR(32)           DEFAULT NULL COMMENT '评判提示词版本，未开启评判时为 null',
    status               VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '运行状态：PENDING/RUNNING/SUCCESS/FAILED',
    metrics              JSON                  DEFAULT NULL COMMENT '分组指标（含 95% 置信区间），运行结束前为 null',
    case_total           INT          NOT NULL DEFAULT 0 COMMENT '运行开始时数据集内的用例总数',
    case_effective       INT          NOT NULL DEFAULT 0 COMMENT '实际参与评判的用例数',
    case_stale           INT          NOT NULL DEFAULT 0 COMMENT '因证据失效被跳过的用例数',
    case_degraded        INT          NOT NULL DEFAULT 0 COMMENT '自动重试后仍处于降级状态的用例数',
    fail_reason          VARCHAR(1024)         DEFAULT NULL COMMENT '仅当 status 为 FAILED 时写入的失败原因',
    started_at           DATETIME              DEFAULT NULL COMMENT '运行开始时间',
    finished_at          DATETIME              DEFAULT NULL COMMENT '运行结束时间',
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_id (run_id),
    KEY idx_dataset_id (dataset_id),
    KEY idx_status (status),
    KEY idx_dataset_created (dataset_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='评测运行：对单套检索配置的一次测量';

CREATE TABLE t_kb_eval_result
(
    id                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    result_id          VARCHAR(64) NOT NULL COMMENT '对外暴露的业务标识',
    run_id             VARCHAR(64) NOT NULL COMMENT '所属评测运行',
    case_id            VARCHAR(64) NOT NULL COMMENT '被评判的用例',
    hit                TINYINT     NOT NULL DEFAULT 0 COMMENT '用例在 Top K 内被召回时置 1',
    hit_rank           INT                  DEFAULT NULL COMMENT '首个命中的名次（从 1 开始），未命中为 null',
    overlap_ratios     JSON                 DEFAULT NULL COMMENT '每条证据的最佳聚合覆盖率',
    recalled_chunk_ids JSON                 DEFAULT NULL COMMENT '本用例 Top K 返回的分片 id 列表',
    degraded           JSON                 DEFAULT NULL COMMENT '评判过程中观察到的降级标记',
    retry_count        INT         NOT NULL DEFAULT 0 COMMENT '本用例经历的自动重试次数',
    judge_score        INT                  DEFAULT NULL COMMENT 'LLM-as-judge 打分，未评判时为 null',
    judge_reason       VARCHAR(2048)        DEFAULT NULL COMMENT 'LLM-as-judge 给出的自由文本理由',
    created_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version       INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted            TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_result_id (result_id),
    KEY idx_run_id (run_id),
    KEY idx_case_id (case_id),
    KEY idx_run_hit (run_id, hit)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='评测运行下逐用例的评判结果';
