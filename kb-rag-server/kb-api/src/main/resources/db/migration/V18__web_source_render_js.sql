-- M17：网页源新增可选的 JS 渲染抓取开关。置 1 时该源的抓取走无头浏览器，取脚本渲染后的
-- DOM 入库，用于正文靠 JS 注入的框架页（如 Javadoc index.html）；默认 0 保持 M12 的静态抓取，
-- 存量源升级后行为零变化。仅随行读出、不参与查询过滤，因此不建索引。
SET NAMES utf8mb4;

ALTER TABLE t_kb_web_source
    ADD COLUMN render_js TINYINT NOT NULL DEFAULT 0
        COMMENT '置 1 时该源抓取走无头浏览器 JS 渲染，默认 0 静态抓取' AFTER sync_enabled;
