-- M16 后修复：三张"任意租户可改删全部署数据"的表定性并收口。
--
-- 这三张表都不带 tenant_id，也从来没有人写下过"它们是有意共享的"。普查把它们摊开之后，
-- 结论不是同一个 —— 判据是"这份数据是租户的业务资产，还是部署本身的设施"：
--
--   ① t_kb_source_mapping（聊天导入映射模板）→ 租户的业务资产，收进租户维度（本脚本）。
--      各租户导出的聊天记录格式本就不同，模板是他们各自调出来的。而且写端点用的是 doc:write，
--      不是 system:config —— 任何租户的普通文档编辑者都能改删全部署的模板，破坏面比另外两张更大。
--   ② t_kb_ik_dict（IK 分词词典）→ 部署级设施，收紧到平台运维权限（本脚本加权限码，代码改注解）。
--      ES 插件从 /internal/dict/ik/{type}.txt 拉取，那是集群级设置：按租户切要求每租户一份渲染
--      文档 + 每租户一套 analyzer + 每租户一批索引配置，这不是隔离问题而是另一个特性。
--   ③ t_kb_system_config 的告警配置（webhook_url 等）→ 部署级设施，同上。运维告警本就该有一个
--      出口；真正的风险是 webhook_url 可以被任意租户改成自己的地址，把别家的告警内容引出去 ——
--      那是"谁能改"的问题，不是"存几份"的问题。
--
-- ①为什么是加列而不是加一层"内置全局 + 租户自建"的混合可见性：混合可见性要在每个查询里写
-- "本租户的 OR 全局的"，任何一处漏写就是一个缺口，而围栏帮不上忙（它只会拼等值条件）。
-- 内置模板改为建租户时复制一份（TenantService#copyBuiltinSourceMappings），与 V17 复制五个
-- 内置角色同一套做法：复制发生一次，之后各租户各改各的，读路径上没有任何分支。
SET NAMES utf8mb4;

-- ---------------------------------------------------------------- ① 映射模板收进租户维度

ALTER TABLE t_kb_source_mapping
    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'tnt_default0000000' COMMENT '所属租户业务标识' AFTER mapping_id;

-- 名称在租户内唯一而不是全局唯一：导入参数 mapping_profile 按名称寻址模板，而复制内置模板会让
-- 'memotrace' 在每个租户各出现一行 —— 全局唯一键会把复制的第一步就顶回去。收缩的推理与 V17 对
-- t_kb_role.uk_code 的处理逐字相同。
ALTER TABLE t_kb_source_mapping
    DROP INDEX uk_name,
    ADD UNIQUE KEY uk_tenant_name (tenant_id, name);

-- 不额外建 idx_tenant：uk_tenant_name 的最左前缀就是 tenant_id，列表页与按名解析都走它。

-- 存量行由列 DEFAULT 划入默认租户，升级零迁移。新建模板时服务层从不写这一列，tenant_id 由
-- TenantLineInnerInterceptor 往 INSERT 注入（同 V17/V21/V22），前提同样是 MyBatis-Plus 保持
-- 默认的 NOT_NULL 字段策略。启动种子（SourceMappingSeeder）跑在无主体线程上，围栏整条跳过，
-- 因此那里显式钉死默认租户 —— 无主体线程上"围栏跳过"意味着零防护，这是 V22 立下的通则。

-- 为已存在的非默认租户补一份内置模板：本脚本之后建的租户由 TenantService 复制，之前建的没有。
-- mapping_id 用 UUID 现算，与 V17 回填 user_id 同一手法。
INSERT INTO t_kb_source_mapping (mapping_id, tenant_id, name, source_type, profile_yaml, is_builtin)
SELECT CONCAT('smp_', SUBSTRING(REPLACE(UUID(), '-', ''), 1, 16)),
       t.tenant_id,
       m.name,
       m.source_type,
       m.profile_yaml,
       1
FROM t_kb_source_mapping m
         CROSS JOIN t_kb_tenant t
WHERE m.tenant_id = 'tnt_default0000000'
  AND m.is_builtin = 1
  AND m.deleted = 0
  AND t.tenant_id <> 'tnt_default0000000'
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1
                  FROM t_kb_source_mapping e
                  WHERE e.tenant_id = t.tenant_id
                    AND e.name = m.name
                    AND e.deleted = 0);

-- ------------------------------------------------- ②③ 部署级配置收紧到平台运维权限

-- 第 20 个权限码，第二个平台级码（PermissionCodes.PLATFORM_ONLY）。与 tenant:manage 分开而不是
-- 复用它：那个码同时还是 t_kb_admin_user / t_kb_role 的围栏例外开关（M16 契约 §1.3），把"改 IK
-- 词典"挂到它下面会让那个例外的边界更难说清。sort_order 排在 tenant:manage 之后。
INSERT INTO t_kb_permission (code, name, module, module_name, sort_order)
VALUES ('platform:config', '部署级配置（IK 词典、告警出口）', 'SYSTEM', '系统', 50);

-- 只授默认租户的 SUPER_ADMIN。子租户的 SUPER_ADMIN 拿不到它，建租户复制内置角色时也会被剔除
-- （TenantService 走 RoleService#replacePermissions，平台级码在那里过滤）。
INSERT INTO t_kb_role_permission (role_id, permission_code)
VALUES ('role_superadmin000', 'platform:config');
