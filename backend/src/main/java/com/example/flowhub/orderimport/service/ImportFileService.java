package com.example.flowhub.orderimport.service;

import com.example.flowhub.common.web.util.RootedPathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 导入文件服务：受控 importRoot 内的上传落盘、错误报告路径分配与受控解析。
 * <p>
 * 路径安全双层防腐与导出一致：文本层 normalize 拒绝 .. 与绝对路径；物理层逐段符号链接检查 + toRealPath 验真。
 * 目录分层按 {@code <UTC 日期>/<jobNo>/}（jobNo 在受理期已知，上传原件须先于任务落盘）。
 */
@Service
public class ImportFileService {

    private static final Logger log = LoggerFactory.getLogger(ImportFileService.class);

    /** 上传原件固定文件名（原始上传文件名仅记入 import_jobs.file_name，不进入磁盘路径避免路径注入）。 */
    private static final String UPLOAD_FILE_NAME = "upload.xlsx";
    /** 错误报告文件名模板（errors-attempt-N.xlsx，attemptNo 标识第几次尝试的报告）。 */
    private static final String ERROR_REPORT_TEMPLATE = "errors-attempt-%d.xlsx";
    /** 目录分层日期（UTC）。 */
    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    /** 错误报告命名模式（孤儿对账按它识别本任务产物）。 */
    private static final Pattern ERROR_REPORT_PATTERN = Pattern.compile("errors-attempt-(\\d+)\\.xlsx");

    private final Path importRoot;
    private final RootedPathGuard guard;

    /** 构造时提纯受控根目录，作为后续所有路径校验的唯一基准。 */
    public ImportFileService(@Value("${import.files.dir:import-files}") String configuredRoot) throws IOException {
        Path configured = Path.of(configuredRoot).toAbsolutePath().normalize();
        Files.createDirectories(configured);
        this.importRoot = configured.toRealPath();
        this.guard = new RootedPathGuard(this.importRoot);
    }

    /**
     * 受理时落盘上传原件：分配到 {@code <日期>/<jobNo>/upload.xlsx} 并写盘。
     *
     * @return 相对路径（正斜杠统一，供 import_jobs.file_path 登记）
     */
    public String persistUpload(String jobNo, MultipartFile file) throws IOException {
        Path relative = Path.of(DATE_DIR.format(Instant.now()), jobNo, UPLOAD_FILE_NAME);
        Path abs = guard.resolveWithinRoot(relative);
        guard.validateWithinRoot(relative);
        Files.createDirectories(abs.getParent());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, abs);
        }
        return importRoot.relativize(abs).toString().replace('\\', '/');
    }

    /** 错误报告目标路径（写入到最终位置，注册成功后再由 markPartial 引用；DB 失败由调用方补偿删除）。 */
    public String errorReportPath(String jobNo, int attemptNo) {
        Path relative = Path.of(DATE_DIR.format(Instant.now()), jobNo, ERROR_REPORT_TEMPLATE.formatted(attemptNo));
        return relative.toString().replace('\\', '/');
    }

    /** 解析错误报告目标为受控绝对路径（写盘前建目录由调用方/Writer 处理）。 */
    public Path resolveErrorReportAbsolute(String relativePath) {
        return resolvePersisted(relativePath);
    }

    /**
     * 受控解析 DB 登记的相对路径：文本层拒空值/绝对路径/..，物理层逐段符号链接 + toRealPath 验真。
     * 不要求文件已存在（存在性由调用方判定并映射为独立错误码）。
     */
    public Path resolvePersisted(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Persisted import path is blank");
        }
        Path parsed = Path.of(relativePath);
        if (parsed.isAbsolute() || parsed.getRoot() != null) {
            throw new IllegalArgumentException("导入持久化路径必须为相对路径: " + relativePath);
        }
        Path resolved = guard.resolveWithinRoot(parsed);
        guard.validateWithinRoot(parsed);
        return resolved;
    }

    /** 补偿/过期删除受控内文件：删除成功返回 true，失败/路径非法返回 false（调用方保持 DB 原状下一轮再试）。 */
    public boolean deletePersisted(Path absolutePath) {
        try {
            guard.requireWithinRoot(absolutePath);
            Files.deleteIfExists(absolutePath);
            return true;
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("import_file_delete_failed path={} reason={}", absolutePath, ex.toString());
            return false;
        }
    }

    /** 删除（尽力，不抛）：文件不存在视为已清理。 */
    public void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            log.warn("import_temp_file_delete_failed path={} reason={}", file, ex.toString());
        }
    }

    /** 扫描宽限期前、位于 {@code <日期>/<jobNo>/} 的导入产物候选（upload.xlsx 或 errors-attempt-N.xlsx）。 */
    public List<ImportOrphanCandidate> orphanCandidatesOlderThan(Instant threshold) {
        List<ImportOrphanCandidate> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(importRoot)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> parseCandidate(path, threshold).ifPresent(candidates::add));
        } catch (IOException ex) {
            log.warn("import_candidate_scan_failed root={} reason={}", importRoot, ex.toString());
        }
        return candidates;
    }

    /** 逐文件过命名/结构/时间三关：命名关（upload.xlsx 或 errors-attempt-N.xlsx）→结构关（父目录为 jobNo 形态）→时间关。 */
    private Optional<ImportOrphanCandidate> parseCandidate(Path path, Instant threshold) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        boolean upload = UPLOAD_FILE_NAME.equals(fileName);
        Matcher matcher = ERROR_REPORT_PATTERN.matcher(fileName);
        if (!upload && !matcher.matches()) {
            return Optional.empty();
        }
        // 结构关：必须位于 <日期>/<jobNo>/ 下，jobNo 由「IMP + 日期 + 随机」构成，父目录非该形态不进候选
        Path jobNoDir = path.getParent();
        if (jobNoDir == null) {
            return Optional.empty();
        }
        boolean jobNoLike = jobNoDir.getFileName() != null && jobNoDir.getFileName().toString().startsWith("IMP");
        if (!jobNoLike) {
            return Optional.empty();
        }
        try {
            Instant modifiedAt = Files.getLastModifiedTime(path).toInstant();
            if (modifiedAt.isAfter(threshold)) {
                return Optional.empty();
            }
            String relativePath = importRoot.relativize(path).toString().replace('\\', '/');
            // 错误报告候选携带 attemptNo 供活跃租约检查；上传原件无 per-attempt 语义记 null
            Integer attemptNo = upload ? null : Integer.valueOf(matcher.group(1));
            return Optional.of(new ImportOrphanCandidate(path, relativePath,
                    jobNoDir.getFileName().toString(), attemptNo, modifiedAt));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    /** 孤儿对账候选：磁盘扫描产物，是否孤儿由维护服务按引用与活跃租约判定；attemptNo 仅错误报告候选有值。 */
    public record ImportOrphanCandidate(Path absolutePath, String relativePath, String jobNo,
                                        Integer attemptNo, Instant modifiedAt) {
    }
}