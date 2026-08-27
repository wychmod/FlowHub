package com.example.exportflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * ExportFlow 后端服务入口。
 *
 * <p>当前为项目初始骨架：仅包含 Web 基础设施与订单、导出任务两个业务模块的
 * 最小示例接口。MySQL、Redis、RabbitMQ、Excel 生成等依赖将按 be-td.md 在后续迭代引入。
 *
 * <p>已引入 mybatis-spring-boot-starter 与 spring-boot-starter-jdbc（数据源自动配置），
 * 但当前骨架尚无数据库，故显式排除 {@link DataSourceAutoConfiguration}，避免启动时
 * 因缺少 JDBC URL/驱动而失败；订单数据由 {@code InMemoryOrderMapper} 提供 Mock。
 * 接入 MySQL 后移除该排除项并配置 datasource 即可启用真实持久化。
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class ExportFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExportFlowApplication.class, args);
    }
}
