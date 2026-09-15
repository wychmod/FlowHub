import { afterEach, describe, expect, it, vi } from 'vitest';
import { filenameFromDisposition, parseBlobError, saveBlob } from './download';

/** 构造下载失败场景的最小 Response 桩：parseBlobError 只消费 status/blob。 */
function stubBlobResponse(status: number, bodyText: string): Response {
  return {
    ok: false,
    status,
    blob: async () => ({ text: async () => bodyText }),
  } as unknown as Response;
}

/** node 测试环境无 DOM：stub document/window 供 saveBlob 使用，并回收断言句柄。 */
function stubDownloadDom() {
  const anchors: { tag: string; href?: string; download?: string; click: ReturnType<typeof vi.fn> }[] =
    [];
  const appendChild = vi.fn();
  const removeChild = vi.fn();
  vi.stubGlobal('document', {
    createElement: (tag: string) => {
      const a = { tag, click: vi.fn() };
      anchors.push(a);
      return a;
    },
    body: { appendChild, removeChild },
  });
  const url = { createObjectURL: vi.fn(() => 'blob:mock-url'), revokeObjectURL: vi.fn() };
  vi.stubGlobal('window', { URL: url });
  return { anchors, appendChild, removeChild, url };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('parseBlobError', () => {
  // 契约：parseBlobError 返回 ApiError，由调用方 throw
  it('失败体为 JSON 错误 Envelope 时返回 ApiError（保留 code/trace_id/status）', async () => {
    const response = stubBlobResponse(
      410,
      JSON.stringify({ code: 'EXPORT_FILE_EXPIRED', message: '导出文件已过期', data: {}, trace_id: 'xyz-456' }),
    );
    await expect(parseBlobError(response)).resolves.toMatchObject({
      code: 'EXPORT_FILE_EXPIRED',
      message: '导出文件已过期',
      status: 410,
      traceId: 'xyz-456',
    });
  });

  it('失败体为 JSON 但缺 code 字段时降级为普通文本错误', async () => {
    const response = stubBlobResponse(502, JSON.stringify({ error: 'Bad Gateway' }));
    await expect(parseBlobError(response)).resolves.toMatchObject({
      message: expect.stringContaining('Bad Gateway'),
      status: 502,
    });
  });

  it('失败体为纯文本/HTML 时保留正文摘要', async () => {
    const response = stubBlobResponse(502, '<html>502 Bad Gateway</html>');
    await expect(parseBlobError(response)).resolves.toMatchObject({
      message: expect.stringContaining('502 Bad Gateway'),
      status: 502,
    });
  });

  it('超长文本正文截断至 200 字符', async () => {
    const response = stubBlobResponse(500, 'x'.repeat(500));
    const error = await parseBlobError(response);
    expect(error.message.length).toBeLessThanOrEqual('下载失败（HTTP 500）：'.length + 200);
  });

  it('失败体为空时仅保留 HTTP 状态文案', async () => {
    const response = stubBlobResponse(503, '   ');
    await expect(parseBlobError(response)).resolves.toMatchObject({ message: '下载失败（HTTP 503）' });
  });
});

describe('filenameFromDisposition', () => {
  it("filename*=UTF-8'' 优先且解码中文文件名", () => {
    expect(filenameFromDisposition(`attachment; filename*=UTF-8''%E8%AE%A2%E5%8D%95.xlsx`)).toBe(
      '订单.xlsx',
    );
  });

  it("filename* 与 filename 并存时优先 filename*", () => {
    expect(
      filenameFromDisposition(`attachment; filename="fallback.xlsx"; filename*=UTF-8''%E8%AE%A2%E5%8D%95.xlsx`),
    ).toBe('订单.xlsx');
  });

  it('无 filename* 时解析带引号的 filename', () => {
    expect(filenameFromDisposition('attachment; filename="orders.xlsx"')).toBe('orders.xlsx');
  });

  it('头缺失或不含文件名时返回 null', () => {
    expect(filenameFromDisposition(null)).toBeNull();
    expect(filenameFromDisposition('attachment')).toBeNull();
  });

  it("filename* 百分号解码失败时返回 null，交由调用方兜底", () => {
    expect(filenameFromDisposition(`attachment; filename*=UTF-8''%ZZ.xlsx`)).toBeNull();
  });
});

describe('saveBlob', () => {
  it('创建临时 a 标签触发下载，并在完成后释放 Object URL', () => {
    const { anchors, appendChild, removeChild, url } = stubDownloadDom();
    const blob = new Blob(['excel-bytes']);

    saveBlob(blob, '订单.xlsx');

    expect(url.createObjectURL).toHaveBeenCalledWith(blob);
    const a = anchors[0];
    expect(a.tag).toBe('a');
    expect(a.href).toBe('blob:mock-url');
    expect(a.download).toBe('订单.xlsx');
    expect(a.click).toHaveBeenCalledTimes(1);
    expect(appendChild).toHaveBeenCalledWith(a);
    expect(removeChild).toHaveBeenCalledWith(a);
    expect(url.revokeObjectURL).toHaveBeenCalledWith('blob:mock-url');
  });
});
