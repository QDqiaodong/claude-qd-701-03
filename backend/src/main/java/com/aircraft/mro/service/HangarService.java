package com.aircraft.mro.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BusinessClock;
import com.aircraft.mro.domain.Part;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.BayRepository;
import com.aircraft.mro.repository.DefectRepository;
import com.aircraft.mro.repository.PartRepository;
import com.aircraft.mro.repository.ToolLoanRepository;
import com.aircraft.mro.repository.WorkCardRepository;

/**
 * 机库总览：顶栏那排计数器 + 主页面用到的汇总数字。
 * 全部走 repository 的 count 派生查询，低库存这种"两列比较"的用内存过滤。
 *
 * <p>故障计数与故障列表/超期查询共用同一跨日口径（未关闭且到期日 &lt; 业务日期才算超期；
 * 到期日当天仍计入开放），按日期实时统计，不依赖库里 status 列是否已被后台校正，
 * 所以"先开首页"和"先进故障页再回来"看到的数量必然相同。
 */
@Service
public class HangarService {

    private final BayRepository bayRepository;
    private final AircraftRepository aircraftRepository;
    private final WorkCardRepository workCardRepository;
    private final DefectRepository defectRepository;
    private final PartRepository partRepository;
    private final ToolLoanRepository toolLoanRepository;
    private final BusinessClock clock;
    private final DefectOverdueReconciler reconciler;

    public HangarService(BayRepository bayRepository,
                         AircraftRepository aircraftRepository,
                         WorkCardRepository workCardRepository,
                         DefectRepository defectRepository,
                         PartRepository partRepository,
                         ToolLoanRepository toolLoanRepository,
                         BusinessClock clock,
                         DefectOverdueReconciler reconciler) {
        this.bayRepository = bayRepository;
        this.aircraftRepository = aircraftRepository;
        this.workCardRepository = workCardRepository;
        this.defectRepository = defectRepository;
        this.partRepository = partRepository;
        this.toolLoanRepository = toolLoanRepository;
        this.clock = clock;
        this.reconciler = reconciler;
    }

    @Transactional(readOnly = true)
    public Views.Overview overview() {
        // 纯读统计前异步提示跨日校正（不阻塞、不抛异常），首页即第一个入口也能保证读值正确
        reconciler.requestCheck("hangar-overview");

        long bayTotal = bayRepository.count();
        long bayIdle = bayRepository.countByStatus("IDLE");
        long bayOccupied = bayRepository.countByStatus("OCCUPIED");
        long bayDisabled = bayRepository.countByStatus("DISABLED");

        long aircraftTotal = aircraftRepository.count();
        long airworthy = aircraftRepository.countByAirworthy("AIRWORTHY");
        long grounded = aircraftRepository.countByAirworthy("GROUNDED");
        long inspection = aircraftRepository.countByAirworthy("INSPECTION");
        long stored = aircraftRepository.countByAirworthy("STORED");

        long cardAssigned = workCardRepository.countByStatus("ASSIGNED");
        long cardInProgress = workCardRepository.countByStatus("IN_PROGRESS");
        long cardWaitInsp = workCardRepository.countByStatus("WAIT_INSP");
        long cardClosed = workCardRepository.countByStatus("CLOSED");

        var today = clock.today();
        long defectOpen = defectRepository.countEffectiveOpen(today);
        long defectOverdue = defectRepository.countEffectiveOverdue(today);

        List<Part> parts = partRepository.findAllByOrderByPartNoAsc();
        long partLow = parts.stream().filter(part -> part.getStockQty() < part.getMinQty()).count();

        return new Views.Overview(bayTotal, bayIdle, bayOccupied, bayDisabled,
                aircraftTotal, airworthy, grounded, inspection, stored,
                cardAssigned, cardInProgress, cardWaitInsp, cardClosed,
                defectOpen, defectOverdue, parts.size(), partLow,
                toolLoanRepository.countByReturnTimeIsNull());
    }
}
