import { Card, Space, Tag, Typography } from 'antd';
import type { SearchApplied } from '../../../api/types';
import { FUSION_MODE_META, describeThresholdApplied } from '../../../utils/statusMeta';

interface AppliedInfoBarProps {
  applied: SearchApplied;
  degraded: string[];
  originalQuery: string;
}

/**
 * Top-of-results "applied" info bar (M2-CONTRACTS.md section 1.5/5): shows the query actually
 * used after rewrite (when different from the input), the fusion mode in effect, and which
 * score type the threshold filter acted on.
 */
export default function AppliedInfoBar({ applied, degraded, originalQuery }: AppliedInfoBarProps) {
  const thresholdTag = describeThresholdApplied(applied.threshold_applied_on, degraded);
  const rewritten = applied.rewrite_used_query !== null && applied.rewrite_used_query !== originalQuery;

  return (
    <Card size="small" style={{ marginBottom: 16 }}>
      <Space direction="vertical" size={6} style={{ width: '100%' }}>
        <Space wrap>
          <Typography.Text type="secondary">融合模式：</Typography.Text>
          <Tag color="processing">{FUSION_MODE_META[applied.fusion_mode].label}</Tag>
          {thresholdTag && <Tag color={thresholdTag.color}>{thresholdTag.label}</Tag>}
        </Space>
        {rewritten && (
          <Typography.Text>
            <Typography.Text type="secondary">改写后 query：</Typography.Text>
            {applied.rewrite_used_query}
          </Typography.Text>
        )}
      </Space>
    </Card>
  );
}
