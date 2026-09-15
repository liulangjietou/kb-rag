import { Alert, Button, Form, Input, Radio, Space, Switch, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import RequiredSelect from '../../../components/RequiredSelect';
import { getAppVersion, listApps, listAppVersions } from '../../../api/app';
import { listDocuments } from '../../../api/document';
import { listEvalDatasets } from '../../../api/evalDataset';
import type { QualityCorrection, QualityIssue } from '../../../api/qualityIssue';
import { qualityFailure } from '../../../api/qualityIssue';
import type { AppVersion, EvalDataset, KbApp, KbDocument } from '../../../api/types';
import { resolveKbRefs } from '../../../utils/kbRefs';
import { QUALITY_REASONS } from './qualityMeta';

type Values = QualityCorrection & { app_id?: string };

/** 纠正草稿独立于后台详情刷新；只有明确载入最新内容时才重新挂载此表单。 */
export default function QualityCorrectionForm({ issue, blocked, busy, onSave, onCancel, onDirty, onRestricted }: {
  issue: QualityIssue; blocked: boolean; busy: boolean; onSave: (input: QualityCorrection) => Promise<void>;
  onCancel: () => void; onDirty: () => void; onRestricted: () => void;
}) {
  const [initial] = useState(issue);
  const [form] = Form.useForm<Values>();
  const [datasets, setDatasets] = useState<EvalDataset[]>([]);
  const [apps, setApps] = useState<KbApp[]>([]);
  const [versions, setVersions] = useState<AppVersion[]>([]);
  const [docs, setDocs] = useState<KbDocument[]>([]);
  const [docPage, setDocPage] = useState(1);
  const [docTotal, setDocTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [docsLoading, setDocsLoading] = useState(false);
  const [loadError, setLoadError] = useState<string>();
  const [reload, setReload] = useState(0);
  const alive = useRef(true);
  const appId = Form.useWatch('app_id', form);
  const refusal = Form.useWatch(['input', 'expected_refusal'], form) ?? false;
  const anchorType = Form.useWatch(['input', 'anchor_type'], form) ?? 'SPAN';

  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    let active = true;
    setLoading(true); setLoadError(undefined);
    const load = async () => {
      try {
        const [datasetOptions, appOptions, documents, originalVersion] = await Promise.all([
          listEvalDatasets(initial.kb_id), listApps(), listDocuments(initial.kb_id, { size: 50, page: 1, process_status: 'INDEXED' }),
          initial.affected_app_version_id ? getAppVersion(initial.affected_app_version_id) : Promise.resolve(null),
        ]);
        if (!active) return;
        setDatasets(datasetOptions); setApps(appOptions); setDocs(documents.items); setDocTotal(documents.total); setDocPage(1);
        if (originalVersion && !form.getFieldValue('app_id')) form.setFieldsValue({ app_id: originalVersion.app_id });
      } catch (failure) { if (active) {
        const problem = qualityFailure(failure); setLoadError(problem.message); if (problem.restricted) onRestricted();
      } }
      finally { if (active) setLoading(false); }
    };
    void load();
    return () => { active = false; };
  }, [initial, form, reload, onRestricted]);

  useEffect(() => {
    let active = true;
    setVersions([]);
    if (!appId) return;
    setVersionsLoading(true);
    listAppVersions(appId).then((items) => {
      if (active) setVersions(items.filter((version) => resolveKbRefs(version.config).some((ref) => ref.kb_id === initial.kb_id)));
    }).catch((failure) => { if (active) {
      const problem = qualityFailure(failure); setLoadError(problem.message); if (problem.restricted) onRestricted();
    } })
      .finally(() => { if (active) setVersionsLoading(false); });
    return () => { active = false; };
  }, [appId, initial.kb_id, reload, onRestricted]);

  const moreDocs = async () => {
    if (docsLoading) return;
    setDocsLoading(true);
    try {
      const result = await listDocuments(initial.kb_id, { size: 50, page: docPage + 1, process_status: 'INDEXED' });
      if (alive.current) {
        setDocs((previous) => [...new Map([...previous, ...result.items].map((doc) => [doc.doc_id, doc])).values()]);
        setDocPage(result.page); setDocTotal(result.total);
      }
    } catch (failure) { if (alive.current) {
      const problem = qualityFailure(failure); setLoadError(problem.message); if (problem.restricted) onRestricted();
    } }
    finally { if (alive.current) setDocsLoading(false); }
  };

  const saved = initial.correction;
  const initialValues: Values = {
    revision: initial.revision, case_revision: initial.case_revision, reason: initial.reason ?? 'MISSING_KNOWLEDGE',
    dataset_id: initial.dataset_id ?? '', affected_app_version_id: initial.affected_app_version_id ?? '',
    input: { query: saved?.query ?? '', messages: saved?.messages ?? [], expected_answer: saved?.expected_answer ?? undefined,
      expected_refusal: saved?.expected_refusal ?? false, anchor_type: saved?.anchor_type ?? 'SPAN',
      evidences: saved?.evidences.map(({ doc_id, span }) => ({ doc_id, span: span ?? undefined })) ?? [], note: '' },
  };
  const disabled = blocked || busy || loading || !!loadError;
  const submit = async (values: Values) => {
    const input = values.input;
    await onSave({ revision: initial.revision, case_revision: initial.case_revision, reason: values.reason,
      dataset_id: values.dataset_id, affected_app_version_id: values.affected_app_version_id,
      input: { ...input, note: input.note.trim(), expected_refusal: input.expected_refusal,
        evidences: input.evidences.map(({ doc_id, span }) => ({ doc_id, span: input.anchor_type === 'SPAN' ? span : undefined })) } });
  };

  return <div className="quality-correction">
    <Typography.Title level={4}>确认问题的正确处理方式</Typography.Title>
    <p className="quality-muted">填写可重复验证的问题和标准。保存后进入待回归，当前问题不会立即标为解决。</p>
    {loadError && <Alert type="error" showIcon message={loadError} action={<Button onClick={() => setReload((value) => value + 1)}>重新加载选项</Button>} />}
    <Form form={form} layout="vertical" initialValues={initialValues} disabled={disabled} onValuesChange={onDirty} onFinish={(values) => void submit(values)}>
      <div className="quality-form-grid">
        <Form.Item name="reason" label="问题原因" rules={[{ required: true }]}>
          <RequiredSelect aria-label="问题原因" options={Object.entries(QUALITY_REASONS).map(([value, label]) => ({ value, label }))} />
        </Form.Item>
        <Form.Item name="dataset_id" label="保存到评测集" rules={[{ required: true, message: '请选择评测集' }]}>
          <RequiredSelect aria-label="保存到评测集" loading={loading} disabled={disabled || !!initial.dataset_id} placeholder="选择当前知识库的评测集"
            options={datasets.map((dataset) => ({ value: dataset.dataset_id, label: dataset.name }))} />
        </Form.Item>
        <Form.Item name="app_id" label="受影响应用" rules={[{ required: true, message: '请选择应用' }]}>
          <RequiredSelect aria-label="受影响应用" loading={loading} disabled={disabled || !!initial.affected_app_version_id} placeholder="选择应用"
            options={apps.map((app) => ({ value: app.app_id, label: app.name }))} onChange={() => form.setFieldsValue({ affected_app_version_id: undefined })} />
        </Form.Item>
        <Form.Item name="affected_app_version_id" label="发生问题的版本" rules={[{ required: true, message: '请选择引用当前知识库的应用版本' }]}>
          <RequiredSelect aria-label="发生问题的版本" loading={versionsLoading} disabled={disabled || !appId || versionsLoading} placeholder="选择应用版本"
            options={versions.map((version) => ({ value: version.app_version_id, label: version.version }))}
            notFoundContent={appId ? '该应用暂无引用此知识库的版本' : '请先选择应用'} />
        </Form.Item>
      </div>
      <Form.Item name={['input', 'query']} label="用于回归的完整问题" rules={[{ required: true, whitespace: true, message: '请输入完整问题' }]}>
        <Input.TextArea maxLength={4096} showCount autoSize={{ minRows: 2, maxRows: 5 }} placeholder="请人工核实问题，不要把脱敏摘要直接当成完整测试问题" />
      </Form.Item>
      <Form.Item name={['input', 'expected_refusal']} label="正确行为应为拒答" valuePropName="checked">
        <Switch checkedChildren="拒答" unCheckedChildren="回答" />
      </Form.Item>
      <Form.Item name={['input', 'expected_answer']} label={refusal ? '拒答标准说明（可选）' : '标准答案'}
        rules={[{ required: !refusal, whitespace: !refusal, message: '请填写用于核验的标准答案' }]}>
        <Input.TextArea maxLength={8192} showCount autoSize={{ minRows: 3, maxRows: 8 }} />
      </Form.Item>
      <Form.Item name={['input', 'anchor_type']} label="证据范围">
        <Radio.Group options={[{ label: '具体原文片段', value: 'SPAN' }, { label: '完整文档', value: 'DOCUMENT' }]} />
      </Form.Item>
      <Form.List name={['input', 'evidences']} rules={[{ validator: async (_, evidences) => {
        if (!form.getFieldValue(['input', 'expected_refusal']) && !evidences?.length) throw new Error('正常回答至少需要一条正确证据');
      } }]}>
        {(fields, { add, remove }, { errors }) => <div className="quality-evidence-editor">
          {fields.map((field, index) => <div className="quality-evidence-row" key={field.key}>
            <Form.Item name={[field.name, 'doc_id']} label={`证据文档 ${index + 1}`} rules={[{ required: true, message: '请选择证据文档' }]}>
              <RequiredSelect aria-label={`证据文档 ${index + 1}`} showSearch optionFilterProp="label" placeholder="选择已索引的文档"
                options={docs.map((doc) => ({ label: doc.file_name, value: doc.doc_id }))} />
            </Form.Item>
            {anchorType === 'SPAN' && <Form.Item name={[field.name, 'span']} label="正确证据原文" rules={[{ required: true, whitespace: true, message: '请填写原文片段' }]}>
              <Input.TextArea maxLength={8192} autoSize={{ minRows: 2, maxRows: 5 }} />
            </Form.Item>}
            <Button danger disabled={disabled} onClick={() => remove(field.name)} aria-label={`移除证据 ${index + 1}`}>移除</Button>
          </div>)}
          {refusal && fields.length === 0 && <p className="quality-muted">无依据拒答可保持证据为空。</p>}
          <Space wrap>
            <Button disabled={disabled || fields.length >= 20} onClick={() => add({ doc_id: '', span: '' })}>添加正确证据</Button>
            {docs.length < docTotal && <Button loading={docsLoading} disabled={disabled} onClick={() => void moreDocs()}>加载更多文档（已载入 {docs.length}/{docTotal}）</Button>}
          </Space>
          <Form.ErrorList errors={errors} />
        </div>}
      </Form.List>
      <details className="quality-history-editor"><summary>多轮问题的历史消息</summary>
        <Form.List name={['input', 'messages']}>
          {(fields, { add, remove }) => <>
            {fields.map((field, index) => <div className="quality-evidence-row" key={field.key}>
              <Form.Item name={[field.name, 'role']} label={`消息 ${index + 1} 的角色`} rules={[{ required: true }, { pattern: /^(user|assistant)$/, message: '请选择用户或助手' }]}>
                <RequiredSelect aria-label={`消息 ${index + 1} 的角色`} options={[{ label: '用户', value: 'user' }, { label: '助手', value: 'assistant' }]} />
              </Form.Item>
              <Form.Item name={[field.name, 'content']} label="消息内容" rules={[{ required: true, whitespace: true }]}><Input.TextArea maxLength={4096} /></Form.Item>
              <Button disabled={disabled} onClick={() => remove(field.name)} aria-label={`移除历史消息 ${index + 1}`}>移除消息</Button>
            </div>)}
            <Button disabled={disabled || fields.length >= 20} onClick={() => add({ role: 'user', content: '' })}>添加历史消息</Button>
          </>}
        </Form.List>
      </details>
      <Form.Item name={['input', 'note']} label="纠正说明" rules={[{ required: true, whitespace: true, message: '请说明纠正依据和处理内容' }]}>
        <Input.TextArea maxLength={1024} showCount autoSize={{ minRows: 2, maxRows: 5 }} />
      </Form.Item>
      <Space wrap><Button type="primary" htmlType="submit" loading={busy} disabled={disabled || versionsLoading}>保存纠正用例</Button>
        <Button disabled={busy} onClick={onCancel}>取消编辑</Button></Space>
    </Form>
  </div>;
}
