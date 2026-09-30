package com.aircraft.mro.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.common.BusinessClock;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.Defect;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.dto.DefectDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.DefectRepository;

/**
 * 模块四：故障保留。
 *
 * <p>规则面：
 * <ol>
 *   <li>缺陷单号唯一</li>
 *   <li>保留期限：DAY 按飞行日直接加天；HOUR 按每天飞 {@value MroDict#HOUR_PER_DAY} 小时折算成飞行日</li>
 *   <li>跨日超期口径全站唯一（{@link MroDict#defectEffectiveStatus}）：到期日 &lt; 业务日期
 *       且未关闭即为超期；到期日当天仍有效，下一业务日起才算超期；已关闭永不回退。
 *       列表/只看本机/超期清单/顶部总览/放行门禁都用同一判据，读的时候只按日期派生，
 *       <b>不逐条写库</b>；库里 OPEN→OVERDUE 的批量收敛由 {@link DefectOverdueReconciler}
 *       在后台异步完成，因此先开哪个页面都不影响结果。</li>
 *   <li>已关闭的缺陷不能重复关闭</li>
 * </ol>
 */
@Service
public class DefectService {

    private final DefectRepository defectRepository;
    private final AircraftRepository aircraftRepository;
    private final ReleaseService releaseService;
    private final BusinessClock clock;
    private final DefectOverdueReconciler reconciler;

    public DefectService(DefectRepository defectRepository, AircraftRepository aircraftRepository,
                         ReleaseService releaseService, BusinessClock clock,
                         DefectOverdueReconciler reconciler) {
        this.defectRepository = defectRepository;
        this.aircraftRepository = aircraftRepository;
        this.releaseService = releaseService;
        this.clock = clock;
        this.reconciler = reconciler;
    }

    /**
     * 故障列表（可按机号过滤）。纯读：超期按业务日期实时派生，刷新列表不产生任何写入；
     * 同时异步提示校正器"今天还没校正过的话补一次后台收敛"，不阻塞本次返回。
     */
    @Transactional(readOnly = true)
    public List<Views.DefectView> list(Long aircraftId) {
        reconciler.requestCheck("defect-list");
        List<Defect> defects = aircraftId == null
                ? defectRepository.findAllByOrderByDefectNoAsc()
                : defectRepository.findAllByAircraftIdOrderByDefectNoAsc(aircraftId);
        LocalDate today = clock.today();
        Map<Long, String> tailMap = new HashMap<>();
        for (Aircraft aircraft : aircraftRepository.findAllByOrderByTailNoAsc()) {
            tailMap.put(aircraft.getId(), aircraft.getTailNo());
        }
        List<Views.DefectView> list = new ArrayList<>();
        for (Defect defect : defects) {
            list.add(toView(defect, tailMap, today));
        }
        return list;
    }

    /**
     * 超期查询：按日期口径取"未关闭且到期日早于业务日期"，不依赖库里状态是否已被后台置位。
     * 因此不先进故障页、服务刚重启、跨午夜后第一次查，结果都与列表/总览完全一致。
     */
    @Transactional(readOnly = true)
    public List<Views.DefectView> overdue() {
        reconciler.requestCheck("defect-overdue");
        Map<Long, String> tailMap = new HashMap<>();
        for (Aircraft aircraft : aircraftRepository.findAllByOrderByTailNoAsc()) {
            tailMap.put(aircraft.getId(), aircraft.getTailNo());
        }
        LocalDate today = clock.today();
        List<Views.DefectView> list = new ArrayList<>();
        for (Defect defect : defectRepository.findEffectiveOverdue(today)) {
            list.add(toView(defect, tailMap, today));
        }
        return list;
    }

