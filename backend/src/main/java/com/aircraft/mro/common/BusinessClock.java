package com.aircraft.mro.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/**
 * 全站统一的业务时钟：所有"今天 / 当前时刻"判定只从这里取，禁止各处直接
 * {@code LocalDate.now()}，保证跨日边界唯一、口径一致。
 *
 * <p>时区固定 Asia/Shanghai，与数据库连接/JDBC/Jackson 配置保持一致；
 * 集成测试可通过 {@link #setClock(Clock)} 拨动时钟模拟跨午夜/重启。
 */
@Component
public class BusinessClock {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private volatile Clock clock = Clock.system(BUSINESS_ZONE);

    /** 当前业务日期（跨日判定的唯一时间边界） */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** 当前业务时刻 */
    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 测试用：替换底层时钟（如固定时钟、偏移时钟），用完调 {@link #reset()} */
    public void setClock(Clock clock) {
        this.clock = clock == null ? Clock.system(BUSINESS_ZONE) : clock;
    }

    /** 测试用：恢复系统时钟 */
    public void reset() {
        this.clock = Clock.system(BUSINESS_ZONE);
    }
}
