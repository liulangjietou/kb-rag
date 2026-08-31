const PIPELINE_STAGES = [
  ['解析', 'Parse'],
  ['召回', 'Recall'],
  ['融合', 'Fuse'],
  ['重排', 'Rerank'],
  ['回答', 'Answer'],
  ['评测', 'Evaluate'],
] as const;

interface RetrievalPipelineProps {
  compact?: boolean;
}

/** 设计稿中的标志性检索链路，只表达平台能力，不承载运行状态。 */
export default function RetrievalPipeline({ compact = false }: RetrievalPipelineProps) {
  return (
    <ol
      className={`retrieval-pipeline${compact ? ' retrieval-pipeline--compact' : ''}`}
      aria-label="RAG 检索链路"
    >
      {PIPELINE_STAGES.map(([label, english], index) => (
        <li key={english} className={index === 4 ? 'is-accent' : undefined}>
          <i aria-hidden="true" />
          <strong>{label}</strong>
          <small>{english}</small>
        </li>
      ))}
    </ol>
  );
}
