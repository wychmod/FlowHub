package com.example.exportflow.common.web.trace;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 异步任务 MDC 装饰器：让异步线程继承提交线程的 trace 上下文，
 * 执行完毕后还原执行线程原有的 MDC。
 */
public final class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> submittingContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> executorContext = MDC.getCopyOfContextMap();
            try {
                if (submittingContext == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(submittingContext);
                }
                runnable.run();
            } finally {
                if (executorContext == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(executorContext);
                }
            }
        };
    }
}
