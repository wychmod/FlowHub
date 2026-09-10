package com.example.flowhub.common.web.upload;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/** 超大请求体预拒：超限 drain + 400 结构化 Envelope、链路不透传；正常与 chunked 请求直通。 */
class OversizeRequestBodyFilterTest {

    private final OversizeRequestBodyFilter filter = new OversizeRequestBodyFilter(DataSize.ofBytes(10));

    @Test
    void oversizeBodyRejectedWith400WithoutReachingChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/import-jobs");
        request.setContent(new byte[16]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"code\":\"VALIDATION_ERROR\"");
        assertThat(response.getContentAsString()).contains("10MB");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void normalBodyAndChunkedPassThrough() throws Exception {
        MockHttpServletRequest normal = new MockHttpServletRequest("POST", "/api/v1/import-jobs");
        normal.setContent(new byte[5]);
        MockFilterChain normalChain = new MockFilterChain();
        filter.doFilter(normal, new MockHttpServletResponse(), normalChain);
        assertThat(normalChain.getRequest()).isNotNull();

        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/api/v1/import-jobs");
        // 不设 content：content-length 为 -1（chunked 等无长度场景），应直通交容器兜底
        MockFilterChain chunkedChain = new MockFilterChain();
        filter.doFilter(chunked, new MockHttpServletResponse(), chunkedChain);
        assertThat(chunkedChain.getRequest()).isNotNull();
    }
}
