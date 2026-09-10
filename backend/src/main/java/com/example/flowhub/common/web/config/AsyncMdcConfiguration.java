package com.example.flowhub.common.web.config;

import com.example.flowhub.common.web.trace.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 项目统一异步线程池配置，带 MDC 上下文传递。
 * <p>
 * 业务侧异步任务应注入 {@code flowHubTaskExecutor} 以保持 trace 链路。
 */
@Configuration
public class AsyncMdcConfiguration {

    @Bean(name = "flowHubTaskExecutor")
    public ThreadPoolTaskExecutor flowHubTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("flowhub-async-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }
}
