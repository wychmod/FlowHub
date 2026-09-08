package com.example.exportflow.export.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 导出文件服务（第 17 章）：分配业务临时文件路径与失败清理。
 * <p>
 * 业务临时文件（job-{id}-attempt-{n}.tmp）承载完整但未发布的 Excel——与 POI 库内部的
 * SXSSF 临时 XML 分属两条生命周期（后者由 workbook.dispose() 清理），不可互相替代。
 */
@Service
public class ExportFileService {

    private static final Logger log = LoggerFactory.getLogger(ExportFileService.class);

    private final Path baseDir;

    public ExportFileService(@Value("${export.files.dir:export-files}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    /** 分配业务临时文件路径（教程记法 attempt-N.tmp，叠加 jobId 前缀防并发任务互相覆盖）。 */
    public Path temporaryPath(long jobId, int attemptNo) {
        return baseDir.resolve("job-" + jobId + "-attempt-" + attemptNo + ".tmp");
    }

    /** 删除业务临时文件（失败收敛时调用；文件不存在视为已清理，删除失败仅记日志不阻断收敛）。 */
    public void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            log.warn("export_temp_file_delete_failed path={} reason={}", file, ex.toString());
        }
    }
}
