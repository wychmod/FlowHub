package com.example.exportflow.export.service;

import com.example.exportflow.common.web.util.RootedPathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * 导出文件服务（第 17/18 章）：受控 exportRoot 内的临时文件分配、原子发布与受控路径解析。
 * <p>
 * 路径安全双层防腐：文本层 normalize 拒绝 .. 与绝对路径；物理层逐段符号链接检查 + toRealPath 验真。
 * 写入、发布、解析、删除每个入口都重新校验——安全边界不信任「这条路径最初由服务端生成」。
 */
@Service
public class ExportFileService {

    private static final Logger log = LoggerFactory.getLogger(ExportFileService.class);

    /** 发布文件名与临时文件同号同目录（同文件系统是 ATOMIC_MOVE 的实现前提）。 */
    private static final String PUBLISHED_SUFFIX = ".xlsx";
    /** 目录分层日期（UTC）：同一天发布的文件聚在同一目录，供维护清理圈定候选范围。 */
    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    /** 尝试产物命名（attempt-N.tmp|xlsx）：孤儿对账按它解析 jobId/attemptNo 身份。 */
    private static final Pattern ATTEMPT_FILE_PATTERN = Pattern.compile("attempt-(\\d+)\\.(tmp|xlsx)");

    private final Path exportRoot;
    private final RootedPathGuard guard;

    /** 构造时提纯受控根目录，作为后续所有路径校验的唯一基准。 */
    public ExportFileService(@Value("${export.files.dir:export-files}") String configuredRoot) throws IOException {
        // exportRoot 提纯：绝对化 → 规范化 → 建目录 → 真实路径（配置里的符号链接在此现出原形）
        Path configured = Path.of(configuredRoot).toAbsolutePath().normalize();
        Files.createDirectories(configured);
        this.exportRoot = configured.toRealPath();
        this.guard = new RootedPathGuard(this.exportRoot);
    }

    /**
     * 分配业务临时文件 &lt;root&gt;/&lt;UTC日期&gt;/&lt;jobId&gt;/attempt-N.tmp：建受控目录并清理同 Attempt 残留。
     * 残留清理兼防符号链接陷阱：delete 不跟随链接，删除的是链接本身而非外部目标。
     *
     * @return 临时文件的绝对路径，执行体拿它写入 Excel，随后作为 publish 的入参
     */
    public Path temporaryPath(long jobId, int attemptNo) throws IOException {
        Path relative = Path.of(DATE_DIR.format(Instant.now()),
                String.valueOf(jobId), "attempt-" + attemptNo + ".tmp");
        Path file = guard.resolveWithinRoot(relative);
        guard.validateWithinRoot(relative);
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        return file;
    }

    /**
     * 发布（发布协议第 2 步）：Workbook 关闭后调用，ATOMIC_MOVE 将临时文件瞬间切换为 attempt-N.xlsx。
     * 不支持原子移动时原样抛出、不降级为普通复制（半可见的正式文件比失败更不可解释），由执行体收敛 FAILED 并清理。
     *
     * @return 发布结果三元组：relativePath 供 DB 登记（正斜杠相对路径）、sizeBytes 为正式文件字节大小、
     *         absolutePath 仅存当前进程，供 markSucceeded 事务失败时的补偿删除
     */
    public PublishedFile publish(Path temporary, int attemptNo) throws IOException {
        guard.requireWithinRoot(temporary);
        Path target = temporary.resolveSibling("attempt-" + attemptNo + PUBLISHED_SUFFIX);
        // DB 只存相对路径（正斜杠统一，避免平台分隔符进入持久化值）
        String relativePath = exportRoot.relativize(target.normalize()).toString().replace('\\', '/');
        // ATOMIC_MOVE 不受支持时异常原样上抛（不静默降级为普通复制），交由执行体收敛 FAILED
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return new PublishedFile(relativePath, Files.size(target), target);
    }

