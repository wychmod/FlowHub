package com.example.exportflow.export.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 受控文件边界单测（第 18 章）：exportRoot 提纯、路径双层防腐（normalize + 符号链接/toRealPath）、
 * 原子移动发布与补偿删除。符号链接用例在无权限环境自动跳过（assumeTrue）。
 */
class ExportFileServiceTest {

    @TempDir
    Path tempDir;

    // ==================== exportRoot 提纯 ====================

    @Test
    void rootPurifiedThroughSymlinkToRealPath() throws Exception {
        Path real = Files.createDirectories(tempDir.resolve("real-root"));
        Path link = tempDir.resolve("link-root");
        assumeTrue(createSymLink(link, real), "当前环境不支持创建符号链接，跳过");

        ExportFileService service = new ExportFileService(link.toString());

        // 配置期符号链接被 toRealPath 消解：分配的临时文件目录落在真实根目录
        Path temporary = service.temporaryPath(1, 1);
        assertThat(temporary.getParent().toRealPath()).startsWith(real.toRealPath());
    }

    // ==================== 临时文件分配 ====================

    @Test
    void temporaryPathCreatesHierarchicalDirsInsideRoot() throws Exception {
        ExportFileService service = newService("root");

        Path temporary = service.temporaryPath(42, 2);

        // <root>/<UTC 日期>/42/attempt-2.tmp 三层定位：jobId 隔离任务、attemptNo 隔离重试
        assertThat(temporary.startsWith(tempDir.resolve("root"))).isTrue();
        assertThat(temporary.getParent().getFileName().toString()).isEqualTo("42");
        assertThat(temporary.getFileName().toString()).isEqualTo("attempt-2.tmp");
        assertThat(temporary.getParent().getParent().getFileName().toString()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(Files.isDirectory(temporary.getParent())).isTrue();
    }

    @Test
    void temporaryPathDeletesLeftoverSameAttemptFile() throws Exception {
        ExportFileService service = newService("root");
        Path temporary = service.temporaryPath(7, 1);
        Files.writeString(temporary, "stale"); // 模拟上次中断的残留

        Path reallocated = service.temporaryPath(7, 1);

        assertThat(reallocated).isEqualTo(temporary);
        assertThat(Files.exists(reallocated)).isFalse();
    }

    // ==================== 路径双层防腐 ====================

    @Test
    void resolvePersistedRejectsBlankAbsoluteAndTraversal() throws Exception {
        ExportFileService service = newService("root");

        assertThatIllegalArgumentException().isThrownBy(() -> service.resolvePersisted(null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.resolvePersisted("  "));
        assertThatIllegalArgumentException().isThrownBy(() -> service.resolvePersisted("/etc/passwd"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.resolvePersisted("../secret.xlsx"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.resolvePersisted("42/../../secret.xlsx"));

        // 合法相对路径解析后仍在 root 内（存在性由下载方判定并映射独立错误码）
        assertThat(service.resolvePersisted("2026-09-09/42/attempt-1.xlsx")
                .startsWith(tempDir.resolve("root"))).isTrue();
    }

    @Test
    void resolvePersistedRejectsSymbolicLinkEscape() throws Exception {
        ExportFileService service = newService("root");
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.xlsx"), "x");
        assumeTrue(createSymLink(tempDir.resolve("root").resolve("escape"), outside),
                "当前环境不支持创建符号链接，跳过");

        // escape 目录名义在 root 内、真实指向外部：物理层必须拒绝
        assertThatIllegalArgumentException().isThrownBy(() -> service.resolvePersisted("escape/secret.xlsx"));
    }

    // ==================== 发布与补偿 ====================

    @Test
    void publishMovesTmpToXlsxAndReturnsPublishedFile() throws Exception {
        ExportFileService service = newService("root");
        Path temporary = service.temporaryPath(9, 1);
        Files.write(temporary, new byte[]{1, 2, 3, 4});

        PublishedFile published = service.publish(temporary, 1);

        // 三联单：相对路径（正斜杠统一）+ 字节数 + 绝对路径；tmp 已被原子移动不复存在
        assertThat(published.relativePath()).matches("\\d{4}-\\d{2}-\\d{2}/9/attempt-1\\.xlsx");
        assertThat(published.sizeBytes()).isEqualTo(4L);
        assertThat(Files.exists(published.absolutePath())).isTrue();
        assertThat(Files.exists(temporary)).isFalse();
    }

    @Test
    void publishRejectsTemporaryOutsideRootWithoutMoving() throws Exception {
        ExportFileService service = newService("root");
        Path evil = tempDir.resolve("evil.xlsx");
        Files.write(evil, new byte[]{9});

        assertThatIllegalArgumentException().isThrownBy(() -> service.publish(evil, 1));
        assertThat(Files.exists(evil)).isTrue();
    }

    @Test
    void deletePublishedRemovesOnlyFilesWithinRoot() throws Exception {
        ExportFileService service = newService("root");
        Path temporary = service.temporaryPath(11, 1);
        Files.write(temporary, new byte[]{1});
        PublishedFile published = service.publish(temporary, 1);

        service.deletePublished(published.absolutePath());
        assertThat(Files.exists(published.absolutePath())).isFalse();

        // root 外路径拒绝删除，且不抛异常（补偿路径不掩盖执行体的首异常）
        Path evil = tempDir.resolve("evil.xlsx");
        Files.write(evil, new byte[]{1});
        assertThatCode(() -> service.deletePublished(evil)).doesNotThrowAnyException();
        assertThat(Files.exists(evil)).isTrue();
    }

    // ==================== 辅助 ====================

    private ExportFileService newService(String relativeRoot) throws IOException {
        return new ExportFileService(tempDir.resolve(relativeRoot).toString());
    }

    /** 创建符号链接；无权限环境（Windows 非管理员/非开发者模式）返回 false 由用例跳过。 */
    private static boolean createSymLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException ex) {
            return false;
        }
    }
}
