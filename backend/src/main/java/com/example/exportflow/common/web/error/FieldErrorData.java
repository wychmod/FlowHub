package com.example.exportflow.common.web.error;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 参数校验失败的 data 载体：字段路径 → 错误文案的对象映射（契约见 export-http-boundary-plan.md 第 8 节）。
 */
public record FieldErrorData(
        @JsonProperty("field_errors")
        Map<String, String> fieldErrors) {
}
