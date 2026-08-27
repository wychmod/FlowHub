package com.example.exportflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ExportFlow 后端服务入口。
 *
 * <p>当前为项目初始骨架：仅包含 Web 基础设施与订单、导出任务两个业务模块的
 * 最小示例接口。Redis、RabbitMQ、Excel 生成等依赖将按 be-td.md 在后续迭代引入。
 *
 * <p>已接入 MySQL 数据源与 Flyway：应用启动时由 Flyway 自动执行
 * {@code src/main/resources/db/migration} 下的迁移脚本。订单查询目前仍由
 * {@code InMemoryOrderMapper} 提供内存 Mock，替换为真实 MyBatis 实现时新增迁移脚本即可。
 */
@SpringBootApplication
public class ExportFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExportFlowApplication.class, args);
    }
}
