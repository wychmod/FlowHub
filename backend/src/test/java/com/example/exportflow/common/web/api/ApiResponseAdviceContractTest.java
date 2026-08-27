package com.example.exportflow.common.web.api;

import com.example.exportflow.common.web.trace.TraceIdSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ApiResponseAdvice} 包装契约测试。
 *
 * <p>验证三类行为：裸对象自动包装为 {@link ApiResponse}、已包装响应不二次包装、
 * 标注 {@link RawResponse} 的类型按原样返回。使用 standalone 构建，仅聚焦 Advice 本身。
 */
class ApiResponseAdviceContractTest {

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    /** 裸对象应被自动包装为统一 Envelope，并回写 trace_id 响应头。 */
    @Test
    void wrapsRawPayloadIntoEnvelope() throws Exception {
        mvc.perform(get("/probe/plain"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.value").value("plain"))
                .andExpect(header().string(TraceIdSupport.HEADER_NAME, not(emptyOrNullString())));
    }

    /** 控制器已返回 ApiResponse 时，不得被二次包装。 */
    @Test
    void doesNotDoubleWrapExistingEnvelope() throws Exception {
        mvc.perform(get("/probe/already-wrapped"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.value").value("wrapped"))
                .andExpect(jsonPath("$.data.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.nullValue()));
    }

    /** 返回类型标注 @RawResponse 时按原样响应，不进入 Envelope。 */
    @Test
    void skipsReturnBodyTypesAnnotatedAsRawResponse() throws Exception {
        mvc.perform(get("/probe/raw-type"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.value").value("raw"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe/plain")
        Payload plain() {
            return new Payload("plain");
        }

        @GetMapping("/probe/already-wrapped")
        ApiResponse<Payload> alreadyWrapped() {
            return ApiResponse.success(new Payload("wrapped"));
        }

        @GetMapping("/probe/raw-type")
        RawPayload rawType() {
            return new RawPayload("raw");
        }
    }

    /** 普通负载，应被 Advice 包装。 */
    record Payload(String value) {
    }

    /** 标注 @RawResponse 的负载，应保持原始响应。 */
    @RawResponse
    record RawPayload(String value) {
    }
}