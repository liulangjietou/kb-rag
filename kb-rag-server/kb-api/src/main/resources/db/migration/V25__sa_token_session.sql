-- 控制台会话底座从自建 TokenStore 换成 Sa-Token 1.46.0。
--
-- 为什么新建一张表而不是复用 t_kb_auth_token：老表是"令牌摘要 -> 用户名 + 过期时刻"的三列专用结构，
-- 而 Sa-Token 的 SaTokenDao 是一套通用 KV 契约（登录令牌、账号会话、令牌会话、临时票据都走同一组
-- get/set/update/delete/getTimeout/updateTimeout）。把 KV 语义硬套进专用三列，等于让存储层去理解
-- 它不该理解的键含义；换成 KV 表以后，Sa-Token 增加任何一种键都不需要再改表。
--
-- 为什么不带 lock_version / deleted 这两个基础列：会话是易失数据，不是业务实体。逻辑删除会让每一次
-- 登出都只是把行标记掉、行数只增不减；乐观锁对"最后写入即有效"的续期语义也没有意义。
--
-- 老表 t_kb_auth_token 刻意不删。它此后没有任何读者，看起来像是该顺手清掉的死表，但 UPGRADING.md
-- 对运维有一条明确承诺：升级失败可以把 server 镜像直接回退到旧版本。删了表，回退后的旧代码会因为
-- 找不到 t_kb_auth_token 而登录不了——一张空表的成本，远低于让回退承诺失效。
-- 确认新版本稳定后由运维手工删除，步骤见 UPGRADING.md。
SET NAMES utf8mb4;

CREATE TABLE t_kb_auth_session
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    session_key   VARCHAR(255) NOT NULL COMMENT 'Sa-Token 的存储键，如 satoken:login:token:xxx',
    session_value MEDIUMTEXT   NOT NULL COMMENT 'Sa-Token 的存储值，会话对象序列化后为 JSON',
    expires_at    DATETIME     NULL COMMENT '绝对过期时刻；NULL 表示永不过期，对应 Sa-Token 的 NEVER_EXPIRE',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 每次读写都按键定位，且 INSERT ... ON DUPLICATE KEY UPDATE 的原子写入依赖这个唯一键。
    UNIQUE KEY uk_session_key (session_key),
    -- 过期清理按此列范围扫描；searchData 的前缀匹配走 uk_session_key。
    KEY idx_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='控制台会话存储（Sa-Token KV），cache.provider=local 时启用';

-- 单点登录的一次性 state 原本借住在 t_kb_auth_token 里，理由是"形状一样"：都是随机值的摘要加一个过期
-- 时刻，连过期清理都能顺带做掉。老表既然要删，它得有个去处。
--
-- 没有让它并入上面那张 Sa-Token 的 KV 表：那张表的键归框架命名和管理，混进第二个写入者以后，谁在写
-- 哪些键就只能靠约定维持。也没有改用 Sa-Token 的临时票据——那会把 state 明文存进存储，而这里从设计上
-- 就只写摘要，拖库拿不到能用的 state。安全属性不该在换框架时被顺手降级，所以给它一张自己的表。
CREATE TABLE t_kb_sso_state
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    state_hash VARCHAR(64)  NOT NULL COMMENT '一次性 state 的 SHA-256 摘要，也是唯一的存储形式',
    payload    VARCHAR(128) NOT NULL COMMENT '回调时原样取回的流程上下文，如 sso:OIDC',
    expires_at DATETIME     NOT NULL COMMENT '绝对过期时刻，签发时间加上流程允许的时长',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_state_hash (state_hash),
    KEY idx_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='单点登录流程的一次性 state';
