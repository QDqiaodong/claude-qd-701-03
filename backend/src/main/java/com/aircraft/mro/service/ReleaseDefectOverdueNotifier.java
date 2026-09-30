package com.aircraft.mro.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 故障超期 → 放行失效的桥接实现。
 * 用独立 bean 解耦：{@link DefectOverdueService} 批量校正时不必直接依赖 {@link ReleaseService}，
 * 既避免循环依赖，也让单条通知异常可被批量逻辑隔离。
 */
@Component
public class ReleaseDefectOverdueNotifier implements DefectOverdueNotifier {

    private final ReleaseService releaseService;

    public ReleaseDefectOverdueNotifier(@Lazy ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @Override
    public int notifyOverdue(Long aircraftId, String defectNo) {
        return releaseService.invalidateOnFactChange(aircraftId,
                "故障 " + defectNo + " 已超过保留到期日，变为超期故障");
    }
}
