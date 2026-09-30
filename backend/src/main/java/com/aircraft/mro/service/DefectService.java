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
 *   <li>跨日口径全系统唯一：未关闭且 {@code dueDate < 业务日} 即为超期。到期日 == 当天仍有效，
 *       下一业务日才超期；CLOSED 无论日期多早都不算超期。列表/只看当前机/超期查询/顶部总览
 *       都按同一日期口径即时派生，普通查询不写库。</li>
 *   <li>状态落库（OPEN→OVERDUE）只由 {@link DefectOverdueService} 的异步批量校正做一次，
 *       幂等且可重试；不随页面刷新反复改 updatedAt 或重复写放行失效提示。</li>
 *   <li>已关闭的缺陷不能重复关闭</li>
 * </ol>
 */
@Service
public class DefectService {

    private final DefectRepository defectRepository;
    private final AircraftRepository aircraftRepository;
    private final ReleaseService releaseService;
    private final BusinessClock clock;
    private final DefectOverdueService overdueService;

    public DefectService(DefectRepository defectRepository, AircraftRepository aircraftRepository,
                         ReleaseService releaseService, BusinessClock clock,
                         DefectOverdueService overdueService) {
        this.defectRepository = defectRepository;
        this.aircraftRepository = aircraftRepository;
        this.releaseService = releaseService;
        this.clock = clock;
        this.overdueService = overdueService;
    }

    /**
     * 故障列表（含「只看当前机」，aircraftId 非空即该机）。只读：不在请求线程里逐条改状态，
     * 仅在跨日后预约一次异步批量校正；超期与否完全按日期口径即时派生，所以先开首页、
     * 直接超期查询、先逛故障页再回来，看到的结果一致。
     */
    @Transactional(readOnly = true)
    public List<Views.DefectView> list(Long aircraftId) {
        LocalDate today = clock.today();
        overdueService.requestRefresh();
        List<Defect> defects = aircraftId == null
                ? defectRepository.findAllByOrderByDefectNoAsc()
                : defectRepository.findAllByAircraftIdOrderByDefectNoAsc(aircraftId);
        Map<Long, String> tailMap = tailNoMap(null);
        List<Views.DefectView> list = new ArrayList<>();
        for (Defect defect : defects) {
            list.add(toView(defect, tailMap, today));
        }
        return list;
    }

    /** 超期清单：与顶部总览、列表标红同一口径（未关闭且到期日早于业务日），不依赖库里状态是否已置位 */
    @Transactional(readOnly = true)
    public List<Views.DefectView> overdue() {
        LocalDate today = clock.today();
        overdueService.requestRefresh();
        Map<Long, String> tailMap = tailNoMap(null);
        List<Views.DefectView> list = new ArrayList<>();
        for (Defect defect : defectRepository.findAllOverdue(today)) {
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
        // 补录历史缺陷（发现日期很早）时按同一跨日口径落初始状态；正常新登记都是 OPEN
        defect.setStatus(dueDate.isBefore(today) ? "OVERDUE" : "OPEN");
        defect.setReporter(req.getReporter() == null || req.getReporter().isBlank() ? null : req.getReporter().trim());
        defect.setUpdatedAt(clock.now());
        defect = defectRepository.save(defect);
        // 新增关联故障：待审放行的证据快照对不上当前事实，立即失效
        releaseService.invalidateOnFactChange(defect.getAircraftId(),
                "新增故障保留 " + defect.getDefectNo() + "（" + MroDict.defectStatusName(defect.getStatus()) + "）");
        return toView(defect, tailNoMap(aircraft), today);
    }

    /** 关闭缺陷：已关闭的不能再关；关闭后即使到期日已过也永不重新变超期 */
    @Transactional
    public Views.DefectView close(Long id) {
        Defect defect = defectRepository.findById(id)
                .orElseThrow(() -> new BizException("缺陷单不存在：id=" + id));
        if ("CLOSED".equals(defect.getStatus())) {
            throw new BizException("缺陷单 " + defect.getDefectNo() + " 已关闭，不能重复关闭");
        }
        defect.setStatus("CLOSED");
        defect.setUpdatedAt(clock.now());
        defect = defectRepository.save(defect);
        // 保留故障被闭环，也是维修事实变化：待审放行立即失效（差异视图可查）
        releaseService.invalidateOnFactChange(defect.getAircraftId(),
                "故障 " + defect.getDefectNo() + " 已关闭，维修事实变化");
        return toView(defect, tailNoMap(null), clock.today());
    }

    /**
     * 视图组装的唯一口径：
     * <ul>
     *   <li>CLOSED：永不超期；</li>
     *   <li>非关闭且 dueDate &lt; today：按超期展示（即使库里还没来得及被批量校正置成 OVERDUE）；</li>
     *   <li>dueDate == today：今天到期，仍有效（剩余 0 天），不算超期；</li>
     *   <li>其余：开放，剩余天数可正可零。</li>
     * </ul>
     */
    private Views.DefectView toView(Defect defect, Map<Long, String> tailMap, LocalDate today) {
        boolean closed = "CLOSED".equals(defect.getStatus());
        boolean overdue = !closed && defect.getDueDate().isBefore(today);
        boolean todayDue = !closed && !overdue && defect.getDueDate().isEqual(today);
        String effectiveStatus = overdue ? "OVERDUE" : defect.getStatus();
        long daysLeft = ChronoUnit.DAYS.between(today, defect.getDueDate());
        return new Views.DefectView(defect.getId(), defect.getDefectNo(), defect.getAircraftId(),
                tailMap.get(defect.getAircraftId()), defect.getAtaChapter(), defect.getDescription(),
                defect.getLimitUnit(), MroDict.limitUnitName(defect.getLimitUnit()), defect.getLimitValue(),
                defect.getLimitValue() + MroDict.limitUnitName(defect.getLimitUnit()),
                defect.getFoundDate(), defect.getDueDate(), effectiveStatus,
                MroDict.defectStatusName(effectiveStatus), defect.getReporter(), daysLeft, overdue, todayDue);
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
