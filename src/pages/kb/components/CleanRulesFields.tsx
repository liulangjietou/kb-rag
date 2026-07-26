// Author: owlzhangfq@gmail.com
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Form, Input, Space, Switch, Typography } from 'antd';
import type { FormInstance } from 'antd';

interface CleanRulesFieldsProps {
  form: FormInstance;
  /**
   * Field name path prefix: [] when clean_rules IS the form root (the per-document "改规则重解析"
   * override form), or ['clean_rules'] when nested under a parent config form (the KB-level index
   * config drawer). Kept generic so the watermark/regex list editors and desensitize toggle group
   * are written once and shared by both call sites instead of duplicated.
   */
  namePath: Array<string | number>;
}

/** Builds a full field name path by appending suffix segments to the shared prefix. */
function fieldPath(namePath: Array<string | number>, ...suffix: Array<string | number>): Array<string | number> {
  return [...namePath, ...suffix];
}

/**
 * Shared clean_rules form fragment (M3-CONTRACTS.md section 3.3/4): header/footer strip switch,
 * watermark regex list, regex-replacement list (pattern/replacement), Excel header-join switch,
 * and the four desensitize toggles under one master switch. extract_metadata is carried through
 * as a hidden field -- the M3 web deliverable's clean-rules group does not list a control for it,
 * so it must round-trip unchanged rather than silently reset to its default on every save.
 */
export default function CleanRulesFields({ form, namePath }: CleanRulesFieldsProps) {
  const desensitizeEnabledPath = fieldPath(namePath, 'desensitize', 'enabled');
  const desensitizeEnabled = Form.useWatch(desensitizeEnabledPath, form) ?? false;

  return (
    <>
      <Form.Item name={fieldPath(namePath, 'strip_header_footer')} label="去除页眉页脚" valuePropName="checked">
        <Switch />
      </Form.Item>
      <Form.Item
        name={fieldPath(namePath, 'excel_header_join')}
        label="Excel 表头拼接"
        valuePropName="checked"
        tooltip="切分时把表头拼接到每个数据分片前，保留列语义"
      >
        <Switch />
      </Form.Item>

      <Typography.Text strong>水印正则列表</Typography.Text>
      <Form.List name={fieldPath(namePath, 'strip_watermark_patterns')}>
        {(fields, { add, remove }) => (
          <div style={{ marginTop: 8, marginBottom: 16 }}>
            {fields.map((field) => (
              <Space key={field.key} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
                <Form.Item
                  {...field}
                  rules={[{ required: true, message: '请输入正则或删除该行' }]}
                  style={{ flex: 1, marginBottom: 0 }}
                >
                  <Input placeholder="水印正则表达式" />
                </Form.Item>
                <MinusCircleOutlined onClick={() => remove(field.name)} />
              </Space>
            ))}
            <Button type="dashed" onClick={() => add('')} block icon={<PlusOutlined />}>
              新增水印正则
            </Button>
          </div>
        )}
      </Form.List>

      <Typography.Text strong>正则替换列表</Typography.Text>
      <Form.List name={fieldPath(namePath, 'regex_replacements')}>
        {(fields, { add, remove }) => (
          <div style={{ marginTop: 8, marginBottom: 16 }}>
            {fields.map((field) => (
              <Space key={field.key} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
                <Form.Item
                  name={[field.name, 'pattern']}
                  rules={[{ required: true, message: '请输入匹配正则' }]}
                  style={{ flex: 1, marginBottom: 0 }}
                >
                  <Input placeholder="匹配正则 pattern" />
                </Form.Item>
                <Form.Item name={[field.name, 'replacement']} style={{ flex: 1, marginBottom: 0 }}>
                  <Input placeholder="替换为 replacement" />
                </Form.Item>
                <MinusCircleOutlined onClick={() => remove(field.name)} />
              </Space>
            ))}
            <Button type="dashed" onClick={() => add({ pattern: '', replacement: '' })} block icon={<PlusOutlined />}>
              新增正则替换
            </Button>
          </div>
        )}
      </Form.List>

      <Form.Item name={desensitizeEnabledPath} label="启用脱敏" valuePropName="checked">
        <Switch />
      </Form.Item>
      {desensitizeEnabled && (
        <Space direction="vertical" style={{ marginBottom: 16 }}>
          <Form.Item
            name={fieldPath(namePath, 'desensitize', 'phone')}
            label="手机号"
            valuePropName="checked"
            style={{ marginBottom: 4 }}
          >
            <Switch checkedChildren="脱敏" unCheckedChildren="保留" />
          </Form.Item>
          <Form.Item
            name={fieldPath(namePath, 'desensitize', 'id_card')}
            label="身份证号"
            valuePropName="checked"
            style={{ marginBottom: 4 }}
          >
            <Switch checkedChildren="脱敏" unCheckedChildren="保留" />
          </Form.Item>
          <Form.Item
            name={fieldPath(namePath, 'desensitize', 'bank_card')}
            label="银行卡号"
            valuePropName="checked"
            style={{ marginBottom: 4 }}
          >
            <Switch checkedChildren="脱敏" unCheckedChildren="保留" />
          </Form.Item>
          <Form.Item
            name={fieldPath(namePath, 'desensitize', 'email')}
            label="邮箱"
            valuePropName="checked"
            style={{ marginBottom: 0 }}
          >
            <Switch checkedChildren="脱敏" unCheckedChildren="保留" />
          </Form.Item>
        </Space>
      )}

      {/* Not exposed in the UI per the M3 web deliverable scope; round-tripped unchanged. */}
      <Form.Item name={fieldPath(namePath, 'extract_metadata')} hidden>
        <Input />
      </Form.Item>
    </>
  );
}
