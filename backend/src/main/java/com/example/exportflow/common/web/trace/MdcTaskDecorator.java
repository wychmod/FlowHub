package com.example.exportflow.common.web.trace;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 异步任务 MDC 上下文装饰器，解决 ThreadLocal 无法跨线程传递的问题。
 *
 * <p>MDC 底层是 ThreadLocal，新线程默认拿不到提交线程的 trace_id。本装饰器在提交时快照
 * 提交线程的 MDC map，执行线程开始时写入、结束后再还原执行线程原本的上下文，从而让
 * 异步任务（线程池 / @Async / 消息并发消费）继承调用方同一条 trace 链路。
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
                // 还原执行线程在本次任务前的上下文，避免污染复用线程。
                if (executorContext == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(executorContext);
                }
            }
        };
    }
}