package com.example.exportflow.export.dto;

import com.example.exportflow.export.command.CreateExportJobCommand;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建导出任务请求体（JSON 绑定，见 export-http-boundary-plan.md 第 5 节）。
 * <p>
 * 只承担结构校验；白名单、存在性等业务校验由 ExportJobService 完成。
 */
public record CreateExportJobRequest(

        @NotNull(message = "selection 不能为空")
        @Valid
        ExportSelectionRequest selection,

        @NotEmpty(message = "columns 不能为空")
        @Size(max = 9, message = "columns 最多 9 列")
        List<@NotBlank(message = "columns 不能包含空值") String> columns,

        @JsonProperty("file_name")
        @Size(max = 255, message = "file_name 过长")
        String fileName) {

    /** 转导出创建命令（规范化规则见 {@link com.example.exportflow.export.command.CreateExportJobCommand}）。 */
    public CreateExportJobCommand toCommand() {
        return CreateExportJobCommand.from(this);
    }
}
