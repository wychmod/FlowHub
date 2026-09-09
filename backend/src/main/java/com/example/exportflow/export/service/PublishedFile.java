package com.example.exportflow.export.service;

import java.nio.file.Path;

/**
 * 文件发布结果：一次发布产出的三个受控字段。
 * <p>
 * relativePath 供 DB 登记（下载/清理围绕它受控解析）；sizeBytes 供展示与审计；
 * absolutePath 仅存于当前进程，供「数据库提交失败」时的补偿删除。
 */
public record PublishedFile(String relativePath, long sizeBytes, Path absolutePath) {
}
