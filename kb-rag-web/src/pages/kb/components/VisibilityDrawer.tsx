// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { Alert, Button, Drawer, Form, Radio, Select, Space, message } from 'antd';
import { getDocumentVisibility, updateDocumentVisibility } from '../../../api/document';
import { listRoles } from '../../../api/role';
import type { DocumentVisibility, KbDocument, RoleSummary } from '../../../api/types';

interface VisibilityDrawerProps {
  kbId: string;
  /** Document being edited; null keeps the drawer closed. */
  doc: KbDocument | null;
  onClose: () => void;
  /** Fired after a successful save so the list can refresh its 受限 tag. */
  onSaved: () => void;
}

interface VisibilityFormValues {
  visibility: DocumentVisibility;
  role_ids?: string[];
}

/**
 * Document visibility editor (M16-CONTRACTS.md section 4): inherit the knowledge base scope, or
 * restrict the content to named roles.
 *
 * <p>Restriction hides content, not existence: the row stays in every list (counts must not lie),
 * retrieval simply never returns the document's chunks to a session outside the granted roles, and
 * the content endpoints answer 403. That framing is spelled out on the form so an operator knows
 * what "受限" actually buys before saving it.
 */
export default function VisibilityDrawer({ kbId, doc, onClose, onSaved }: VisibilityDrawerProps) {
  const [roles, setRoles] = useState<RoleSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [visibility, setVisibility] = useState<DocumentVisibility>('INHERIT');
  const [form] = Form.useForm<VisibilityFormValues>();

  useEffect(() => {
    if (!doc) {
      return;
    }
    setLoading(true);
    // The current grant and the role catalogue load together: the multi-select is useless while
    // either half is missing, and a failed catalogue read still leaves the radio operable.
    Promise.all([
      getDocumentVisibility(kbId, doc.doc_id),
      listRoles().catch(() => [] as RoleSummary[]),
    ])
      .then(([current, catalogue]) => {
        setRoles(catalogue);
        setVisibility(current.visibility);
        form.setFieldsValue({ visibility: current.visibility, role_ids: current.role_ids });
      })
      .finally(() => setLoading(false));
  }, [kbId, doc, form]);

  const submit = async (values: VisibilityFormValues) => {
    if (!doc) {
      return;
    }
    setSubmitting(true);
    try {
      await updateDocumentVisibility(kbId, doc.doc_id, {
        visibility: values.visibility,
        // Switching back to INHERIT drops the grant server-side; sending the stale list anyway
        // would only be stored dead weight, so it is cleared here too.
        role_ids: values.visibility === 'RESTRICTED' ? values.role_ids ?? [] : [],
      });
      message.success('文档可见性已更新，检索与内容访问即刻生效');
      onSaved();
      onClose();
    } finally {
      setSubmitting(false);
    }
  };

  const roleOptions = roles.map((role) => ({ value: role.role_id, label: `${role.name}（${role.code}）` }));

  return (
    <Drawer
      open={doc !== null}
      width={480}
      title={`文档可见性 - ${doc?.file_name ?? ''}`}
      onClose={onClose}
      destroyOnClose
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={submitting} onClick={() => form.submit()}>
            保存
          </Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="受限只隐藏内容，不隐藏条目"
        description="设为受限后，文档仍出现在列表中，但只有被授权角色能查看内容与命中检索，其他账号打开时会提示无权查看。"
      />
      <Form<VisibilityFormValues>
        form={form}
        layout="vertical"
        onFinish={submit}
        preserve={false}
        disabled={loading}
      >
        <Form.Item name="visibility" label="可见范围" rules={[{ required: true, message: '请选择可见范围' }]}>
          <Radio.Group onChange={(e) => setVisibility(e.target.value as DocumentVisibility)}>
            <Radio value="INHERIT">继承知识库范围</Radio>
            <Radio value="RESTRICTED">仅限指定角色</Radio>
          </Radio.Group>
        </Form.Item>
        {visibility === 'RESTRICTED' && (
          <Form.Item
            name="role_ids"
            label="可见角色"
            rules={[{ required: true, message: '请至少选择一个角色' }]}
            extra="持有任一所选角色的账号可查看内容；未选中角色的账号仅能看到文件名等条目信息"
          >
            <Select mode="multiple" options={roleOptions} placeholder="可多选" />
          </Form.Item>
        )}
      </Form>
    </Drawer>
  );
}
