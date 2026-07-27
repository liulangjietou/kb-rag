// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { Alert, Select, Space, Tabs, Typography } from 'antd';
import { listEvalDatasets } from '../../api/evalDataset';
import { listKnowledgeBases } from '../../api/kb';
import type { EvalDataset, KnowledgeBase } from '../../api/types';
import EvalCaseTab from './components/EvalCaseTab';
import EvalDatasetTab from './components/EvalDatasetTab';
import EvalReviewTab from './components/EvalReviewTab';
import EvalRunTab from './components/EvalRunTab';

/**
 * Evaluation center top-level page (M4b-CONTRACTS.md section 5): a knowledge-base selector plus
 * four tabs scoped to that KB's evaluation datasets -- dataset management, case annotation
 * workbench, evidence review workbench, and run/report. The dataset picked (or created) in the
 * first tab becomes the shared "current dataset" context consumed by the other three, mirroring
 * how KbDetailPage owns document state that its drawers read from.
 */
export default function EvalCenterPage() {
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [kbId, setKbId] = useState<string | null>(null);
  const [datasets, setDatasets] = useState<EvalDataset[]>([]);
  const [datasetsLoading, setDatasetsLoading] = useState(false);
  const [currentDatasetId, setCurrentDatasetId] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState('dataset');

  useEffect(() => {
    listKnowledgeBases().then((list) => {
      setKbs(list);
      setKbId((prev) => prev ?? list[0]?.kb_id ?? null);
    });
  }, []);

  const loadDatasets = useCallback(async () => {
    if (!kbId) {
      setDatasets([]);
      return;
    }
    setDatasetsLoading(true);
    try {
      const result = await listEvalDatasets(kbId);
      setDatasets(result);
    } finally {
      setDatasetsLoading(false);
    }
  }, [kbId]);

  useEffect(() => {
    setCurrentDatasetId(null);
    loadDatasets();
  }, [kbId, loadDatasets]);

  const currentDataset = datasets.find((dataset) => dataset.dataset_id === currentDatasetId) ?? null;

  const selectCurrentDataset = (datasetId: string) => {
    setCurrentDatasetId(datasetId);
    setActiveTab('cases');
  };

  return (
    <div>
      <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }} wrap>
        <Typography.Title level={4} style={{ margin: 0 }}>
          评测中心
        </Typography.Title>
        <Select
          style={{ width: 280 }}
          placeholder="请选择知识库"
          value={kbId ?? undefined}
          options={kbs.map((kb) => ({ label: kb.name, value: kb.kb_id }))}
          onChange={(value) => setKbId(value)}
        />
      </Space>

      {!kbId ? (
        <Alert type="info" showIcon message="请先创建知识库后再使用评测中心" />
      ) : (
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: 'dataset',
              label: '评测集管理',
              children: (
                <EvalDatasetTab
                  kbId={kbId}
                  datasets={datasets}
                  loading={datasetsLoading}
                  currentDatasetId={currentDatasetId}
                  onSelectDataset={selectCurrentDataset}
                  onChanged={loadDatasets}
                />
              ),
            },
            {
              key: 'cases',
              label: '标注工作台',
              children: <EvalCaseTab kbId={kbId} dataset={currentDataset} onDatasetChanged={loadDatasets} />,
            },
            {
              key: 'review',
              label: '证据复核工作台',
              children: <EvalReviewTab dataset={currentDataset} onDatasetChanged={loadDatasets} />,
            },
            {
              key: 'run',
              label: '评测任务与报告',
              children: <EvalRunTab dataset={currentDataset} />,
            },
          ]}
        />
      )}
    </div>
  );
}