    @Transactional
    public Views.DefectView create(DefectDtos.DefectSaveReq req) {
        String defectNo = trim(req.getDefectNo());
        if (defectRepository.existsByDefectNo(defectNo)) {
            throw new BizException("缺陷单号 " + defectNo + " 已存在，不能重复登记");
        }
        Long aircraftId = req.getAircraftId();
        if (aircraftId == null) {
            throw new BizException("请先选择缺陷所属的飞机");
        }
        Aircraft aircraft = aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        String unit = trim(req.getLimitUnit()).toUpperCase();
        if (!MroDict.isValidLimitUnit(unit)) {
            throw new BizException("保留期限单位只能是 DAY（飞行日）或 HOUR（飞行小时），收到的是：" + req.getLimitUnit());
        }
        Integer limitValue = req.getLimitValue() == null ? 0 : req.getLimitValue();
        if (limitValue <= 0) {
            throw new BizException("保留期限数值必须大于 0");
        }
        int days = MroDict.limitToDays(unit, limitValue);
        if (days > MroDict.MAX_LIMIT_DAY) {
            throw new BizException("保留期限折算后为 " + days + " 天，超过上限 " + MroDict.MAX_LIMIT_DAY + " 天，需走工程评估");
        }
        LocalDate today = clock.today();
        LocalDate foundDate = req.getFoundDate() == null ? today : req.getFoundDate();
        LocalDate dueDate = foundDate.plusDays(days);
        Defect defect = new Defect();
        defect.setDefectNo(defectNo);
        defect.setAircraftId(aircraft.getId());
        defect.setAtaChapter(trim(req.getAtaChapter()));
        defect.setDescription(trim(req.getDescription()));
        defect.setLimitUnit(unit);
        defect.setLimitValue(limitValue);
        defect.setFoundDate(foundDate);
        defect.setDueDate(dueDate);
        // 登记时即按统一跨日口径落初始状态（到期日等于当天仍为开放）
        defect.setStatus(MroDict.defectEffectiveStatus(MroDict.DEFECT_OPEN, dueDate, today));
        defect.setReporter(req.getReporter() == null || req.getReporter().isBlank() ? null : req.getReporter().trim());
        defect.setUpdatedAt(clock.now());
        defect = defectRepository.save(defect);
        // 新增关联故障：待审放行的证据快照对不上当前事实，立即失效
        releaseService.invalidateOnFactChange(defect.getAircraftId(),
                "新增故障保留 " + defect.getDefectNo() + "（" + MroDict.defectStatusName(defect.getStatus()) + "）");
        return toView(defect, tailNoMap(aircraft), today);
    }

    /** 关闭缺陷：已关闭的不能再关；关闭后无论到期日多早都不会再算超期 */
    @Transactional
    public Views.DefectView close(Long id) {
        Defect defect = defectRepository.findById(id)
                .orElseThrow(() -> new BizException("缺陷单不存在：id=" + id));
        if (MroDict.DEFECT_CLOSED.equals(defect.getStatus())) {
            throw new BizException("缺陷单 " + defect.getDefectNo() + " 已关闭，不能重复关闭");
        }
        defect.setStatus(MroDict.DEFECT_CLOSED);
        defect.setUpdatedAt(clock.now());
        defect = defectRepository.save(defect);
        // 保留故障被闭环，也是维修事实变化：待审放行立即失效（差异视图可查）
        releaseService.invalidateOnFactChange(defect.getAircraftId(),
                "故障 " + defect.getDefectNo() + " 已关闭，维修事实变化");
        return toView(defect, tailNoMap(null), clock.today());
    }

    private Views.DefectView toView(Defect defect, Map<Long, String> tailMap, LocalDate today) {
        boolean overdue = MroDict.defectOverdue(defect.getStatus(), defect.getDueDate(), today);
        boolean dueToday = MroDict.defectDueToday(defect.getStatus(), defect.getDueDate(), today);
        String effectiveStatus = MroDict.defectEffectiveStatus(defect.getStatus(), defect.getDueDate(), today);
        long daysLeft = ChronoUnit.DAYS.between(today, defect.getDueDate());
        return new Views.DefectView(defect.getId(), defect.getDefectNo(), defect.getAircraftId(),
                tailMap.get(defect.getAircraftId()), defect.getAtaChapter(), defect.getDescription(),
                defect.getLimitUnit(), MroDict.limitUnitName(defect.getLimitUnit()), defect.getLimitValue(),
                defect.getLimitValue() + MroDict.limitUnitName(defect.getLimitUnit()),
                defect.getFoundDate(), defect.getDueDate(), effectiveStatus,
                MroDict.defectStatusName(effectiveStatus), defect.getReporter(), daysLeft, overdue, dueToday);
    }

    private Map<Long, String> tailNoMap(Aircraft only) {
        Map<Long, String> map = new HashMap<>();
        if (only != null) {
            map.put(only.getId(), only.getTailNo());
            return map;
        }
        for (Aircraft aircraft : aircraftRepository.findAllByOrderByTailNoAsc()) {
            map.put(aircraft.getId(), aircraft.getTailNo());
        }
        return map;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
