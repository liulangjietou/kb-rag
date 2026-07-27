// Author: owlzhangfq@gmail.com
import { PlusOutlined } from '@ant-design/icons';
import { Button, Image, Space, Typography, Upload, message } from 'antd';
import { formatFileSize } from '../utils/format';

/**
 * M9-CONTRACTS.md section 0.6: client-side mirror of the server's image-query limits, used only
 * for a front-loaded hint -- the authoritative check (count/per-image/total bytes, INVALID_PARAM on
 * breach) lives on the server per kb.retrieval.image-query-max-count/-max-bytes. Kept as plain
 * literals rather than reading server config: this page has no endpoint that exposes those two
 * config values, and hard-coding the same defaults the contract documents is the established
 * pattern for other client-side pre-checks in this codebase.
 */
export const IMAGE_QUERY_MAX_COUNT = 3;
const IMAGE_QUERY_MAX_BYTES = 5 * 1024 * 1024;

/** One image the operator picked, kept as a data URL (`data:image/...;base64,xxx`) for thumbnail preview. */
export interface PickedImage {
  uid: string;
  name: string;
  dataUrl: string;
}

interface ImagePickerProps {
  value: PickedImage[];
  onChange: (images: PickedImage[]) => void;
  disabled?: boolean;
}

/**
 * Image-query picker shared by ChatDebugPage and ApiDebugTab (M9-CONTRACTS.md section 0.6): turns
 * up to IMAGE_QUERY_MAX_COUNT local files into base64 data URLs (no upload endpoint involved -- the
 * bytes only ever leave the browser inside the search/chat request body's `images` field) and
 * renders their thumbnails. Count/size limits here are a front-loaded UX hint only; the server is
 * the sole authority and returns INVALID_PARAM on breach regardless of what this component allows
 * through.
 */
export default function ImagePicker({ value, onChange, disabled }: ImagePickerProps) {
  const handleBeforeUpload = (file: File): boolean => {
    if (value.length >= IMAGE_QUERY_MAX_COUNT) {
      message.warning(`最多选择 ${IMAGE_QUERY_MAX_COUNT} 张图片`);
      return false;
    }
    if (file.size > IMAGE_QUERY_MAX_BYTES) {
      message.warning(
        `单张图片建议不超过 ${formatFileSize(IMAGE_QUERY_MAX_BYTES)}（最终校验以服务端为准），当前 ${formatFileSize(file.size)}`,
      );
    }
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = reader.result;
      if (typeof dataUrl !== 'string') {
        return;
      }
      onChange([...value, { uid: `${Date.now()}-${file.name}`, name: file.name, dataUrl }]);
    };
    reader.readAsDataURL(file);
    return false;
  };

  return (
    <Space direction="vertical" size={4}>
      <Space wrap align="start">
        {value.map((img) => (
          <div key={img.uid} style={{ position: 'relative' }}>
            <Image src={img.dataUrl} width={64} height={64} style={{ objectFit: 'cover', borderRadius: 4 }} />
            <Button
              size="small"
              danger
              type="text"
              disabled={disabled}
              style={{ position: 'absolute', top: -10, right: -10, minWidth: 20, height: 20, padding: 0, lineHeight: '20px' }}
              onClick={() => onChange(value.filter((item) => item.uid !== img.uid))}
            >
              ×
            </Button>
          </div>
        ))}
        {value.length < IMAGE_QUERY_MAX_COUNT && (
          <Upload beforeUpload={handleBeforeUpload} showUploadList={false} accept="image/*" disabled={disabled}>
            <Button icon={<PlusOutlined />} disabled={disabled}>
              选图
            </Button>
          </Upload>
        )}
      </Space>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        最多 {IMAGE_QUERY_MAX_COUNT} 张，单张建议 ≤{formatFileSize(IMAGE_QUERY_MAX_BYTES)}（前置提示，权威校验在服务端）
      </Typography.Text>
    </Space>
  );
}

/** Strips the `data:image/...;base64,` prefix so only the raw base64 payload goes in the request's `images` field. */
export function toImagesPayload(images: PickedImage[]): string[] | undefined {
  if (images.length === 0) {
    return undefined;
  }
  return images.map((img) => img.dataUrl.slice(img.dataUrl.indexOf(',') + 1));
}
