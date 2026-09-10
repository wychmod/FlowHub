package com.example.flowhub.export.service;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 孤儿对账候选：受控根内按 attempt-N 命名解析出的文件身份。
 * <p>
 * 候选 ≠ 可删：删除由调用方完成「时间宽限 + 活跃租约 + 数据库引用」三维校验后执行。
 */
public record OrphanCandidate(Path absolutePath, String relativePath, long jobId, int attemptNo, Instant lastModifiedAt) {
}
