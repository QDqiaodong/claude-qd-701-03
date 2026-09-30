package com.aircraft.mro;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.Bay;
import com.aircraft.mro.domain.Defect;
import com.aircraft.mro.dto.ReleaseDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.BayRepository;
import com.aircraft.mro.repository.DefectRepository;
import com.aircraft.mro.service.AdBootstrap;
import com.aircraft.mro.service.BusinessClock;
import com.aircraft.mro.service.DefectOverdueService;
import com.aircraft.mro.service.DefectService;
import com.aircraft.mro.service.HangarService;
import com.aircraft.mro.service.ReleaseService;

/**
 * 故障保留跨日一致性端到端验收（H2/MySQL 兼容模式 + JPA 自动建表）。
 *
 * <p>固定业务日为基准日，覆盖验收要求的全部场景：
 * <ul>
 *   <li>昨天到期仍开放 → 立即按超期（任意入口、无需先打开故障页）</li>
 *   <li>今天到期 → 仍有效，不算超期；明天到期 → 开放</li>
 *   <li>已关闭但日期已过 → 永不重新变超期</li>
 *   <li>不同读取顺序结果一致；重复刷新不产生额外状态变化（updatedAt/提示记录/统计数稳定）</li>
 *   <li>服务运行中跨过午夜（不重启、只拨时钟）→ 各入口同步变超期，待审放行随之失效</li>
 *   <li>进程重启后第一次查询（不触发任何校正直接读）也正确</li>
 *   <li>较多记录（数百条跨多批）查询稳定完成、校正幂等</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("adtest")
class DefectOverdueIntegrationTest {

    @Autowired DefectService defectService;
    @Autowired HangarService hangarService;
    @Autowired ReleaseService releaseService;
    @Autowired DefectOverdueService overdueService;
    @Autowired BusinessClock clock;
    @Autowired DefectRepository defectRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired BayRepository bayRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    static final LocalDate BASE = LocalDate.of(2026, 9, 15);

    /** 与模块七测试一样，关闭 AD 启动引导（本测试不涉及 AD，但上下文共享） */
    @TestConfiguration
    static class NoBootstrap {
        @Bean
        @Primary
        AdBootstrap adBootstrap() {
            return new AdBootstrap(null, null, null, null, null, null, null, null, null) {
                @Override public void boot() { }
                @Override public void seedCredentialHistory() { }
                @Override public void ensureBaselineJob() { }
            };
        }
    }

    @BeforeEach
    void clean() throws Exception {
        // 先让上一用例可能在跑的异步校正停下来，并把其"今天已校正"记忆复位（用例间会拨时钟）
        overdueService.awaitIdle(3000);
        overdueService.resetForNewBusinessDay();
        jdbcTemplate.execute("DELETE FROM release_event");
        jdbcTemplate.execute("DELETE FROM release_snapshot_defect");
        jdbcTemplate.execute("DELETE FROM release_snapshot_card");
        jdbcTemplate.execute("DELETE FROM release_snapshot");
        jdbcTemplate.execute("DELETE FROM release_order");
        jdbcTemplate.execute("DELETE FROM defect");
        jdbcTemplate.execute("DELETE FROM bay");
        jdbcTemplate.execute("DELETE FROM aircraft");
        clock.setDate(BASE);
    }

    private Long aircraft(String tailNo) {
        Aircraft a = new Aircraft();
        a.setTailNo(tailNo);
        a.setModel("B737");
        a.setAirline("验收航空");
        a.setAirworthy("AIRWORTHY");
        a.setUpdatedAt(LocalDateTime.now());
        return aircraftRepository.save(a).getId();
    }

    private Long occupiedBay(String bayNo, Long aircraftId) {
        Bay bay = new Bay();
        bay.setBayNo(bayNo);
        bay.setSizeClass("A");
        bay.setFitType("B737;A320;A330;ARJ21");
        bay.setLengthM(java.math.BigDecimal.valueOf(60));
        bay.setStatus("OCCUPIED");
        bay.setCurrentAircraftId(aircraftId);
        bay.setUpdatedAt(LocalDateTime.now());
        return bayRepository.save(bay).getId();
    }

    /** 直接按精确状态/日期造故障（绕开 create 的派生逻辑，才能造出"存量 OPEN 但日期已过"的脏数据） */
    private void defect(String no, Long acId, String status, LocalDate dueDate) {
        Defect d = new Defect();
        d.setDefectNo(no);
        d.setAircraftId(acId);
        d.setAtaChapter("32-11");
        d.setDescription("验收缺陷 " + no);
        d.setLimitUnit("DAY");
        d.setLimitValue(5);
        d.setFoundDate(dueDate.minusDays(5));
        d.setDueDate(dueDate);
        d.setStatus(status);
        d.setReporter("验收员");
        d.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 9, 0));
        defectRepository.save(d);
    }

    private Views.Overview overview() {
        return hangarService.overview();
    }

    private Map<String, Views.DefectView> listMap() {
        return defectService.list(null).stream()
                .collect(Collectors.toMap(Views.DefectView::defectNo, v -> v));
    }

    private java.util.Set<String> overdueNos() {
        return defectService.overdue().stream().map(Views.DefectView::defectNo)
                .collect(Collectors.toSet());
    }

    // ============================== 验收用例 ==============================

    @Test
    void boundary_yesterdayOverdue_todayValid_tomorrowOpen_closedNeverOverdue() {
        Long ac = aircraft("B-T001");
        defect("DF-Y", ac, "OPEN", BASE.minusDays(1));   // 昨天到期仍开放 → 超期
        defect("DF-T", ac, "OPEN", BASE);                // 今天到期 → 有效
        defect("DF-M", ac, "OPEN", BASE.plusDays(1));    // 明天到期 → 开放
        defect("DF-C", ac, "CLOSED", BASE.minusDays(30));// 已关闭、日期很早 → 永不超期
        defect("DF-CO", ac, "OVERDUE", BASE.minusDays(2));// 原本就标记超期 → 保持可解释

        Map<String, Views.DefectView> rows = listMap();
        assertTrue(rows.get("DF-Y").overdue());
        assertEquals("OVERDUE", rows.get("DF-Y").status());
        assertEquals(-1L, rows.get("DF-Y").daysLeft());

        assertFalse(rows.get("DF-T").overdue(), "到期日等于当天不能算超期");
        assertTrue(rows.get("DF-T").todayDue(), "到期日等于当天应标记今天到期");
        assertEquals(0L, rows.get("DF-T").daysLeft());
        assertEquals("OPEN", rows.get("DF-T").status());

        assertFalse(rows.get("DF-M").overdue());
        assertFalse(rows.get("DF-M").todayDue());
        assertEquals(1L, rows.get("DF-M").daysLeft());

        assertFalse(rows.get("DF-C").overdue(), "已关闭缺陷不能重新变成超期");
        assertFalse(rows.get("DF-C").todayDue());
        assertEquals("CLOSED", rows.get("DF-C").status());

        assertTrue(rows.get("DF-CO").overdue());

        // 顶部总览口径：未关闭且未超期 = DF-T/DF-M 2 条（今天到期仍算开放）；超期 = DF-Y/DF-CO 2 条
        Views.Overview ov = overview();
        assertEquals(2L, ov.defectOverdue());
        assertEquals(2L, ov.defectOpen());
        assertEquals(java.util.Set.of("DF-Y", "DF-CO"), overdueNos());
    }

    @Test
    void readOrderConsistent_overviewOverdueListReturnSameResult() throws Exception {
        Long ac = aircraft("B-T002");
        defect("DF-A", ac, "OPEN", BASE.minusDays(5));
        defect("DF-B", ac, "OPEN", BASE.minusDays(1));
        defect("DF-C", ac, "OPEN", BASE);
        // 顺序 1：先顶部总览
        Views.Overview o1 = overview();
        java.util.Set<String> od1 = overdueNos();
        Map<String, Views.DefectView> l1 = listMap();
        // 顺序 2：先超期清单，再列表，再总览
        java.util.Set<String> od2 = overdueNos();
        Map<String, Views.DefectView> l2 = listMap();
        Views.Overview o2 = overview();
        // 顺序 3：先故障列表（只看当前机）再其余
        List<Views.DefectView> only = defectService.list(ac);
        Views.Overview o3 = overview();
        java.util.Set<String> od3 = overdueNos();

        assertEquals(2L, o1.defectOverdue());
        assertEquals(o1.defectOverdue(), o2.defectOverdue());
        assertEquals(o2.defectOverdue(), o3.defectOverdue());
        assertEquals(o1.defectOpen(), o2.defectOpen());
        assertEquals(java.util.Set.of("DF-A", "DF-B"), od1);
        assertEquals(od1, od2);
        assertEquals(od1, od3);
        assertEquals(2, only.stream().filter(Views.DefectView::overdue).count());
        assertTrue(l1.get("DF-A").overdue() && l2.get("DF-A").overdue());
        assertTrue(l1.get("DF-C").todayDue() && l2.get("DF-C").todayDue());
    }

    @Test
    void refreshIsIdempotent_repeatedReadChangesNothing() throws Exception {
        Long ac = aircraft("B-T003");
        defect("DF-1", ac, "OPEN", BASE.minusDays(2));
        defect("DF-2", ac, "OPEN", BASE.minusDays(1));
        defect("DF-3", ac, "CLOSED", BASE.minusDays(10));

        // 第一次显式校正：2 条 OPEN 落库为 OVERDUE（先等启动/定时触发的异步轮跑完并串行化）
        assertTrue(overdueService.awaitIdle(3000));
        int first = overdueService.runRefresh();
        assertEquals(2, first);
        LocalDateTime ts1 = defectRepository.findByDefectNo("DF-1").orElseThrow().getUpdatedAt();

        // 再跑两轮 + 连续普通刷新：零新增变更
        assertEquals(0, overdueService.runRefresh());
        assertEquals(0, overdueService.runRefresh());
        defectService.list(null);
        defectService.overdue();
        hangarService.overview();
        defectService.list(null);
        assertTrue(overdueService.awaitIdle(3000));
        assertEquals(ts1, defectRepository.findByDefectNo("DF-1").orElseThrow().getUpdatedAt(),
                "重复刷新不能改动 updatedAt");
        assertEquals("OVERDUE", defectRepository.findByDefectNo("DF-1").orElseThrow().getStatus());
        assertEquals("CLOSED", defectRepository.findByDefectNo("DF-3").orElseThrow().getStatus());
        // 放行失效提示只应在真正置位时产生：没有待审放行 → 0 条 INVALIDATE 事件
        Long invalidEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM release_event WHERE event_type = 'INVALIDATE'", Long.class);
        assertEquals(0L, invalidEvents, "重复刷新不能重复产生提示/审计记录");
        assertEquals(2L, overview().defectOverdue());
    }

    @Test
    void midnightCrossing_withoutRestart_allEntriesFlipTogether() throws Exception {
        Long ac = aircraft("B-T004");
        occupiedBay("BAY-T04", ac);
        defect("DF-D1", ac, "OPEN", BASE);              // 基准日当天到期（有效）
        defect("DF-D2", ac, "OPEN", BASE.plusDays(1));  // 基准日明天到期
        // 基准日先把存量校正干净（本场景没有已过期数据）
        assertEquals(0, overdueService.runRefresh());
        assertEquals(0L, overview().defectOverdue());

        // 在基准日为该机发起一条放行并推进到待质量终审（2 条开放故障须逐条写保留依据）
        ReleaseDtos.CreateReq createReq = new ReleaseDtos.CreateReq();
        createReq.setAircraftId(ac);
        createReq.setInitiator("发起人");
        releaseService.create(createReq);
        Long orderId = releaseService.list(ac).get(0).id();
        // 逐条补保留依据（v1 草稿快照里的开放故障条目）
        List<Views.SnapshotDefectItem> v1Items = releaseService.detail(orderId).snapshots().get(0).defects();
        for (Views.SnapshotDefectItem item : v1Items) {
            ReleaseDtos.RetainBasisReq basis = new ReleaseDtos.RetainBasisReq();
            basis.setSnapshotDefectId(item.itemId());
            basis.setRetainBasis("按 MEL 保留，下次定检排除");
            basis.setOperator("发起人");
            releaseService.saveRetainBasis(orderId, basis);
        }
        ReleaseDtos.SubmitReq submitReq = new ReleaseDtos.SubmitReq();
        submitReq.setOperator("发起人");
        releaseService.submit(orderId, submitReq);
        ReleaseDtos.SignReq mech = new ReleaseDtos.SignReq();
        mech.setSigner("机械员");
        releaseService.mechSign(orderId, mech);
        assertEquals("WAIT_QA", releaseService.list(ac).get(0).status());

        // 服务运行期间跨过两个午夜（不重启进程）
        clock.setDate(BASE.plusDays(1));
        // 任意读取先预约异步校正；同时读取结果本身必须立刻正确（派生口径，不等校正）
        assertEquals(1L, overview().defectOverdue(), "跨日后第一次读顶部总览就必须是新口径");
        assertTrue(listMap().get("DF-D1").overdue());
        assertTrue(listMap().get("DF-D1").todayDue() == false);
        // 今天到期标记转移到 DF-D2
        assertTrue(listMap().get("DF-D2").todayDue());
        assertEquals(java.util.Set.of("DF-D1"), overdueNos());

        // 等待异步校正落库（启动/读取触发），随后放行应已被失效
        waitUntil(() -> "INVALID".equals(releaseService.list(ac).get(0).status()), 3000);
        waitUntil(() -> defectRepository.findByDefectNo("DF-D1").orElseThrow()
                .getStatus().equals("OVERDUE"), 3000);

        // 再过一天
        clock.setDate(BASE.plusDays(2));
        assertEquals(2L, overview().defectOverdue());
        assertEquals(java.util.Set.of("DF-D1", "DF-D2"), overdueNos());
        waitUntil(() -> defectRepository.findByDefectNo("DF-D2").orElseThrow()
                .getStatus().equals("OVERDUE"), 3000);
        // 多条读取结果始终一致
        assertEquals(overview().defectOverdue(), overdueNos().size());
    }

    @Test
    void firstQueryAfterRestart_isCorrectBeforeAnyRefresh() throws Exception {
        // 复位后不跑任何校正，模拟「进程重启后第一次查询」前的状态：库里 OPEN 但日期已过
        Long ac = aircraft("B-T005");
        defect("DF-R1", ac, "OPEN", BASE.minusDays(9));
        defect("DF-R2", ac, "OPEN", BASE);
        defect("DF-R3", ac, "CLOSED", BASE.minusDays(90));
        assertEquals("OPEN", defectRepository.findByDefectNo("DF-R1").orElseThrow().getStatus(),
                "用例前提：存量记录尚未被校正");

        // 只读入口必须立即给出正确结果（派生口径，不依赖校正时机）
        assertEquals(1L, overview().defectOverdue(), "重启后首次读顶部总览即正确");
        assertEquals(java.util.Set.of("DF-R1"), overdueNos(), "重启后首次读超期清单即正确");
        Map<String, Views.DefectView> rows = listMap();
        assertTrue(rows.get("DF-R1").overdue());
        assertTrue(rows.get("DF-R2").todayDue());
        assertFalse(rows.get("DF-R3").overdue());
        // 结构保证：list/overdue/overview 都是只读事务，普通查询不承担逐条写库；
        // 状态落库统一由异步校正做（这里等它跑完后应被幂等置成 OVERDUE）
        assertTrue(overdueService.awaitIdle(3000));
        assertEquals("OVERDUE", defectRepository.findByDefectNo("DF-R1").orElseThrow().getStatus());
        assertEquals("OPEN", defectRepository.findByDefectNo("DF-R2").orElseThrow().getStatus());
        assertEquals("CLOSED", defectRepository.findByDefectNo("DF-R3").orElseThrow().getStatus());
    }

    @Test
    void manyRecords_queriesStayStableAndCorrectionIsBatched() {
        Long ac = aircraft("B-T006");
        // 600 条：400 条已过日期（300 OPEN 脏数据 + 100 已 OVERDUE），100 今天，100 未来；另加 1 条已关闭老日期
        for (int i = 0; i < 300; i++) {
            defect(String.format("DF-O-%03d", i), ac, "OPEN", BASE.minusDays(1 + i % 50));
        }
        for (int i = 0; i < 100; i++) {
            defect(String.format("DF-V-%03d", i), ac, "OVERDUE", BASE.minusDays(60 + i % 30));
        }
        for (int i = 0; i < 100; i++) {
            defect(String.format("DF-T-%03d", i), ac, "OPEN", BASE);
        }
        for (int i = 0; i < 100; i++) {
            defect(String.format("DF-F-%03d", i), ac, "OPEN", BASE.plusDays(1 + i % 20));
        }
        defect("DF-OLD-CLOSED", ac, "CLOSED", BASE.minusYears(2));

        // 先确定性地跑完校正（插入期间未做任何读取，没有竞争轮次）：batch=50，300 条脏数据跨 6 批
        int flipped = overdueService.runRefresh();
        assertEquals(300, flipped);
        long stillOpenButOverdue = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM defect WHERE status = 'OPEN' AND due_date < ?", Long.class, BASE);
        assertEquals(0L, stillOpenButOverdue, "所有 OPEN 脏数据都应被分批校正");
        long closedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM defect WHERE status = 'CLOSED'", Long.class);
        assertEquals(1L, closedCount);

        // 校正完成后各入口在大量数据下仍稳定快速
        long t0 = System.currentTimeMillis();
        assertEquals(400L, overview().defectOverdue());
        assertEquals(400, defectService.overdue().size()); // 超期清单为全机库口径，此处只有一机 → 400
        assertEquals(601, defectService.list(null).size()); // 含 1 条已关闭
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed < 5000, "大量数据下查询应稳定快速完成，实际 " + elapsed + "ms");

        // 第二轮校正零写入、零等待
        long t1 = System.currentTimeMillis();
        assertEquals(0, overdueService.runRefresh());
        assertTrue(System.currentTimeMillis() - t1 < 3000);
        assertEquals(400L, overview().defectOverdue());
        assertEquals(200L, overview().defectOpen());
    }

    private interface Condition {
        boolean check() throws Exception;
    }

    private static void waitUntil(Condition cond, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        AssertionError last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (cond.check()) {
                    return;
                }
            } catch (AssertionError e) {
                last = e;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("条件等待超时", last);
    }
}
