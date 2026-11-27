package com.toolhelper.cron;

import com.toolhelper.config.CronProperties;
import com.toolhelper.support.ApiException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CronCalculatorTest {
    @Test void saturationTimeoutAndNonCooperativeWorkNeverCreateReplacementThreads() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var calculator = new CronCalculator(new CronProperties(1, 1, 200))) {
            var first = calculator.submit(() -> {
                entered.countDown();
                // 模拟next不响应中断：Future超时不能释放实际计算槽。
                boolean done = false;
                while (!done) { try { release.await(); done = true; } catch (InterruptedException ignored) { /* 刻意不协作的测试夹具 */ } }
                return 1;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queued = calculator.submit(() -> 2);
            var rejected = assertThrows(ExecutionException.class, () -> calculator.submit(() -> 3).get(2, TimeUnit.SECONDS));
            assertEquals("CRON_BUSY", ((ApiException) rejected.getCause()).code());
            var timeout = assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
            assertEquals("CRON_CALCULATION_TIMEOUT", ((ApiException) timeout.getCause()).code());
            assertThrows(ExecutionException.class, () -> queued.get(2, TimeUnit.SECONDS));
            // 超时工作仍占槽，新请求只会排队后超时，不能绕开并发配额。
            var blocked = assertThrows(ExecutionException.class, () -> calculator.submit(() -> 4).get(2, TimeUnit.SECONDS));
            assertEquals("CRON_CALCULATION_TIMEOUT", ((ApiException) blocked.getCause()).code());
            release.countDown();
        } finally { release.countDown(); }
    }
}
