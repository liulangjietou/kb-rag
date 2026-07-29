-- 里程碑 M6 的结构增量：应用版本的索引快照。
-- 只在既有的版本表上加两列，不新建表：物理索引本身已经由 t_kb_index_registry 描述，
-- 快照链路也会往那张表里注册一行；若再按应用版本建一份注册表，只会重复这份描述并可能与它冲突。
-- V1-V6 发布后不再修改，因此后续每一次变更都以独立的迁移脚本落地。
SET NAMES utf8mb4;

ALTER TABLE t_kb_app_version
    -- 发布时冻结的 {kb_id: [document_version_id, ...]}，对应需求文档 4.7 节「版本可见集」。
    -- 发布版调用在引擎侧必带的版本过滤条件取值于此，而不是取自当前的生效版本指针：
    -- 旧快照里只有它自己发布那一刻的版本，若按今天的生效版本去过滤，回滚后将什么都召回不到。
    -- 三种情况下为 NULL——从未发布过的版本、本里程碑之前发布的版本、快照已被保留期任务清理的版本，
    -- 它们都回落到实时别名。
    ADD COLUMN visible_version_ids JSON DEFAULT NULL
        COMMENT '按知识库冻结的 document_version_id 集合，为 null 时回落到实时别名'
        AFTER released_at,
    -- [{kb_id, engine, physical_index_name}]，每个知识库、每种引擎一个元素：全量模式部署会把 BM25
    -- 索引和向量集合分别快照，调用时要按召回路由各自寻址到正确的那一个。
    -- 这里存物理索引名而不是运行时重算，这样发布之后再换向量模型，也不会把历史版本重新指向一个
    -- 它从未使用过的索引。
    ADD COLUMN index_snapshots JSON DEFAULT NULL
        COMMENT '发布时冻结的物理索引集合，为 null 时回落到实时别名'
        AFTER visible_version_ids;
