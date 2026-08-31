// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Form, Input, Modal, Radio, Select, Space, Switch, Typography, message } from 'antd';
import { listDocuments } from '../../../api/document';
import { createEvalCase, updateEvalCase } from '../../../api/evalCase';
import type {
  AnchorType,
  ChatMessage,
  CreateEvalCaseRequest,
  EvalCase,
  EvalCaseEvidenceInput,
  KbDocument,
} from '../../../api/types';
import { ANCHOR_TYPE_META, metaOf } from '../../../utils/statusMeta';

const { TextArea } = Input;

interface EvalCaseFormModalProps {
  open: boolean;
  kbId: string;
  datasetId: string;
  /** Present when editing an existing case; null for the "new case" flow. */
  editingCase: EvalCase | null;
  onClose: () => void;
  onSaved: () => void;
}

/** Form-only value shape edited in this modal; converted to CreateEvalCaseRequest on submit. */
interface EvalCaseFormValues {
  query: string;
  multi_turn: boolean;
  messages?: ChatMessage[];
  anchor_type: AnchorType;
  evidences: EvalCaseEvidenceInput[];
  expected_answer?: string;
  expected_refusal: boolean;
  note?: string;
}

const EMPTY_EVIDENCE: EvalCaseEvidenceInput = { doc_id: '', span: '' };

/**
 * Create/edit form for one eval case (M4b-CONTRACTS.md section 5 item 2): query, an optional
 * multi-turn history editor, anchor type, and an evidence list editor (pick a document + paste
 * the span excerpt). The span field is dropped from the submitted payload entirely when
 * anchor_type=DOCUMENT, per the contract's "DOCUMENT 锚定时 span 为空".
 */
