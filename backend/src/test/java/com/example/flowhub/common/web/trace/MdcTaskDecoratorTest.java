package com.example.flowhub.common.web.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 MdcTaskDecorator 能把提交线程的 trace_id 传递给执行线程，并在结束后还原。 */
class MdcTaskDecoratorTest {

    private static final String TEST_TRACE = "0123456789abcdef0123456789abcdef";

    @BeforeEach
    @AfterEach
    void resetMdc() {
        MDC.clear();
    }

    @Test
    void propagatesMdcToExecutorAndRestoresContext() throws Exception {
        // 模拟提交线程携带 trace_id
        MDC.put(TraceIdSupport.MDC_KEY, TEST_TRACE);

        MdcTaskDecorator decorator = new MdcTaskDecorator();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> seenInTask = new AtomicReference<>();

        // 已存在 context：验证执行线程能读到调用方的 trace_id
        assertTrue(MDC.getCopyOfContextMap().containsKey(TraceIdSupport.MDC_KEY));

        decorator.decorate(() -> {
            seenInTask.set(MDC.get(TraceIdSupport.MDC_KEY));
            latch.countDown();
        }).run();

        assertTrue(latch.await(2, TimeUnit.SECONDS), "任务应在 2s 内执行完成");
        assertEquals(TEST_TRACE, seenInTask.get(), "执行线程应继承提交线程的 trace_id");
        assertEquals(TEST_TRACE, MDC.get(TraceIdSupport.MDC_KEY), "执行后应还原提交线程的 MDC");
    }

    @Test
    void restoresExecutorOriginalContextWhenSubmittingHasNone() throws Exception {
        // 提交线程没有 trace_id（上下文为 null）
        assertNull(MDC.getCopyOfContextMap());

        MdcTaskDecorator decorator = new MdcTaskDecorator();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> seenInTask = new AtomicReference<>();

        decorator.decorate(() -> {
            MDC.put(TraceIdSupport.MDC_KEY, "worker-local");
            seenInTask.set(MDC.get(TraceIdSupport.MDC_KEY));
            latch.countDown();
        }).run();

        assertTrue(latch.await(2, TimeUnit.SECONDS), "任务应在 2s 内执行完成");
        assertEquals("worker-local", seenInTask.get());
        // 还原：执行线程标记的 key 不应泄漏回提交线程
        assertNull(MDC.get(TraceIdSupport.MDC_KEY), "执行线程的 MDC 不应泄漏回提交线程");
    }
}