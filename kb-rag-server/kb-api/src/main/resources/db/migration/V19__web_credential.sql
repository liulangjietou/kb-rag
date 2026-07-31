-- M18：站点凭据。网页导入抓取需要登录的站点（内网 wiki、私有文档站）时，按 host 挂一份凭据，
-- 抓取时只对该 host 的请求注入认证头。凭据属于站点而不属于页面：同一站点登记再多 URL 也只存
-- 一份，轮换只改一处。host 精确匹配、不做通配——通配是凭据被兄弟子域套走的经典入口。
SET NAMES utf8mb4;

CREATE TABLE t_kb_web_credential
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    credential_id VARCHAR(64)  NOT NULL COMMENT '控制台对外暴露的业务标识',
    host          VARCHAR(255) NOT NULL COMMENT '凭据生效的精确 host（带非默认端口则含端口），全局唯一',
    auth_type     VARCHAR(16)  NOT NULL COMMENT '认证类型：BASIC 用户名密码 / HEADER 任意请求头（覆盖 Bearer 与 Cookie）',
    username      VARCHAR(128)          DEFAULT NULL COMMENT 'BASIC 的用户名，HEADER 类型为 null',
    -- 刻意明文存储，与 t_kb_ext_source.secret_key 同一决策（D17）：单管理员控制台、网络隔离部署。
    -- 读接口永不回传这一列；建议站点侧使用专用只读账号，控制明文的爆炸半径。
    secret        VARCHAR(512) NOT NULL COMMENT 'BASIC 存密码，HEADER 存头的完整值，接口永不回传',
    header_name   VARCHAR(64)           DEFAULT NULL COMMENT 'HEADER 的头名（如 Authorization / Cookie），BASIC 类型为 null',
    enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '置 0 时抓取不再使用该凭据，行保留',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    lock_version  INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记，1 表示已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_credential_id (credential_id),
    -- 一个 host 只有一份凭据；抓取按 host 查找，这也是查询唯一入口。
    UNIQUE KEY uk_host (host)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='网页导入的站点级认证凭据';
