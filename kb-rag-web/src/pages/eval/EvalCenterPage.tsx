// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Select, Tabs } from 'antd';
import { useSearchParams } from 'react-router-dom';
import { listEvalDatasets } from '../../api/evalDataset';
import { listKnowledgeBases } from '../../api/kb';
import type { EvalDataset, KnowledgeBase } from '../../api/types';
import PageHeader from '../../components/PageHeader';
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
  const [searchParams] = useSearchParams();
  const requestedKb = searchParams.get('kb_id');
  const requestedDataset = searchParams.get('dataset_id');
  const requestedTab = searchParams.get('tab');
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [kbId, setKbId] = useState<string | null>(null);
  const [datasets, setDatasets] = useState<EvalDataset[]>([]);
  const [datasetsLoading, setDatasetsLoading] = useState(false);
  const [currentDatasetId, setCurrentDatasetId] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState('dataset');
  const datasetRequest = useRef(0);

  useEffect(() => {
    let active = true;
    listKnowledgeBases().then((list) => {
      if (!active) return;
      setKbs(list);
      setKbId((prev) => list.some((kb) => kb.kb_id === requestedKb) ? requestedKb
        : list.some((kb) => kb.kb_id === prev) ? prev : list[0]?.kb_id ?? null);
    });
    return () => { active = false; };
  }, [requestedKb]);

  useEffect(() => {
    if (requestedTab && ['dataset', 'cases', 'review', 'run'].includes(requestedTab)) setActiveTab(requestedTab);
  }, [requestedTab]);

  const loadDatasets = useCallback(async () => {
    const request = ++datasetRequest.current;
    if (!kbId) {
      setDatasets([]);
      return;
    }
    setDatasetsLoading(true);
    try {
      const result = await listEvalDatasets(kbId);
      if (request !== datasetRequest.current) return;
      setDatasets(result);
      setCurrentDatasetId((previous) => result.some((dataset) => dataset.dataset_id === previous) ? previous
        : result.some((dataset) => dataset.dataset_id === requestedDataset) ? requestedDataset : null);
    } finally {
      if (request === datasetRequest.current) setDatasetsLoading(false);
    }
  }, [kbId, requestedDataset]);

  useEffect(() => {
    setCurrentDatasetId(null);
    setDatasets([]);
    loadDatasets();
    return () => { datasetRequest.current += 1; };
  }, [kbId, loadDatasets]);

  const currentDataset = datasets.find((dataset) => dataset.dataset_id === currentDatasetId) ?? null;

  const selectCurrentDataset = (datasetId: string) => {
    setCurrentDatasetId(datasetId);
    setActiveTab('cases');
  };

  return (
    <div className="catalog-eval-page catalog-detail-page catalog-eval-center">
      <PageHeader
        eyebrow="QUALITY LAB / 质量评测"
        title="评测中心"
        description="围绕同一知识库建立语料集、完成证据标注，并用可比的运行报告守住应用发布质量。"
        actions={
          <Select
            className="catalog-context-select"
            placeholder="请选择知识库"
            value={kbId ?? undefined}
            options={kbs.map((kb) => ({ label: kb.name, value: kb.kb_id }))}
            onChange={(value) => setKbId(value)}
          />
        }
      />

      {!kbId ? (
        <Alert type="info" showIcon message="请先创建知识库后再使用评测中心" />
      ) : (
        <Tabs
          className="catalog-workbench-tabs"
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
