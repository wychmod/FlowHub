package com.example.flowhub.export.service;

import java.nio.file.Path;

/**
 * 可下载文件解析结果：受控绝对路径 + 登记大小 + 展示文件名，供控制器构造下载响应。
 */
public record DownloadableExportFile(Path absolutePath, long sizeBytes, String displayName) {
}
