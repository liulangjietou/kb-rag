-- 里程碑 M21：最终答案质量评测与发布门禁。
SET NAMES utf8mb4;

ALTER TABLE t_kb_eval_case
    ADD COLUMN expected_refusal TINYINT NOT NULL DEFAULT 0
        COMMENT '正确答案是否应因资料不足而拒答';

ALTER TABLE t_kb_eval_run
    ADD COLUMN answer_eval_config JSON DEFAULT NULL
        COMMENT '答案评测使用的应用版本与完整配置快照',
    ADD COLUMN answer_judge_model VARCHAR(128) DEFAULT NULL
        COMMENT '最终答案评判模型，未开启答案评测时为 null',
    ADD COLUMN answer_judge_prompt_version VARCHAR(32) DEFAULT NULL
        COMMENT '最终答案评分提示词版本',
    ADD COLUMN answer_metrics JSON DEFAULT NULL
        COMMENT '最终答案质量聚合指标';

ALTER TABLE t_kb_eval_result
    ADD COLUMN generated_answer MEDIUMTEXT DEFAULT NULL
        COMMENT '复用生产问答链路生成的最终答案',
    ADD COLUMN answer_judge_requested TINYINT NOT NULL DEFAULT 0
        COMMENT '本 case 是否需要生成并评判最终答案',
    ADD COLUMN generation_latency_ms INT DEFAULT NULL
        COMMENT '最终答案生成耗时，毫秒',
    ADD COLUMN answer_score INT DEFAULT NULL
        COMMENT '最终答案五维评分均值，1-5',
    ADD COLUMN answer_correctness INT DEFAULT NULL
        COMMENT '最终答案正确性评分，1-5',
    ADD COLUMN answer_faithfulness INT DEFAULT NULL
        COMMENT '最终答案忠实度评分，1-5',
    ADD COLUMN answer_completeness INT DEFAULT NULL
        COMMENT '最终答案完整性评分，1-5',
    ADD COLUMN citation_correctness INT DEFAULT NULL
        COMMENT '引用正确性评分，1-5',
    ADD COLUMN citation_completeness INT DEFAULT NULL
        COMMENT '引用完整性评分，1-5',
    ADD COLUMN refusal_correct TINYINT DEFAULT NULL
        COMMENT '是否作出正确的回答或拒答决策',
    ADD COLUMN answer_judge_reason VARCHAR(2048) DEFAULT NULL
        COMMENT '最终答案评分理由或评判失败说明';
