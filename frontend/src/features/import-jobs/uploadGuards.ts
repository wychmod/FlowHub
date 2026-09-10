import { MAX_UPLOAD_BYTES } from '../../api/importApi';

/** 预检结论：ok=false 时给出原因枚举与中文提示。 */
export interface UploadGuardResult {
  ok: boolean;
  reason?: 'FORMAT' | 'TOO_LARGE';
  message?: string;
}

/**
 * 上传本地预检（spec F3 / AC3）：仅 `.xlsx`、≤10MB。
 * 只为省一次往返的软拦截，最终裁决以后端受理接口的 400 为准。后缀判定大小写不敏感。
 */
export function checkUploadFile(file: { name: string; size: number }): UploadGuardResult {
  if (!/\.xlsx$/i.test(file.name)) {
    return { ok: false, reason: 'FORMAT', message: '仅支持 .xlsx 格式（请用导出模板填写后上传）' };
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return { ok: false, reason: 'TOO_LARGE', message: '文件超过 10MB 上限' };
  }
  return { ok: true };
}
