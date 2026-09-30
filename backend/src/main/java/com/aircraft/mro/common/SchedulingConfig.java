package com.aircraft.mro.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关：生产默认开启（故障跨日校正的分钟级兜底）；
 * 集成测试用 mro.scheduling.enabled=false 关闭，避免时钟类用例被墙上时间的 tick 干扰。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(value = "mro.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
