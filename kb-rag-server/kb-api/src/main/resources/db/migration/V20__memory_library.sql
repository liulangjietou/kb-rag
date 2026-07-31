-- M19：企业级记忆库（对标百炼记忆库）。智能体应用通过 Memory Key（kb-mk-*）调用开放 API 写入与
-- 检索长期记忆：对话经 LLM 按片段规则抽取成记忆节点、按画像规则抽取成结构化画像；MySQL 是事实源，
-- 记忆节点另写 ES（向量 + BM25）供语义检索。Key 绑定唯一记忆库实现应用级隔离，库内再按 user_id
-- （记忆实体）隔离——两层隔离都是查询条件而不是约定。
SET NAMES utf8mb4;

CREATE TABLE t_kb_memory_library
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    library_id   VARCHAR(64)  NOT NULL COMMENT '对外暴露的业务标识（ml_*）',
    name         VARCHAR(64)  NOT NULL COMMENT '记忆库名称，服务层校验同名（逻辑删除下唯一索引会挡住重建）',
    description  VARCHAR(512)          DEFAULT NULL COMMENT '描述，可能用于指导智能体调用的语句',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_library_id (library_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='记忆库';

CREATE TABLE t_kb_memory_fragment_rule
(
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    rule_id          VARCHAR(64)   NOT NULL COMMENT '对外暴露的业务标识（mfr_*）',
    library_id       VARCHAR(64)   NOT NULL COMMENT '所属记忆库',
    name             VARCHAR(64)   NOT NULL COMMENT '规则名称，库内唯一由服务层校验',
    instruction_type VARCHAR(16)   NOT NULL DEFAULT 'DEFAULT' COMMENT '指令类型：DEFAULT 内置抽取指令 / CUSTOM 自定义',
    instruction      VARCHAR(2000)          DEFAULT NULL COMMENT '自定义抽取指令，CUSTOM 时必填',
    auto_update      TINYINT       NOT NULL DEFAULT 1 COMMENT '1 时抽取会合并更新同实体旧记忆而不是只追加',
    -- 过期时间存天数而不是枚举串：写入记忆时用它算 expire_at，NULL 即永不过期，语义在一个列里闭合。
    expire_days      INT                    DEFAULT NULL COMMENT '记忆有效期天数（7/30/180），NULL 永不过期',
    extract_version  VARCHAR(16)   NOT NULL DEFAULT 'PRO' COMMENT '抽取版本：PRO 带旧记忆合并去重 / LITE 单次直抽',
    builtin          TINYINT       NOT NULL DEFAULT 0 COMMENT '1 为建库预置的「默认项目」规则，可编辑不可删除',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version     INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_id (rule_id),
    KEY idx_library_id (library_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='记忆片段规则，每库上限 50 条由服务层守';

CREATE TABLE t_kb_memory_profile_rule
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    rule_id         VARCHAR(64) NOT NULL COMMENT '对外暴露的业务标识（mpr_*）',
    library_id      VARCHAR(64) NOT NULL COMMENT '所属记忆库',
    name            VARCHAR(64) NOT NULL COMMENT '画像规则名称，库内唯一由服务层校验',
    extract_version VARCHAR(16) NOT NULL DEFAULT 'PRO' COMMENT '抽取版本：PRO / LITE',
    -- 字段列表整体存 JSON 而不是拆子表：字段只随规则整体编辑、整体读取，没有按字段查询的入口。
    fields          TEXT        NOT NULL COMMENT '画像字段定义 JSON 数组：[{name,description,initial_value}]，上限 50 个',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version    INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_id (rule_id),
    KEY idx_library_id (library_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户画像规则，每库上限 50 条由服务层守';

CREATE TABLE t_kb_memory_node
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    node_id      VARCHAR(64) NOT NULL COMMENT '对外暴露的业务标识（mn_*）',
    library_id   VARCHAR(64) NOT NULL COMMENT '所属记忆库',
    rule_id      VARCHAR(64) NOT NULL COMMENT '产生该记忆的片段规则',
    user_id      VARCHAR(64) NOT NULL COMMENT '记忆实体 ID，调用方自定义，实体间互相隔离',
    content      TEXT        NOT NULL COMMENT '记忆内容',
    source       VARCHAR(16) NOT NULL COMMENT '来源：EXTRACTED 由 LLM 抽取 / CUSTOM 调用方直写',
    meta_data    TEXT                 DEFAULT NULL COMMENT '调用方自定义元数据 JSON，原样存储原样返回',
    expire_at    DATETIME             DEFAULT NULL COMMENT '过期时间（写入时按规则 expire_days 计算），NULL 永不过期；过期记忆不再被检索',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_node_id (node_id),
    -- 记忆详情页与开放 API list 都按（库，实体）翻页，这是唯一的高频查询路径。
    KEY idx_library_user (library_id, user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='记忆节点（记忆片段），MySQL 为事实源，ES 存检索副本';

CREATE TABLE t_kb_memory_profile
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    library_id   VARCHAR(64) NOT NULL COMMENT '所属记忆库',
    rule_id      VARCHAR(64) NOT NULL COMMENT '所属画像规则',
    user_id      VARCHAR(64) NOT NULL COMMENT '记忆实体 ID',
    attributes   TEXT        NOT NULL COMMENT '已提取的画像属性 JSON 对象：{字段名:值}，未提取字段不落行、读取时回落规则初始值',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    -- 一个实体在一条画像规则下只有一份画像，AddMemory 抽取结果按此键 upsert 合并。
    UNIQUE KEY uk_rule_user (rule_id, user_id),
    KEY idx_library_user (library_id, user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户画像（按实体×画像规则一行）';

CREATE TABLE t_kb_memory_app_key
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    key_id       VARCHAR(64)  NOT NULL COMMENT '对外暴露的业务标识（mak_*），不是密钥本身',
    library_id   VARCHAR(64)  NOT NULL COMMENT '绑定的记忆库：一把 Key 只能读写这一个库（应用级隔离）',
    name         VARCHAR(64)  NOT NULL COMMENT '用途备注，如「客服智能体」',
    -- 与 t_kb_api_key 同一决策：只存 SHA-256 摘要与展示形态，明文仅签发响应回传一次，库泄露不等于凭据泄露。
    key_hash     CHAR(64)     NOT NULL COMMENT '明文 Key 的 SHA-256 十六进制摘要，认证按它查',
    key_prefix   VARCHAR(32)  NOT NULL COMMENT '展示形态（前缀+末四位），列表页可见',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / DISABLED，禁用即刻生效',
    qps_limit    INT          NOT NULL DEFAULT 10 COMMENT '每秒请求上限（令牌桶）',
    last_used_at DATETIME              DEFAULT NULL COMMENT '最近一次成功认证时间，异步更新',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_id (key_id),
    UNIQUE KEY uk_key_hash (key_hash),
    KEY idx_library_id (library_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='记忆库开放 API 的 Memory Key（kb-mk-*）';

-- ---------------------------------------------------------------------------
-- 权限码：记忆库管理页两档，读（看库/规则/记忆/调试检索）与写（建删库、改规则、管 Key）。
-- ---------------------------------------------------------------------------

INSERT INTO t_kb_permission (code, name, module, module_name, sort_order)
VALUES ('memory:read', '查看记忆库与检索调试', 'MEMORY', '记忆库', 10),
       ('memory:write', '管理记忆库、规则与 Memory Key', 'MEMORY', '记忆库', 20);

-- 超管持全量权限的不变量由「播种时全选」维持，新码要补进去；知识库管理员照 V16 的授权口径同样拿到。
INSERT INTO t_kb_role_permission (role_id, permission_code)
VALUES ('role_superadmin000', 'memory:read'),
       ('role_superadmin000', 'memory:write'),
       ('role_kbadmin00000', 'memory:read'),
       ('role_kbadmin00000', 'memory:write');
