import { Alert, Descriptions, Typography } from 'antd';
import type { ModelUsageRecord } from '../../../api/types';

const FAILURE_REASONS: Record<string, string> = {
  AUTH_FAILED: '模型服务拒绝了凭据，请检查对应提供方的密钥配置。',
  QUOTA_EXCEEDED: '模型提供方额度不足或账户欠费，请检查提供方账户。',
  MODEL_QUOTA_EXCEEDED: '租户 Token 配额不足，请核对本月已结算和预占用量。',
  NETWORK_UNREACHABLE: '无法连接模型服务，请检查网络和服务地址。',
  MODEL_NOT_FOUND: '提供方未找到所请求的模型，请检查模型名称。',
  INPUT_TOO_LONG: '输入超过模型上下文长度，请缩短历史消息或检索上下文。',
  OUTPUT_TRUNCATED: '模型达到了输出长度上限，返回内容不完整。请缩小问题范围或核对输出预算。',
  DIMENSION_MISMATCH: '模型返回的向量维度与索引配置不一致，请核对向量模型与知识库索引。',
  CancellationException: '调用已停止；停止前产生的消耗仍会保留。',
  TimeoutException: '调用超时，请结合请求标识核对上游服务。',
  UNKNOWN: '模型调用未正常结束，请结合请求标识进一步定位。',
};

/** 只解释接口已有的计量维度，不读取提示词、答案或异常堆栈。 */
export default function ModelUsageCallDetails({ record }: { record: ModelUsageRecord }) {
  const settled = record.status !== 'RESERVED';
  const warning = record.error_type ? FAILURE_REASONS[record.error_type] ?? '调用未正常完成，请结合错误分类与请求标识定位。' : null;
  const count = (value: number) => new Intl.NumberFormat('zh-CN').format(value);
  return <section className="model-usage-call-details" aria-label={`${record.model} 调用详情`}>
    {warning && <Alert type={record.status === 'CANCELLED' ? 'info' : 'warning'} showIcon message={warning} />}
    <Descriptions size="small" column={{ xs: 1, sm: 2, xl: 3 }} items={[
      { key: 'reservation', label: '调用时预占', children: `${count(record.reserved_tokens)} Token` },
      { key: 'input', label: record.estimated ? '估算输入归集' : '输入用量', children: settled ? `${count(record.input_tokens)} Token` : '待结算' },
      { key: 'output', label: '输出用量', children: !settled ? '待结算' : record.estimated ? '未获得上游准确计数' : `${count(record.output_tokens)} Token` },
      { key: 'measurement', label: '计量依据', children: !settled ? '当前仍为预占' : record.estimated ? '按预占上界保守结算' : record.total_tokens > 0 ? '模型提供方返回的计数' : '无已记录的结算用量' },
      { key: 'failure', label: '错误分类', children: record.error_type ? <Typography.Text code>{record.error_type}</Typography.Text> : '—' },
      { key: 'request', label: '请求标识', children: record.request_id ? <Typography.Text code>{record.request_id}</Typography.Text> : '未提供' },
      { key: 'source', label: '调用来源', children: record.source },
      { key: 'sourceId', label: '来源标识', children: record.source_id || '未提供' },
      { key: 'completed', label: '完成时间', children: record.completed_at?.replace('T', ' ').replace(/\.\d+$/, '') ?? '尚未结束' },
    ]} />
    {settled && <p className="model-usage-note">失败或停止的调用也可能消耗 Token；这里保留已记录的实际或估算用量。未定价表示无法计算成本，不代表免费。</p>}
  </section>;
}
