package com.example.exportflow.common.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API 版本命名空间路径前缀配置。
 *
 * <p>为所有 {@code @RestController} 统一追加 {@code /api/v1} 前缀，控制器内只声明业务相对路径
 * （如 {@code /orders}、{@code /export-jobs}），版本号集中在此处维护。
 *
 * <p>注：采用 {@code forAnnotation(RestController)} 做全局一刀切，简单且当前所有 REST 控制器
 * 都属于 v1；若后续出现某个控制器不希望进入 v1，需改为显式标记方式（可参照 git 历史中的
 * {@code @ApiV1} 实现）以支持按控制器选择性加前缀。
 */
@Configuration
public class ApiWebMvcConfiguration implements WebMvcConfigurer {

    /** API v1 统一路径前缀。 */
    private static final String API_V1_PREFIX = "/api/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_V1_PREFIX, HandlerTypePredicate.forAnnotation(RestController.class));
    }
}