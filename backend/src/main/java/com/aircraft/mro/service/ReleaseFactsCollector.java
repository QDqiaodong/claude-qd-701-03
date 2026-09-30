package com.aircraft.mro.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BusinessClock;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.Bay;
import com.aircraft.mro.domain.Defect;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.WorkCard;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.BayRepository;
import com.aircraft.mro.repository.DefectRepository;
import com.aircraft.mro.repository.WorkCardRepository;

/**
 * 放行审签的"当前维修事实"采集器：把散落在飞机台账、机位、工卡、故障四处的事实
 * 集中成一份 {@link ReleaseFacts}，发起门禁、快照生成、终审复核、出位门禁都只用这一份口径，
 * 保证"页面判定 / 快照判定 / 出位判定"完全一致，杜绝前端各查各的。
 *
 * <p>超期判定统一走 {@link MroDict#defectOverdue}（到期日 &lt; 业务日期且未关闭），
 * 业务日期统一从 {@link BusinessClock} 取——库里 OPEN 是否已被后台校正成 OVERDUE
 * 不影响门禁结果，跨午夜/重启后任何时刻读到的口径都一致。
 */
@Component
public class ReleaseFactsCollector {

    private final AircraftRepository aircraftRepository;
    private final BayRepository bayRepository;
    private final WorkCardRepository workCardRepository;
    private final DefectRepository defectRepository;
    private final BusinessClock clock;

    public ReleaseFactsCollector(AircraftRepository aircraftRepository,
                                 BayRepository bayRepository,
                                 WorkCardRepository workCardRepository,
                                 DefectRepository defectRepository,
                                 BusinessClock clock) {
        this.aircraftRepository = aircraftRepository;
        this.bayRepository = bayRepository;
        this.workCardRepository = workCardRepository;
        this.defectRepository = defectRepository;
        this.clock = clock;
    }

    /** 一份不可再分流的事实快照（内存对象，落库成证据快照前的中间态） */
    public static class ReleaseFacts {
        public Aircraft aircraft;
        public Bay bay;
        public List<WorkCard> cards = new ArrayList<>();
        public List<WorkCard> openCards = new ArrayList<>();
        public List<Defect> defects = new ArrayList<>();
        public List<Defect> openDefects = new ArrayList<>();
        public List<Defect> overdueDefects = new ArrayList<>();
        public java.time.LocalDate today;
    }

    /**
     * 采集某架飞机当前事实。超期完全按 {@link MroDict#defectOverdue} 的统一业务日期口径派生，
     * 不依赖库里的 OPEN/OVERDUE 状态列是否已被后台校正，也不在读时写库。
     */
    @Transactional(readOnly = true)
    public ReleaseFacts collect(Long aircraftId) {
        ReleaseFacts facts = new ReleaseFacts();
        facts.aircraft = aircraftRepository.findById(aircraftId).orElse(null);
        facts.today = clock.today();
        List<Bay> bays = bayRepository.findAllByCurrentAircraftId(aircraftId);
        facts.bay = bays.isEmpty() ? null : bays.get(0);
        facts.cards = workCardRepository.findAllByAircraftIdOrderByCardNoAsc(aircraftId);
        for (WorkCard card : facts.cards) {
            if (!MroDict.cardClosed(card.getStatus())) {
                facts.openCards.add(card);
            }
        }
        facts.defects = defectRepository.findAllByAircraftIdOrderByDefectNoAsc(aircraftId);
        for (Defect defect : facts.defects) {
            if (MroDict.DEFECT_CLOSED.equals(defect.getStatus())) {
                continue;
            }
            // 唯一口径：未关闭且到期日 < 业务日期即超期（库里 OPEN/OVERDUE 一视同仁）
            if (MroDict.defectOverdue(defect.getStatus(), defect.getDueDate(), facts.today)) {
                facts.overdueDefects.add(defect);
            } else {
                facts.openDefects.add(defect);
            }
        }
        return facts;
    }
}
