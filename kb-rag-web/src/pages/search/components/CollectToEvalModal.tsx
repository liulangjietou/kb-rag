// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { Alert, Form, Input, Modal, Radio, Select, Typography, message } from 'antd';
import { createEvalDataset, listEvalDatasets } from '../../../api/evalDataset';
import { createEvalCaseFromRetrieval } from '../../../api/evalCase';
import type { EvalDataset, RetrievalNode } from '../../../api/types';

interface CollectToEvalModalProps {
  open: boolean;
  kbId: string;
  query: string;
  selectedNodes: RetrievalNode[];
  onClose: () => void;
  onCollected: () => void;
}

type TargetMode = 'existing' | 'new';

interface CollectFormValues {
  target_mode: TargetMode;
  dataset_id?: string;
  new_dataset_name?: string;
}

/**
 * "收进评测集" modal for the search debug page (M4b-CONTRACTS.md section 5): picks (or creates)
 * a target dataset for this KB, then calls POST cases/from-retrieval with the checked result
 * cards' chunk_ids. Any image-type result forces anchor_type=DOCUMENT client-side, mirroring the
 * server's own auto-detection rule (section 2 "图片类 chunk...建成文档级锚定 case") so the confirm
 * text is accurate even before the request round-trips.
 */
export default function CollectToEvalModal({ open, kbId, query, selectedNodes, onClose, onCollected }: CollectToEvalModalProps) {
  const [form] = Form.useForm<CollectFormValues>();
  const targetMode = Form.useWatch('target_mode', form) ?? 'existing';
  const [datasets, setDatasets] = useState<EvalDataset[]>([]);
  const [loadingDatasets, setLoadingDatasets] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const containsImage = selectedNodes.some((node) => node.chunk_type === 'image');

  useEffect(() => {
    if (!open) {
      return;
    }
    form.setFieldsValue({ target_mode: 'existing' });
    setLoadingDatasets(true);
    listEvalDatasets(kbId)
      .then((result) => {
        setDatasets(result);
        form.setFieldsValue({ target_mode: result.length > 0 ? 'existing' : 'new' });
      })
      .finally(() => setLoadingDatasets(false));
  }, [open, kbId, form]);

  const handleSubmit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const datasetId =
        values.target_mode === 'new'
          ? (await createEvalDataset(kbId, { name: values.new_dataset_name! })).dataset_id
          : values.dataset_id!;
      await createEvalCaseFromRetrieval(datasetId, {
        query,
        chunk_ids: selectedNodes.map((node) => node.chunk_id),
        anchor_type: containsImage ? 'DOCUMENT' : undefined,
      });
      message.success('已收进评测集');
      form.resetFields();
      onCollected();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="收进评测集"
      open={open}
      onOk={handleSubmit}
      onCancel={onClose}
      confirmLoading={submitting}
      okText="收进"
      cancelText="取消"
      destroyOnClose
    >
      <Typography.Paragraph type="secondary">
        将勾选的 {selectedNodes.length} 条检索结果作为证据，连同当前 query 收进一条评测 case（source=DEBUG_PAGE）。
      </Typography.Paragraph>
      {containsImage && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="所选结果含图片类分片，将自动按文档级锚定收录（不做 span 重叠计算）"
        />
      )}
      <Form<CollectFormValues> form={form} layout="vertical">
        <Form.Item name="target_mode" label="目标评测集">
          <Radio.Group>
            <Radio value="existing" disabled={datasets.length === 0}>
              选择已有评测集
            </Radio>
            <Radio value="new">新建评测集</Radio>
          </Radio.Group>
        </Form.Item>
        {targetMode === 'existing' ? (
          <Form.Item name="dataset_id" rules={[{ required: true, message: '请选择评测集' }]}>
            <Select
              loading={loadingDatasets}
              placeholder={datasets.length === 0 ? '该知识库暂无评测集，请先新建' : '请选择评测集'}
              options={datasets.map((dataset) => ({ label: dataset.name, value: dataset.dataset_id }))}
            />
          </Form.Item>
        ) : (
          <Form.Item name="new_dataset_name" rules={[{ required: true, message: '请输入新评测集名称' }]}>
            <Input placeholder="新评测集名称" maxLength={64} />
          </Form.Item>
        )}
      </Form>
    </Modal>
  );
}
