-- 里程碑 M9 的结构增量：子分片在父分片中的位置，对应需求文档 4.5 节。
--
-- 为什么要建列而不是运行时推导。检索侧在返回父分片正文之前，会把已停用子分片对应的那一段抠掉；
-- 如果查询时再去父分片里搜索子分片文本来还原位置，既更慢又有歧义——重叠切分本来就会让相邻子分片
-- 重复同一段文本，搜索可能落到错误的那一次出现上。切分器本身是知道这个位置的（子分片就是从父分片
-- 里切出来的），所以写一次、以后廉价读回即可。
--
-- 刻意设计成可空，并且 null 就是安全值。存量数据一律为 NULL，检索侧把它读作「这个父分片无法精确
-- 抠除」，于是原样返回整个父分片，行为和本里程碑之前完全一致。标注的写入链路只要文本位置发生移动，
-- 就会把这一对偏移清空。
SET NAMES utf8mb4;

ALTER TABLE t_kb_chunk
    ADD COLUMN parent_start_offset INT DEFAULT NULL COMMENT '本子分片在父分片正文中的起始偏移，未知时为 null' AFTER parent_id,
    ADD COLUMN parent_end_offset   INT DEFAULT NULL COMMENT '本子分片在父分片正文中的结束偏移（不含），未知时为 null' AFTER parent_start_offset;