    /**
     * 受控解析 DB 登记的相对路径：文本层拒空值/绝对路径/..，物理层逐段符号链接 + toRealPath 验真。
     * 不要求文件已存在（存在性由下载方判定并映射为独立错误码）。
     *
     * @return exportRoot 下的目标绝对路径（此时文件可能尚未存在）
     */
    public Path resolvePersisted(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Persisted export path is blank");
        }
        Path parsed = Path.of(relativePath);
        // isAbsolute 之外的隐性逃逸：带根组件的无盘符路径（如 Windows 的 /etc/passwd）会让
        // Path.resolve 整段替换目标位置，必须一并拒绝
        if (parsed.isAbsolute() || parsed.getRoot() != null) {
            throw new IllegalArgumentException("导出持久化路径必须为相对路径: " + relativePath);
        }
        Path resolved = guard.resolveWithinRoot(parsed);
        guard.validateWithinRoot(parsed);
        return resolved;
    }

    /**
     * 补偿/清理删除已发布正式文件（执行体补偿与过期清理共用）。
     * 校验在受控 root 内后才删；删除失败仅记日志不抛出，孤儿由维护服务回收。
     *
     * @return false = 路径非法或删除失败（调用方保持数据库状态原样，下一轮再试）；文件不存在视为已清理
     */
    public boolean deletePublished(Path absolutePath) {
        try {
            guard.requireWithinRoot(absolutePath);
            Files.deleteIfExists(absolutePath);
            return true;
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("export_published_file_delete_failed path={} reason={}", absolutePath, ex.toString());
            return false;
        }
    }

    /** 扫描宽限期前的候选临时文件（&lt;日期&gt;/&lt;jobId&gt;/attempt-N.tmp）；是否孤儿由调用方做租约校验。 */
    public List<OrphanCandidate> temporaryCandidatesOlderThan(Instant threshold) {
        return scanCandidates(threshold, ".tmp");
    }

    /** 扫描宽限期前的候选正式文件（&lt;日期&gt;/&lt;jobId&gt;/attempt-N.xlsx）；是否孤儿由调用方做引用与租约校验。 */
    public List<OrphanCandidate> finalCandidatesOlderThan(Instant threshold) {
        return scanCandidates(threshold, ".xlsx");
    }

    /**
     * 遍历受控根收集命名与时间合格的候选；walk 默认不跟随目录符号链接，扫描范围天然锁定在根内。
     * 只圈定不删除——三维校验（租约/引用）由调用方完成后执行删除。
     */
    private List<OrphanCandidate> scanCandidates(Instant threshold, String suffix) {
        List<OrphanCandidate> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(exportRoot)) {
            // walk 产出目录与文件混合的流：先按类型排除，防「恰好叫 attempt-N 的目录」混入候选
            stream.filter(Files::isRegularFile)
                    .forEach(path -> parseCandidate(path, threshold, suffix).ifPresent(candidates::add));
        } catch (IOException ex) {
            // 扫描失败降级为空候选：宁可本轮漏删下一轮再扫，也不带残缺名单继续
            log.warn("export_candidate_scan_failed root={} reason={}", exportRoot, ex.toString());
        }
        return candidates;
    }

    /**
     * 逐文件过三关：命名关（attempt-N.{suffix}）→ 结构关（父目录为纯数字 jobId）→ 时间关（已过宽限期）。
     * 任何一关不过或元数据读取失败都按非候选处理，单文件异常不中断整轮扫描。
     */
    private Optional<OrphanCandidate> parseCandidate(Path path, Instant threshold, String suffix) {
        // 命名关：正则只认 attempt-N 后缀，同时提取出 attemptNo
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        Matcher matcher = ATTEMPT_FILE_PATTERN.matcher(fileName);
        if (!matcher.matches() || !fileName.endsWith(suffix)) {
            return Optional.empty();
        }
        try {
            // 结构关：必须位于 <日期>/<纯数字 jobId>/ 一层之下，外来路径与手工放置的文件不进候选
            Path jobIdDir = path.getParent();
            String jobIdSegment = jobIdDir == null ? "" : jobIdDir.getFileName().toString();
            if (!jobIdSegment.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            // 时间关：宽限期内的文件可能是活跃 Worker 正在写/刚发布的产物，一律跳过
            Instant modifiedAt = Files.getLastModifiedTime(path).toInstant();
            if (modifiedAt.isAfter(threshold)) {
                return Optional.empty();
            }
            // 相对路径统一正斜杠，与 DB 登记格式一致（引用检查按字符串比对）
            String relativePath = exportRoot.relativize(path).toString().replace('\\', '/');
            return Optional.of(new OrphanCandidate(path, relativePath,
                    Long.parseLong(jobIdSegment), Integer.parseInt(matcher.group(1)), modifiedAt));
        } catch (IOException | NumberFormatException ex) {
            // 元数据取不到（如竞态中被删除）按非候选处理：误放行的代价远高于漏删
            log.warn("export_candidate_parse_failed path={} reason={}", path, ex.toString());
            return Optional.empty();
        }
    }

    /**
     * 删除业务临时文件（失败收敛时调用）：文件不存在视为已清理，删除失败仅记日志不阻断收敛。无返回值。
     */
    public void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            log.warn("export_temp_file_delete_failed path={} reason={}", file, ex.toString());
        }
    }
}
