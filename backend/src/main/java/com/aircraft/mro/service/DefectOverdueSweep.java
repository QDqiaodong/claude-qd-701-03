package com.aircraft.mro.service;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aircraft.mro.common.BusinessClock;
import com.aircraft.mro.repository.DefectRepository;

/**
 * 故障跨日校正的落库动作（无事务组件：只负责编排，事务边界由各依赖自身的代理方法决定，
 * 避免在同类里自调用导致事务/代理失效）。
 *
 * <p>读路径（列表/总览/超期清单）本身按日期派生结果，不依赖这里是否已经跑完；
 * 这里只负责把库里滞后的 OPEN 批量收敛成 OVERDUE，保证重启后查库也直接正确。
 */
@Component
public class DefectOverdueSweep {

    private static final Logger log = LoggerFactory.getLogger(DefectOverdueSweep.class);

    private final DefectRepository defectRepository;
    private final BusinessClock clock;
    private final DefectOverdueSweepTx tx;
    private final ReleaseService releaseService;

    public DefectOverdueSweep(DefectRepository defectRepository, BusinessClock clock,
                              DefectOverdueSweepTx tx, ReleaseService releaseService) {
        this.defectRepository = defectRepository;
        this.clock = clock;
        this.tx = tx;
        this.releaseService = releaseService;
    }

    public static class SweepResult {
        /** 实际由 OPEN 翻成 OVERDUE 的条数（0 表示本次无写入） */
        public final int updatedCount;
        /** 涉及的机号（去重），用于按机失效待审放行 */
        public final List<Long> aircraftIds;

        public SweepResult(int updatedCount, List<Long> aircraftIds) {
            this.updatedCount = updatedCount;
            this.aircraftIds = aircraftIds;
        }
    }

    /**
     * 编排一次校正：
     * <ol>
     *   <li>在独立短事务里一条 UPDATE 批量置位并拿到受影响机号，事务先提交；</li>
     *   <li>事务提交后再逐机失效待审放行（各自独立事务）。</li>
     * </ol>
     * 失效必须放在置位事务之外：放行失效会再开 REQUIRES_NEW 事务并写审计事件，
     * 与外层批量置位事务同栈会被外层同步标记影响。
     *
     * @return 未能完成放行失效的机号（全部成功为空列表）
     */
    public List<Long> sweepOnce(LocalDate today) {
        SweepResult result = tx.markOverdue(today, clock.now());
        if (result.updatedCount > 0) {
            log.info("故障跨日校正：业务日期 {}，{} 条 OPEN 缺陷批量置为 OVERDUE，涉及机号 {}",
                    today, result.updatedCount, result.aircraftIds);
        }
        java.util.List<Long> failed = new java.util.ArrayList<>();
        for (Long aircraftId : result.aircraftIds) {
            try {
                releaseService.invalidateOnFactChange(aircraftId,
                        "该机保留故障已超过到期日，变为超期故障");
            } catch (RuntimeException e) {
                // 单台飞机的放行失效失败不阻断其他飞机；返回给调度器等下轮重试
                failed.add(aircraftId);
                log.warn("机号 {} 的待审放行失效失败，将随下一次校正重试：{}",
                        aircraftId, e.toString());
            }
        }
        return failed;
    }

    /** 单独重试某架飞机的放行失效（幂等，重复执行不产生重复记录），失败抛出由上层记录 */
    public void retryInvalidation(Long aircraftId) {
        releaseService.invalidateOnFactChange(aircraftId,
                "该机保留故障已超过到期日，变为超期故障");
    }

    /** 探测今天是否还有"库里 OPEN 但日期已过"的存量（无需写入） */
    public boolean hasStaleOpen(LocalDate today) {
        return !defectRepository.findStaleOpenAircraftIds(today).isEmpty();
    }
}
