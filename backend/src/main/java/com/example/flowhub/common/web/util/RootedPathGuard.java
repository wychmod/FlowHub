package com.example.flowhub.common.web.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 受控文件根路径守卫：以提纯后的 root 为基准，提供「文本层 + 物理层」双层路径防腐，
 * 供导出/导入文件服务复用同一套安全边界，避免各业务模块私有重复实现。
 */
public final class RootedPathGuard {

    private final Path root;

    /** @param root 已提纯（toAbsolutePath+normalize+createDirectories+toRealPath）的受控根目录 */
    public RootedPathGuard(Path root) {
        this.root = root;
    }

    /** 受控根目录（供调用方 relativize / 拼接）。 */
    public Path root() {
        return root;
    }

    /**
     * 文本层解析（纯字符串运算）：normalize 后拒绝根组件与 .. 逃逸，拼接到 root 下。
     * 只查写法不查磁盘，须配合 {@link #validateWithinRoot} 使用。
     *
     * @return root 下的目标绝对路径
     */
    public Path resolveWithinRoot(Path relative) {
        Path normalized = relative.normalize();
        if (normalized.isAbsolute() || normalized.getRoot() != null || normalized.startsWith("..")) {
            throw new IllegalArgumentException("路径逃逸出受控根目录: " + relative);
        }
        return root.resolve(normalized);
    }

    /** 绝对路径入口防御：normalize 后必须位于 root 内，再按相对部分走物理层校验。通过静默返回，越界抛 IllegalArgumentException。 */
    public void requireWithinRoot(Path absolute) {
        Path normalized = absolute.normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("路径逃逸出受控根目录: " + absolute);
        }
        validateWithinRoot(root.relativize(normalized));
    }

    /**
     * 物理层校验（与文本层配套）：文本层只看字符串，防不住 root 内被塞入符号链接（如 42 → /etc），
     * 故逐段走进真实文件系统查链接，再对已存在目标用 toRealPath 验证最终落点仍在 root 内。
     */
    public void validateWithinRoot(Path relative) {
        Path current = root;
        // 逐段拼接走真实文件系统；中间任意一层是符号链接即拒绝（逃逸常发生在中间目录层）
        for (Path segment : relative.normalize()) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("路径包含符号链接: " + relative);
            }
        }
        // 目标真实存在才验真（待创建的临时/发布文件尚不存在，无真实路径可查）
        if (Files.exists(current)) {
            try {
                // toRealPath 返回跟随全部链接后的真实路径，兜住逐段检查的盲区（嵌套链接/竞态窗口）
                if (!current.toRealPath().startsWith(root)) {
                    throw new IllegalArgumentException("路径逃逸出受控根目录: " + relative);
                }
            } catch (IOException ex) {
                // 系统调用失败无法验真，按逃逸同等处理：查不出真相 ≠ 安全，拒绝放行
                throw new IllegalArgumentException("路径无法验真: " + relative, ex);
            }
        }
    }
}