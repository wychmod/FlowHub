package com.example.exportflow.common.web.config;

import com.example.exportflow.common.web.trace.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 带 MDC 上下文传递的异步执行器配置。
 *
 * <p>在这里集中定义项目统一使用的线程池，并通过 {@link MdcTaskDecorator} 让异步任务继承
 * 提交线程的 trace_id。业务侧如需异步处理（@Async / 手动提交），应注入本 bean
 * {@code exportFlowTaskExecutor} 以保持 trace 链路贯穿，而非自建裸线程池。
 */
@Configuration
public class AsyncMdcConfiguration {

    @Bean(name = "exportFlowTaskExecutor")
    public ThreadPoolTaskExecutor exportFlowTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("exportflow-async-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }
}