package com.example.flowhub.common.web.error;

import com.example.flowhub.common.web.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import static org.assertj.core.api.Assertions.assertThat;

/** multipart 异常 → 400 结构化 Envelope（上传超限/解析失败不落 500 兜底）。 */
class GlobalExceptionHandlerMultipartTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void maxUploadSizeMapsTo400ValidationError() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMaxUploadSize(new MaxUploadSizeExceededException(11L * 1024 * 1024));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void multipartParseFailureMapsTo400ValidationError() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMultipart(new MultipartException("bad boundary"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void notAcceptableMapsTo406() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleNotAcceptable(
                new org.springframework.web.HttpMediaTypeNotAcceptableException("no match"));

        assertThat(response.getStatusCode().value()).isEqualTo(406);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_ACCEPTABLE");
    }
}
