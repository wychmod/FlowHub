package com.example.exportflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ExportFlow 后端服务入口。
 * <p>
 * 已接入 MySQL + Flyway + MyBatis，订单查询走真实持久化；
 * Redis、RabbitMQ、Excel 生成等按 be-td.md 在后续迭代引入。
 */
@SpringBootApplication
public class ExportFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExportFlowApplication.class, args);
    }
}
