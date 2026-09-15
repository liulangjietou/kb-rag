import './source-health.css';

const formatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
});

/** 时间缺失意味着没有可证明的历史，不用最近尝试替代成功或内容变更。 */
export default function SourceHealthTimes({ attempt, success, changed }: {
  attempt?: string | null; success?: string | null; changed?: string | null;
}) {
  return <dl className="source-health-times">
    {([['最近尝试', attempt], ['最近成功', success], ['内容变更', changed]] as const).map(([label, value]) => {
      const valid = value && !Number.isNaN(Date.parse(value));
      return <div key={label}><dt>{label}</dt><dd>{valid
        ? <time dateTime={value}>{formatter.format(new Date(value))}</time> : '暂无记录'}</dd></div>;
    })}
  </dl>;
}
