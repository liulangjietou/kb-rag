import { DeploymentUnitOutlined } from '@ant-design/icons';

interface BrandMarkProps {
  compact?: boolean;
  inverse?: boolean;
}

/** 平台品牌标识：用连通节点表达知识、检索与智能体之间的关系。 */
export default function BrandMark({ compact = false, inverse = false }: BrandMarkProps) {
  return (
    <div className={`brand-mark${compact ? ' brand-mark--compact' : ''}${inverse ? ' brand-mark--inverse' : ''}`}>
      <span className="brand-mark__symbol" aria-hidden="true">
        <DeploymentUnitOutlined />
      </span>
      {!compact && (
        <span className="brand-mark__copy">
          <strong>Knowledge Atlas</strong>
          <small>企业 RAG 管理平台</small>
        </span>
      )}
    </div>
  );
}
