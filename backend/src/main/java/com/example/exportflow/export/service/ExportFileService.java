package com.example.exportflow.export.service;

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
    /** 目录分层日期（UTC）：同一天发布的文件聚在同一目录，供第 19 章清理圈定候选范围。 */
    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final Path exportRoot;

    /** 构造时提纯受控根目录，作为后续所有路径校验的唯一基准。 */
    public ExportFileService(@Value("${export.files.dir:export-files}") String configuredRoot) throws IOException {
        // exportRoot 提纯：绝对化 → 规范化 → 建目录 → 真实路径（配置里的符号链接在此现出原形）
        Path configured = Path.of(configuredRoot).toAbsolutePath().normalize();
        Files.createDirectories(configured);
        this.exportRoot = configured.toRealPath();
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
        Path file = resolveWithinRoot(relative);
        validateWithinRoot(relative);
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        return file;
    }

    /**
     * 发布（第 18 章发布协议第 2 步）：Workbook 关闭后调用，ATOMIC_MOVE 将临时文件瞬间切换为 attempt-N.xlsx。
     * 不支持原子移动时原样抛出、不降级为普通复制（半可见的正式文件比失败更不可解释），由执行体收敛 FAILED 并清理。
     *
     * @return 发布结果三元组：relativePath 供 DB 登记（正斜杠相对路径）、sizeBytes 为正式文件字节大小、
     *         absolutePath 仅存当前进程，供 markSucceeded 事务失败时的补偿删除
     */
    public PublishedFile publish(Path temporary, int attemptNo) throws IOException {
        requireWithinRoot(temporary);
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
        Path resolved = resolveWithinRoot(parsed);
        validateWithinRoot(parsed);
        return resolved;
    }

    /**
     * 补偿删除已发布但未登记 DB 的正式文件（markSucceeded 事务失败时执行体调用）。
     * 校验在受控 root 内后才删；删除失败仅记日志不抛出，孤儿由第 19 章回收。无返回值。
     */
    public void deletePublished(Path absolutePath) {
        try {
            requireWithinRoot(absolutePath);
            Files.deleteIfExists(absolutePath);
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("export_published_file_delete_failed path={} reason={}", absolutePath, ex.toString());
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

    /**
     * 文本层解析（纯字符串运算）：normalize 后拒绝根组件与 .. 逃逸，拼接到 exportRoot 下。
     *
     * @return exportRoot 下的目标绝对路径；只查写法不查磁盘，须配合 validateWithinRoot 使用
     */
    private Path resolveWithinRoot(Path relative) {
        Path normalized = relative.normalize();
        if (normalized.isAbsolute() || normalized.getRoot() != null || normalized.startsWith("..")) {
            throw new IllegalArgumentException("导出路径逃逸出受控根目录: " + relative);
        }
        return exportRoot.resolve(normalized);
    }

    /**
     * 绝对路径入口防御：normalize 后必须位于 exportRoot 内，再按相对部分走物理层校验。
     * 无返回值：通过即静默返回，越界抛 IllegalArgumentException。
     */
    private void requireWithinRoot(Path absolute) {
        Path normalized = absolute.normalize();
        if (!normalized.startsWith(exportRoot)) {
            throw new IllegalArgumentException("导出路径逃逸出受控根目录: " + absolute);
        }
        validateWithinRoot(exportRoot.relativize(normalized));
    }

    /**
     * 物理层校验（与文本层配套）：文本层只看字符串，防不住 root 内被塞入符号链接（如 42 → /etc），
     * 故逐段走进真实文件系统查链接，再对已存在目标用 toRealPath 验证最终落点仍在 root 内。
     * 无返回值：通过即静默返回，含链接/越界/无法验真均抛 IllegalArgumentException。
     */
    private void validateWithinRoot(Path relative) {
        Path current = exportRoot;
        // 逐段拼接走真实文件系统；中间任意一层是符号链接即拒绝（逃逸常发生在中间目录层）
        for (Path segment : relative.normalize()) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Export path contains symbolic link: " + relative);
            }
        }
        // 目标真实存在才验真（待创建的 .tmp 尚不存在，无真实路径可查）
        if (Files.exists(current)) {
            try {
                // toRealPath 返回跟随全部链接后的真实路径，兜住逐段检查的盲区（嵌套链接/竞态窗口）
                if (!current.toRealPath().startsWith(exportRoot)) {
                    throw new IllegalArgumentException("导出路径逃逸出受控根目录: " + relative);
                }
            } catch (IOException ex) {
                // 系统调用失败无法验真，按逃逸同等处理：查不出真相 ≠ 安全，拒绝放行
                throw new IllegalArgumentException("Export path cannot be resolved: " + relative, ex);
            }
        }
    }
}
