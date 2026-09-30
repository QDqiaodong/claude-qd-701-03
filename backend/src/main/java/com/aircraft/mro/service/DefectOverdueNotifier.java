package com.aircraft.mro.service;

/**
 * 故障由开放变为超期时的副作用通知：该机待审放行须立即失效。
 * 单独抽成接口，便于在批量校正里做「单条异常不拖垮整批」的隔离，也便于自动化测试注入桩件。
 */
@FunctionalInterface
public interface DefectOverdueNotifier {

    /**
     * @param aircraftId 故障所属飞机
     * @param defectNo   故障单号（用于提示记录）
     * @return 被失效的待审放行单数
     */
    int notifyOverdue(Long aircraftId, String defectNo);
}
