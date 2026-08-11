-- M19 补课：记忆库接入 M16 的多租户行级隔离。V20 建六张表时漏了这一层，后果是多租户部署下
-- 任何租户持 memory:read 的账号能列出全部署的记忆库、持 memory:write 能改删别人的库与 Memory Key。
--
-- 三条取舍，与 V17 同一套推理，写在这里免得后人重新推一遍：
--   ① 只有 t_kb_memory_library 加 tenant_id。它是记忆库聚合的根，片段规则 / 画像规则 / 记忆节点 /
--      用户画像 / Memory Key 五张从属表全部经 library_id 归属租户 —— 给六张表全加列不叫隔离叫散弹枪，
--      从属查询永远先过根表的租户行过滤，再多一列只是第二个可以不一致的事实源。
--   ② 存量行由列 DEFAULT 划入默认租户，升级零迁移，同 V17 的六张根聚合表。
--   ③ 开放 API（MemoryKeyAuthFilter 那条链）不受影响：Memory Key 绑定唯一记忆库，隔离由 Key 的
--      绑定关系天然完成；那条链上没有控制台主体，栅栏本就整条跳过（KbTenantLineHandler#ignoreTable）。
--
-- 列 DEFAULT 只服务存量行。新建记忆库时服务层从不写这一列（MemoryAdminService#createLibrary），
-- tenant_id 由 MybatisPlusConfig 的 TenantLineInnerInterceptor 往 INSERT 注入 —— 与 V17 的六张根
-- 聚合表同一机制。前提是 MyBatis-Plus 保持默认的 NOT_NULL 字段策略（application.yml 未覆盖
-- insert-strategy）：改成 ignored/always 会让实体的 null tenant_id 显式进 SQL，拦截器判定"调用方
-- 已给出租户列"而放行，建库随即撞 NOT NULL 报错。是 fast-fail，但改那个配置前先看这段。
SET NAMES utf8mb4;

ALTER TABLE t_kb_memory_library
    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识' AFTER library_id,
    ADD KEY idx_tenant (tenant_id);
