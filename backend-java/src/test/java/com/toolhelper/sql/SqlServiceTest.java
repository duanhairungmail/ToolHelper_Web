package com.toolhelper.sql;

import com.toolhelper.config.SqlProperties;
import com.toolhelper.support.ApiException;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlServiceTest {
    @Test void timeoutsDoNotCreateReplacementWorkersForUninterruptibleActions() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var service = new SqlService(new SqlProperties(1, 1, 100))) {
            var first = service.submit(() -> {
                started.countDown();
                while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { /* 模拟不响应中断的第三方解析，测试后会显式释放。 */ } }
                return new SqlModels.Result("SELECT 1", "MYSQL", 1, List.of());
            }, "FIXTURE");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = service.submit(() -> { fail("超时队列任务不应执行"); return null; }, "FIXTURE");
            var busy = service.submit(() -> null, "FIXTURE");
            assertEquals("SQL_BUSY", ((ApiException) assertThrows(ExecutionException.class, () -> busy.get(2, TimeUnit.SECONDS)).getCause()).code());
            assertEquals("SQL_TIMEOUT", ((ApiException) assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS)).getCause()).code());
            assertEquals("SQL_TIMEOUT", ((ApiException) assertThrows(ExecutionException.class, () -> queued.get(2, TimeUnit.SECONDS)).getCause()).code());
        } finally { release.countDown(); }
    }
}
