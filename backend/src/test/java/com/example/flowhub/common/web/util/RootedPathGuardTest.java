package com.example.flowhub.common.web.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** RootedPathGuard 双层路径防腐单测：文本层拒 .. /绝对路径，物理层拒符号链接逃逸。 */
class RootedPathGuardTest {

    @TempDir
    Path temp;

    private RootedPathGuard guard;

    @BeforeEach
    void setUp() {
        guard = new RootedPathGuard(temp);
    }

    @Test
    void resolveWithinRootAcceptsNormalRelative() {
        Path resolved = guard.resolveWithinRoot(Path.of("2026-09-09/42/attempt-1.tmp"));
        // 目标尚未落盘，不能做存在性 I/O，改按路径等值断言
        assertThat(resolved).isAbsolute().isEqualTo(temp.resolve("2026-09-09/42/attempt-1.tmp"));
    }

    @Test
    void resolveWithinRootRejectsTraversalAndAbsolute() {
        assertThatIllegalArgumentException().isThrownBy(() -> guard.resolveWithinRoot(Path.of("..")));
        assertThatIllegalArgumentException().isThrownBy(() -> guard.resolveWithinRoot(Path.of("a/../../b")));
        assertThatIllegalArgumentException().isThrownBy(() -> guard.resolveWithinRoot(Path.of("/etc/passwd")));
    }

    @Test
    void requireWithinRootAcceptsPathInsideRoot() {
        // 只要不抛即通过；文件不必真实存在
        guard.requireWithinRoot(temp.resolve("sub/file.xlsx"));
    }

    @Test
    void requireWithinRootRejectsPathOutsideRoot() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> guard.requireWithinRoot(temp.getParent().resolve("outside.xlsx")));
    }

    @Test
    void validateWithinRootRejectsSymbolicLinkEscape() throws Exception {
        // 物理层：root 内塞一个指向 root 外的符号链接，须按逃逸拒绝
        Path external = temp.getParent().resolve("external-secret.txt");
        Files.writeString(external, "secret");
        try {
            Files.createSymbolicLink(temp.resolve("escape"), external);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
            // 平台/权限不支持符号链接则跳过本用例（不影响其它路径防腐断言）
            return;
        }
        assertThatIllegalArgumentException().isThrownBy(() -> guard.validateWithinRoot(Path.of("escape/secret.txt")));
    }
}