export default function EvalCaseFormModal({
  open,
  kbId,
  datasetId,
  editingCase,
  onClose,
  onSaved,
}: EvalCaseFormModalProps) {
  const [form] = Form.useForm<EvalCaseFormValues>();
  const [docs, setDocs] = useState<KbDocument[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const anchorType = Form.useWatch('anchor_type', form) ?? 'SPAN';
  const multiTurn = Form.useWatch('multi_turn', form) ?? false;

  useEffect(() => {
    if (open) {
      listDocuments(kbId).then((result) => setDocs(result.items));
    }
  }, [open, kbId]);

  useEffect(() => {
    if (!open) {
      return;
    }
    if (editingCase) {
      form.setFieldsValue({
        query: editingCase.query,
        multi_turn: !!(editingCase.messages && editingCase.messages.length > 0),
        messages: editingCase.messages ?? [],
        anchor_type: editingCase.anchor_type,
        evidences: editingCase.evidences.map((evidence) => ({ doc_id: evidence.doc_id, span: evidence.span ?? '' })),
        expected_answer: editingCase.expected_answer ?? undefined,
        expected_refusal: editingCase.expected_refusal,
        note: editingCase.note ?? undefined,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ anchor_type: 'SPAN', multi_turn: false, expected_refusal: false, evidences: [{ ...EMPTY_EVIDENCE }] });
    }
  }, [open, editingCase, form]);

  const handleSubmit = async () => {
    const values = await form.validateFields();
    if (!values.evidences || values.evidences.length === 0) {
      message.warning('请至少添加一条证据');
      return;
    }
    const payload: CreateEvalCaseRequest = {
      query: values.query,
      messages: values.multi_turn ? values.messages : undefined,
      expected_answer: values.expected_answer || undefined,
      expected_refusal: values.expected_refusal,
      anchor_type: values.anchor_type,
      evidences: values.evidences.map((evidence) => ({
        doc_id: evidence.doc_id,
        span: values.anchor_type === 'SPAN' ? evidence.span : undefined,
      })),
      note: values.note || undefined,
    };
    setSubmitting(true);
    try {
      if (editingCase) {
        await updateEvalCase(editingCase.case_id, payload);
        message.success('case 已更新');
      } else {
        await createEvalCase(datasetId, payload);
        message.success('case 新增成功');
      }
      onSaved();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      rootClassName="catalog-eval-modal catalog-eval-case-modal"
      title={editingCase ? '编辑评测 case' : '新增评测 case'}
      open={open}
      onOk={handleSubmit}
      onCancel={onClose}
      confirmLoading={submitting}
      okText="保存"
      cancelText="取消"
      width={720}
      destroyOnClose
    >
      <Form<EvalCaseFormValues> form={form} layout="vertical">
        <Form.Item name="query" label="query（当前提问）" rules={[{ required: true, message: '请输入 query' }]}>
          <TextArea rows={2} placeholder="用户当前这一轮的提问" />
        </Form.Item>

        <Form.Item name="multi_turn" label="多轮对话" valuePropName="checked">
          <Switch checkedChildren="多轮" unCheckedChildren="单轮" />
        </Form.Item>

        {multiTurn && (
          <Form.List name="messages">
            {(fields, { add, remove }) => (
              <Space direction="vertical" style={{ width: '100%', marginBottom: 16 }}>
                <Typography.Text type="secondary">历史消息（早 → 晚），供多轮改写判定使用</Typography.Text>
                {fields.map((field) => (
                  <Space key={field.key} align="baseline" style={{ width: '100%' }} wrap>
                    <Form.Item name={[field.name, 'role']} initialValue="user" noStyle>
                      <Select
                        style={{ width: 110 }}
                        options={[
                          { label: 'user', value: 'user' },
                          { label: 'assistant', value: 'assistant' },
                          { label: 'system', value: 'system' },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item name={[field.name, 'content']} noStyle rules={[{ required: true, message: '请输入内容' }]}>
                      <Input placeholder="消息内容" style={{ width: 400 }} />
                    </Form.Item>
                    <MinusCircleOutlined onClick={() => remove(field.name)} />
                  </Space>
                ))}
                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ role: 'user', content: '' })}>
                  添加历史消息
                </Button>
              </Space>
            )}
          </Form.List>
        )}

        <Form.Item name="anchor_type" label="锚定类型" rules={[{ required: true }]}>
          <Radio.Group
            optionType="button"
            options={[
              { label: metaOf(ANCHOR_TYPE_META, 'SPAN').label, value: 'SPAN' },
              { label: metaOf(ANCHOR_TYPE_META, 'DOCUMENT').label, value: 'DOCUMENT' },
            ]}
          />
        </Form.Item>

        <Typography.Title level={5}>证据</Typography.Title>
        <Form.List name="evidences">
          {(fields, { add, remove }) => (
            <Space direction="vertical" style={{ width: '100%', marginBottom: 16 }}>
              {fields.map((field) => (
                <Space key={field.key} align="start" style={{ width: '100%' }} wrap>
                  <Form.Item
                    name={[field.name, 'doc_id']}
                    rules={[{ required: true, message: '请选择文档' }]}
                    style={{ marginBottom: 8, width: 220 }}
                  >
                    <Select
                      placeholder="选择文档"
                      showSearch
                      optionFilterProp="label"
                      options={docs.map((doc) => ({ label: doc.file_name, value: doc.doc_id }))}
                    />
                  </Form.Item>
                  {anchorType === 'SPAN' && (
                    <Form.Item
                      name={[field.name, 'span']}
                      rules={[{ required: true, message: '请输入证据原文' }]}
                      style={{ marginBottom: 8, width: 360 }}
                    >
                      <TextArea placeholder="粘贴/摘录该证据在文档中的原文片段" autoSize={{ minRows: 1, maxRows: 4 }} />
                    </Form.Item>
                  )}
                  <MinusCircleOutlined onClick={() => remove(field.name)} style={{ marginTop: 10 }} />
                </Space>
              ))}
              <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ ...EMPTY_EVIDENCE })}>
                添加证据
              </Button>
            </Space>
          )}
        </Form.List>

        <Form.Item name="expected_answer" label="期望答案（答案评测时作为参考答案）">
          <TextArea rows={2} placeholder="普通问答 case 建议填写；期望拒答时可留空" />
        </Form.Item>
        <Form.Item
          name="expected_refusal"
          label="期望拒答（资料不足时，正确行为是明确拒绝回答）"
          valuePropName="checked"
        >
          <Switch />
        </Form.Item>
        <Form.Item name="note" label="备注">
          <Input placeholder="选填" maxLength={256} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
