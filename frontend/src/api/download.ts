/**
 * 文件下载协议工具（fe-td.md 7）。
 * 下载地址成功时是二进制文件、失败时可能是 JSON 错误包或网关文本，分流逻辑全部收敛在本层；
 * 只负责协议细节，不负责具体接口与页面展示。
 */
import { ApiError, asEnvelope, envelopeToError } from './http';

/** 文本错误正文最大保留长度，避免整页 HTML 撑爆提示框。 */
const TEXT_ERROR_MAX_LENGTH = 200;

/** 普通文本/HTML 错误体转 ApiError：保留正文摘要便于排查。 */
function textToError(text: string, status: number): ApiError {
  const trimmed = text.trim();
  const detail = trimmed ? `：${trimmed.slice(0, TEXT_ERROR_MAX_LENGTH)}` : '';
  return new ApiError(`下载失败（HTTP ${status}）${detail}`, undefined, undefined, status);
}

/**
 * 下载失败响应转 ApiError：优先按 JSON 错误 Envelope 解析；
 * 非 JSON（网关 HTML）或缺少 code 字段的 JSON 降级为普通文本错误。
 */
export async function parseBlobError(response: Response): Promise<ApiError> {
  const blob = await response.blob();
  const text = await blob.text();
  try {
    const envelope = asEnvelope(JSON.parse(text) as unknown);
    if (envelope) {
      return envelopeToError(envelope, response.status, `下载失败（HTTP ${response.status}）`);
    }
  } catch {
    // 非 JSON，走文本错误
  }
  return textToError(text, response.status);
}

/**
 * 从 Content-Disposition 解析文件名：优先 filename*=UTF-8''（可还原中文等非 ASCII 字符），
 * 其次带引号的 filename="..."；都无法解析（含百分号解码失败）返回 null 由调用方兜底。
 */
export function filenameFromDisposition(header: string | null): string | null {
  if (!header) return null;
  const starMatch = header.match(/filename\*=UTF-8''([^;]+)/i);
  if (starMatch) {
    try {
      return decodeURIComponent(starMatch[1]);
    } catch {
      return null;
    }
  }
  const match = header.match(/filename="([^"]+)"/);
  return match ? match[1] : null;
}

/** 触发浏览器保存：创建临时 <a> 模拟点击，完成后释放 Object URL。 */
export function saveBlob(blob: Blob, filename: string): void {
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.URL.revokeObjectURL(url);
}
