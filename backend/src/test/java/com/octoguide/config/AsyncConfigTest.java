package com.octoguide.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证聊天任务使用有界线程池，避免每个请求创建独立线程。 */
class AsyncConfigTest {

    @Test
    void chatExecutorIsBounded() {
        ThreadPoolTaskExecutor executor = new AsyncConfig().chatTaskExecutor();
        assertEquals(2, executor.getCorePoolSize());
        assertEquals(8, executor.getMaxPoolSize());
        assertEquals(32, executor.getThreadPoolExecutor().getQueue().remainingCapacity());
        executor.shutdown();
    }
}
