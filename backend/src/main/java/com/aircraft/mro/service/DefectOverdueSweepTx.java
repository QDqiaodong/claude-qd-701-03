package com.aircraft.mro.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.repository.DefectRepository;

/**
 * 故障跨日校正的事务边界（独立 bean，确保 {@link Transactional} 经过 Spring 代理生效）。
 * 方法返回后事务立即提交，调用方在事务之外做放行失效，避免事务嵌套互相影响。
 */
@Component
public class DefectOverdueSweepTx {

    private final DefectRepository defectRepository;

    public DefectOverdueSweepTx(DefectRepository defectRepository) {
        this.defectRepository = defectRepository;
    }

    /**
     * 单个短事务完成一次批量置位：先取受影响机号，再一条 UPDATE。
     * 状态未变化时 UPDATE 命中 0 行，不产生任何写入。
     */
    @Transactional
    public DefectOverdueSweep.SweepResult markOverdue(LocalDate today, LocalDateTime now) {
        List<Long> aircraftIds = defectRepository.findStaleOpenAircraftIds(today);
        if (aircraftIds.isEmpty()) {
            return new DefectOverdueSweep.SweepResult(0, List.of());
        }
        int updated = defectRepository.markOpenOverdue(today, now);
        return new DefectOverdueSweep.SweepResult(updated, aircraftIds);
    }
}
