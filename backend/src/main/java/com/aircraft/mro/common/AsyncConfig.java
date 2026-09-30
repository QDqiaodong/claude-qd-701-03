package com.aircraft.mro.common;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 打开定时调度与异步执行，并提供故障跨日校正专用的有界线程池。
 * 校正任务不允许占满默认线程池，也不允许在队列里堆积（同一天内重复请求本就是幂等空操作）。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "defectOverdueExecutor")
    public Executor defectOverdueExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        // 服务内并发闸门已在 DefectOverdueService，多余触发直接丢弃即可，绝不阻塞 HTTP 查询线程
        executor.setThreadNamePrefix("defect-overdue-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
