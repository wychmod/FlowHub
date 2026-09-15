import { DownloadOutlined, ImportOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { Alert, App as AntdApp, Button, Modal, Space, Typography, Upload } from 'antd';
import { useEffect, useState } from 'react';
import {
  downloadImportTemplate,
  uploadOrderImport,
  type ImportJobAccepted,
} from '../../../api/importApi';
import { ApiError } from '../../../api/http';
import type { PageKey } from '../../../app/AppLayout';
import { checkUploadFile } from '../../import-jobs/uploadGuards';

interface ImportModalProps {
  open: boolean;
  onCancel: () => void;
  /** 受理成功后「查看导入任务」跳转（App 传入 setPage）。 */
  onNavigate: (key: PageKey) => void;
}

/** 统一错误文案：ApiError 带 trace_id。 */
function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return `${error.message}${error.traceId ? `（trace_id: ${error.traceId}）` : ''}`;
  }
  return error instanceof Error ? error.message : '未知错误';
}

/**
 * 导入订单弹窗（上传入口）：
 * 模板下载 + Upload.Dragger（本地预检 .xlsx/≤10MB）+ 手动「开始导入」受理。
 * 受理成功展示识别行数并提供跳转；失败弹窗不关闭、可重传（错误含 trace_id）。
 */
export function ImportModal({ open, onCancel, onNavigate }: ImportModalProps) {
  const { message } = AntdApp.useApp();
  const [file, setFile] = useState<File | null>(null);
  const [accepted, setAccepted] = useState<ImportJobAccepted | null>(null);

  // 每次打开重置本地态，不残留上一次的文件与结果
  useEffect(() => {
    if (open) {
      setFile(null);
      setAccepted(null);
    }
  }, [open]);

  const upload = useMutation<ImportJobAccepted, ApiError, File>({
    mutationFn: (target) => uploadOrderImport(target),
    onSuccess: (job) => setAccepted(job),
  });

  async function handleTemplateDownload() {
    try {
      await downloadImportTemplate();
    } catch (error) {
      message.error(errorMessage(error));
    }
  }

  /** beforeUpload：预检不通过就地提示并不入队；通过则暂存待用户点「开始导入」。 */
  function handleBeforeUpload(next: File): boolean {
    const guard = checkUploadFile({ name: next.name, size: next.size });
    if (!guard.ok) {
      message.warning(guard.message ?? '文件格式或大小不符合要求');
      return false;
    }
    setAccepted(null);
    setFile(next);
    return false;
  }

  function handleStart() {
    if (!file) {
      message.warning('请先选择要上传的文件');
      return;
    }
    upload.mutate(file);
  }

  return (
    <Modal
      title={
        <Space size={8}>
          <ImportOutlined />
          导入订单
        </Space>
      }
      open={open}
      mask={{ closable: false }}
      keyboard={false}
      onCancel={onCancel}
      footer={
        accepted
          ? [
            <Button key="stay" onClick={onCancel}>留在本页</Button>,
            <Button key="view" type="primary" icon={<DownloadOutlined />} onClick={() => onNavigate('import-jobs')}>
              查看导入任务
            </Button>,
          ]
          : [
            <Button key="cancel" onClick={onCancel}>取消</Button>,
            <Button
              key="start"
              type="primary"
              loading={upload.isPending}
              disabled={!file}
              onClick={handleStart}
            >
              开始导入
            </Button>,
          ]
      }
    >
      <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        <Typography.Text type="secondary">
          仅支持由导出模板填写的 .xlsx（表头 9 列、≤10MB）。可先
          <Typography.Link onClick={() => void handleTemplateDownload()}>
            下载导入模板
          </Typography.Link>
          。
        </Typography.Text>

        {accepted ? (
          <Alert
            type="success"
            showIcon
            title="导入任务已创建"
            description={`任务编号 ${accepted.job_no}，识别到 ${accepted.total_rows} 行数据，正在后台校验导入。`}
          />
        ) : (
          <>
            {upload.error ? (
              <Alert
                type="error"
                showIcon
                title="导入受理失败"
                description={errorMessage(upload.error)}
              />
            ) : null}
            <Upload.Dragger
              accept=".xlsx"
              maxCount={1}
              showUploadList={false}
              beforeUpload={handleBeforeUpload}
            >
              <p style={{ margin: '12px 0' }}>
                {file ? `已选择：${file.name}` : '点击或拖拽 .xlsx 文件到此处'}
              </p>
              <p style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)', margin: 0 }}>
                文件级/结构级校验同步秒回，行级校验异步执行
              </p>
            </Upload.Dragger>
          </>
        )}
      </Space>
    </Modal>
  );
}
