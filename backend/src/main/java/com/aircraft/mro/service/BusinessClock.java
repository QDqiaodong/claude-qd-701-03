package com.aircraft.mro.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一的「系统业务日期」来源。
 *
 * <p>跨日判定只认这一个口径：{@link #today()}。故障列表、只看当前飞机、超期查询、顶部总览、
 * 放行门禁/快照漂移检测全部通过它取业务日期，杜绝各入口各调一次 {@link LocalDate#now()} 后
 * 因访问顺序、跨过午夜而结果不一致。
 *
 * <p>时间边界：到期日 == 业务日仍在有效期内，{@code dueDate.isBefore(today())} 才是超期；
 * 从下一业务日零点开始才算超期。
 *
 * <p>生产环境时钟为系统默认时钟；测试可通过 {@code mro.business-date=yyyy-MM-dd} 把业务日固定，
 * 或直接 {@link #setClock(Clock)} 注入，用于验收「服务运行中跨过午夜」而无需重启进程。
 */
@Component
public class BusinessClock {

    private volatile Clock clock;

    public BusinessClock(@Value("${mro.business-date:}") String fixedDate) {
        if (fixedDate != null && !fixedDate.isBlank()) {
            this.clock = Clock.fixed(LocalDate.parse(fixedDate.trim()).atStartOfDay(
                    java.time.ZoneId.of("Asia/Shanghai")).toInstant(),
                    java.time.ZoneId.of("Asia/Shanghai"));
        } else {
            this.clock = Clock.system(java.time.ZoneId.of("Asia/Shanghai"));
        }
    }

    /** 当前业务日期（全系统唯一口径） */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 测试/验收用：直接替换时钟（可模拟跨午夜，无需重启进程） */
    public void setClock(Clock clock) {
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    /** 测试/验收用：把业务日期固定到某一天 */
    public void setDate(LocalDate date) {
        this.clock = Clock.fixed(date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant(),
                java.time.ZoneId.of("Asia/Shanghai"));
    }

    /** 测试/验收用：恢复跟随系统时间 */
    public void useSystemClock() {
        this.clock = Clock.system(java.time.ZoneId.of("Asia/Shanghai"));
    }
}
