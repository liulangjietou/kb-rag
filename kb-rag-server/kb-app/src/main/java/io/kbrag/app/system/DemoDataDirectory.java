package io.kbrag.app.system;

import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 为文档与评测集导入统一解析素材目录，避免默认路径依赖某个启动模块。
 */
public final class DemoDataDirectory {

    private static final Path REPOSITORY_DEMO = Path.of("kb-rag-deploy", "demo");

    private DemoDataDirectory() {
    }

    /**
     * 显式配置优先；未配置时从进程工作目录向上寻找当前仓库内的 Demo 素材。
     *
     * @param configuredDirectory 配置中的素材目录，允许留空
     * @return 规范化的绝对路径；素材是否可读由各导入入口检查
     */
    public static Path resolve(String configuredDirectory) {
        return resolve(configuredDirectory, Path.of(""));
    }

    static Path resolve(String configuredDirectory, Path workingDirectory) {
        Path workingRoot = workingDirectory.toAbsolutePath().normalize();
        if (StringUtils.hasText(configuredDirectory)) {
            return workingRoot.resolve(configuredDirectory).normalize();
        }
        for (Path current = workingRoot; current != null; current = current.getParent()) {
            Path candidate = current.resolve(REPOSITORY_DEMO);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            // 独立工作树的 .git 是文件，同样不能越过它读取其他仓库的素材。
            if (Files.exists(current.resolve(".git"))) {
                break;
            }
        }
        return workingRoot.resolve(REPOSITORY_DEMO);
    }
}
