package com.example.exportflow.common.web.error;

import org.springframework.http.HttpStatus;

/**
 * 错误码抽象契约。
 * <p>
 * 业务模块通过实现该接口声明自己的错误码枚举，统一暴露错误编码、默认文案和 HTTP 状态。
 */
public interface ErrorCode {

    /**
     * 错误编码。
     *
     * @return 稳定的错误码字符串
     */
    String code();

    /**
     * 默认错误文案。
     *
     * @return 面向前端或调用方的默认提示
     */
    String message();

    /**
     * 该错误对应的 HTTP 状态。
     *
     * @return HTTP 状态码
     */
    HttpStatus httpStatus();
}
