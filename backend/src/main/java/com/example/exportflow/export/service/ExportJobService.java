package com.example.exportflow.export.service;

import com.example.exportflow.export.dto.ExportJobPageResp;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 导出任务服务（初始骨架版）。
 *
 * <p>当前返回空任务列表占位；任务创建、状态机、进度推送、下载、重试等
 * 完整能力将按 be-td.md 第 6-10 章在后续迭代实现。
 */
@Service
public class ExportJobService {

    public ExportJobPageResp listJobs(int page, int pageSize) {
        return new ExportJobPageResp(List.of(), 0, page, pageSize);
    }
}
