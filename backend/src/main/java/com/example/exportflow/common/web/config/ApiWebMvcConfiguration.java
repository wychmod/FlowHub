package com.example.exportflow.common.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API v1 路径前缀配置：为所有 {@code @RestController} 统一追加 {@code /api/v1} 前缀，
 * 版本号集中在此处维护。
 */
@Configuration
public class ApiWebMvcConfiguration implements WebMvcConfigurer {

    private static final String API_V1_PREFIX = "/api/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_V1_PREFIX, HandlerTypePredicate.forAnnotation(RestController.class));
    }
}
