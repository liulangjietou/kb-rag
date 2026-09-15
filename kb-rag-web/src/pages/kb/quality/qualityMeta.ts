import type { QualityIssueReason, QualityIssueStatus, QualityIssueRecord, QualityIssueSource } from '../../../api/qualityIssue';

export const QUALITY_SOURCES: Record<QualityIssueSource, string> = {
  BAD_FEEDBACK: '负面反馈', ZERO_HIT: '零命中报告', EMPLOYEE_ANSWER: '员工问答反馈',
};

export const QUALITY_STATUS: Record<QualityIssueStatus, { label: string; color: string }> = {
  NEW: { label: '待领取', color: 'default' }, IN_PROGRESS: { label: '处理中', color: 'processing' },
  WAITING_REGRESSION: { label: '待回归', color: 'warning' }, RESOLVED: { label: '已解决', color: 'success' },
};
export const QUALITY_REASONS: Record<QualityIssueReason, string> = {
  MISSING_KNOWLEDGE: '知识缺失', OUTDATED_KNOWLEDGE: '资料过期', RETRIEVAL_MISS: '召回不准确',
  ANSWER_INCORRECT: '回答不准确', QUESTION_UNCLEAR: '问题不明确', OTHER: '其他原因',
};
export const QUALITY_ACTIONS: Record<QualityIssueRecord['action'], string> = {
  CREATED: '建立问题', CLAIMED: '领取问题', RELEASED: '释放问题', CORRECTED: '确认纠正用例',
  NOTE_ADDED: '补充处理说明', RESOLVED: '核验回归并解决', REOPENED: '重新打开问题',
};
