package com.example.flowhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FlowHub 后端服务入口。
 * <p>
 * 已接入 MySQL + Flyway + MyBatis（订单查询真实持久化）与 RabbitMQ（Outbox 投递管道）；
 * Redis、Excel 生成等在后续迭代引入。
 * {@code @EnableScheduling} 打开定时调度，供 Outbox 分发器扫描等后台任务使用。
 */
@SpringBootApplication
@EnableScheduling
public class FlowHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowHubApplication.class, args);
    }
}
