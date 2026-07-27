// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Pagination, Popconfirm, Select, Space, Table, Tag, message } from 'antd';
import { deleteEvalCase, listEvalCases } from '../../../api/evalCase';
import type { CaseStatus, EvalCase, EvalDataset } from '../../../api/types';
import { ANCHOR_TYPE_META, CASE_STATUS_META, metaOf } from '../../../utils/statusMeta';
import EvalCaseFormModal from './EvalCaseFormModal';

interface EvalCaseTabProps {
  kbId: string;
  dataset: EvalDataset | null;
  onDatasetChanged: () => void;
}

const STATUS_FILTER_OPTIONS: { label: string; value: CaseStatus | 'ALL' }[] = [
  { label: '全部状态', value: 'ALL' },
  { label: metaOf(CASE_STATUS_META, 'ACTIVE').label, value: 'ACTIVE' },
  { label: metaOf(CASE_STATUS_META, 'EVIDENCE_STALE').label, value: 'EVIDENCE_STALE' },
  { label: metaOf(CASE_STATUS_META, 'DEPRECATED').label, value: 'DEPRECATED' },
];

/** Case annotation workbench tab (M4b-CONTRACTS.md section 5 item 2). */
export default function EvalCaseTab({ kbId, dataset, onDatasetChanged }: EvalCaseTabProps) {
  const datasetId = dataset?.dataset_id ?? null;
  const [cases, setCases] = useState<EvalCase[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<CaseStatus | 'ALL'>('ALL');
  const [loading, setLoading] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [editingCase, setEditingCase] = useState<EvalCase | null>(null);

  const loadCases = useCallback(async () => {
    if (!datasetId) {
      setCases([]);
      setTotal(0);
      return;
    }
    setLoading(true);
    try {
      const result = await listEvalCases(datasetId, {
        status: statusFilter === 'ALL' ? undefined : statusFilter,
        page,
      });
      setCases(result.items);
      setTotal(result.total);
    } finally {
      setLoading(false);
    }
  }, [datasetId, statusFilter, page]);

  useEffect(() => {
    loadCases();
  }, [loadCases]);

  useEffect(() => {
    setPage(1);
  }, [datasetId, statusFilter]);

  const handleDelete = async (caseId: string) => {
    await deleteEvalCase(caseId);
    message.success('case 已删除');
    loadCases();
    onDatasetChanged();
  };

  const handleSaved = () => {
    setFormOpen(false);
    setEditingCase(null);
    loadCases();
    onDatasetChanged();
  };

  if (!dataset) {
    return <Empty description="请先在「评测集管理」选择一个评测集" />;
  }

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={`当前评测集：${dataset.name}（dataset_revision: ${dataset.dataset_revision}，共 ${dataset.case_count} 条 case）`}
      />
      <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }} wrap>
        <Select style={{ width: 160 }} value={statusFilter} options={STATUS_FILTER_OPTIONS} onChange={setStatusFilter} />
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setEditingCase(null);
            setFormOpen(true);
          }}
        >
          新增 case
        </Button>
      </Space>

      <Table<EvalCase>
        rowKey="case_id"
        loading={loading}
        dataSource={cases}
        pagination={false}
        columns={[
          { title: 'query', dataIndex: 'query', ellipsis: true },
          {
            title: '锚定类型',
            dataIndex: 'anchor_type',
            width: 100,
            render: (anchorType: EvalCase['anchor_type']) => {
              const meta = metaOf(ANCHOR_TYPE_META, anchorType);
              return <Tag color={meta.color}>{meta.label}</Tag>;
            },
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            render: (status: EvalCase['status']) => {
              const meta = metaOf(CASE_STATUS_META, status);
              return <Tag color={meta.color}>{meta.label}</Tag>;
            },
          },
          {
            title: '证据条数',
            dataIndex: 'evidences',
            width: 90,
            render: (evidences: EvalCase['evidences']) => evidences.length,
          },
          {
            title: '轮次',
            width: 90,
            render: (_, record: EvalCase) => <Tag>{record.messages && record.messages.length > 0 ? '多轮' : '单轮'}</Tag>,
          },
          {
            title: '操作',
            width: 160,
            render: (_, record: EvalCase) => (
              <Space>
                <Button
                  size="small"
                  onClick={() => {
                    setEditingCase(record);
                    setFormOpen(true);
                  }}
                >
                  编辑
                </Button>
                <Popconfirm title="确认删除该 case？" okText="删除" cancelText="取消" onConfirm={() => handleDelete(record.case_id)}>
                  <Button size="small" danger>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
      {total > 0 && (
        <Pagination style={{ marginTop: 16, textAlign: 'right' }} current={page} total={total} onChange={setPage} showSizeChanger={false} />
      )}

      <EvalCaseFormModal
        open={formOpen}
        kbId={kbId}
        datasetId={dataset.dataset_id}
        editingCase={editingCase}
        onClose={() => setFormOpen(false)}
        onSaved={handleSaved}
      />
    </div>
  );
}
