package com.aircraft.mro;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.aircraft.mro.common.BusinessClock;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.Defect;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.ReleaseEvent;
import com.aircraft.mro.domain.ReleaseOrder;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.DefectRepository;
import com.aircraft.mro.repository.ReleaseEventRepository;
import com.aircraft.mro.repository.ReleaseOrderRepository;
import com.aircraft.mro.service.AdBootstrap;
import com.aircraft.mro.service.DefectOverdueReconciler;
import com.aircraft.mro.service.DefectService;
import com.aircraft.mro.service.HangarService;
import com.aircraft.mro.service.ReleaseService;

/**
 * 模块四「故障保留」跨日一致性端到端验收（H2 MySQL 兼容模式 + JPA 自动建表）。
 *
 * <p>覆盖：昨天到期仍开放 / 今天到期 / 明天到期 / 已关闭但日期已过 / 运行中跨午夜 /
 * 重启后首次读 / 不先开故障页直接读顶部统计与超期清单 / 不同读取顺序一致 /
 * 重复刷新无额外状态变更 / 大量记录查询稳定 / 后台校正失败可重试且单机异常不拖垮查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("defecttest")
class DefectOverdueIntegrationTest {

    @Autowired DefectService defectService;
    @Autowired HangarService hangarService;
    @Autowired DefectRepository defectRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired ReleaseOrderRepository orderRepository;
    @Autowired ReleaseEventRepository eventRepository;
    @Autowired BusinessClock clock;
    @Autowired DefectOverdueReconciler reconciler;
    @Autowired MockMvc mockMvc;
    @SpyBean ReleaseService releaseService;

    /** 关闭启动引导（基线作业/种子与本模块无关，保证确定性）；定时调度由 defecttest profile 关闭 */
    @TestConfiguration
    static class NoBootstrap {
        @Bean
        @Primary
        AdBootstrap adBootstrap() {
            return new AdBootstrap(null, null, null, null, null, null, null, null, null) {
                @Override
                public void boot() {
                }

                @Override
                public void seedCredentialHistory() {
                }

                @Override
                public void ensureBaselineJob() {
                }
            };
        }
    }

    private LocalDate today;

    @BeforeEach
    void clean() {
        eventRepository.deleteAll();
        orderRepository.deleteAll();
        defectRepository.deleteAll();
        aircraftRepository.deleteAll();
        reset(releaseService);
        // 固定在业务日期 D 的中午，避免测试本身撞到真实午夜
        today = LocalDate.of(2026, 6, 15);
        clock.setClock(Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.ofHours(8)),
                BusinessClock.BUSINESS_ZONE));
        reconciler.resetStateForTest();
        seedAircraft();
    }

    @AfterEach
    void tearDown() {
        reconciler.drainForTest();
        reset(releaseService);
        clock.reset();
    }

    private Long aircraftId;

    private void seedAircraft() {
        Aircraft a = new Aircraft();
        a.setTailNo("B-9001");
        a.setModel("B737");
        a.setAirline("测试航司");
        a.setAirworthy("AIRWORTHY");
        a.setUpdatedAt(LocalDateTime.now());
        aircraftId = aircraftRepository.save(a).getId();
    }

    private Long defect(String no, int dueOffsetDays, String status) {
        return defect(no, aircraftId, dueOffsetDays, status);
    }

    private Long defect(String no, Long acId, int dueOffsetDays, String status) {
        Defect d = new Defect();
        d.setDefectNo(no);
        d.setAircraftId(acId);
        d.setAtaChapter("32-11");
        d.setDescription("验收缺陷 " + no);
        d.setLimitUnit("DAY");
        d.setLimitValue(10);
        LocalDate due = today.plusDays(dueOffsetDays);
        d.setFoundDate(due.minusDays(10));
        d.setDueDate(due);
        d.setStatus(status);
        d.setReporter("测试员");
        d.setUpdatedAt(LocalDateTime.now().minusDays(20));
        return defectRepository.save(d).getId();
    }

    private Map<String, Views.DefectView> byNo(List<Views.DefectView> rows) {
        return rows.stream().collect(Collectors.toMap(Views.DefectView::defectNo, r -> r));
    }

    // ------------------------------------------------------------------
    // 1. 跨日边界：昨天到期=超期；今天到期仍有效；明天到期=开放；已关闭日期再早也不超期
    // ------------------------------------------------------------------
    @Test
    void boundary_yesterday_today_tomorrow_and_closed_old() {
        defect("DF-YESTERDAY", -1, MroDict.DEFECT_OPEN);
        defect("DF-TODAY", 0, MroDict.DEFECT_OPEN);
        defect("DF-TOMORROW", 1, MroDict.DEFECT_OPEN);
        defect("DF-CLOSED-OLD", -30, MroDict.DEFECT_CLOSED);

        Map<String, Views.DefectView> rows = byNo(defectService.list(null));

        Views.DefectView yesterday = rows.get("DF-YESTERDAY");
        assertTrue(yesterday.overdue());
        assertFalse(yesterday.dueToday());
        assertEquals(MroDict.DEFECT_OVERDUE, yesterday.status());
        assertEquals("超期", yesterday.statusName());
        assertEquals(-1, yesterday.daysLeft());

        Views.DefectView dueToday = rows.get("DF-TODAY");
        assertFalse(dueToday.overdue(), "到期日等于当天仍在有效期内");
        assertTrue(dueToday.dueToday());
        assertEquals(MroDict.DEFECT_OPEN, dueToday.status());
        assertEquals(0, dueToday.daysLeft());

        Views.DefectView tomorrow = rows.get("DF-TOMORROW");
        assertFalse(tomorrow.overdue());
        assertFalse(tomorrow.dueToday());
        assertEquals(MroDict.DEFECT_OPEN, tomorrow.status());
        assertEquals(1, tomorrow.daysLeft());

        Views.DefectView closedOld = rows.get("DF-CLOSED-OLD");
        assertFalse(closedOld.overdue(), "已关闭无论日期多早都不能算超期");
        assertFalse(closedOld.dueToday());
        assertEquals(MroDict.DEFECT_CLOSED, closedOld.status());

        // 顶部总览口径：1 条超期、2 条开放（今天+明天）
        Views.Overview ov = hangarService.overview();
        assertEquals(1, ov.defectOverdue());
        assertEquals(2, ov.defectOpen());

        // 超期清单口径完全一致，且不依赖先访问列表
        List<Views.DefectView> overdueRows = defectService.overdue();
        assertEquals(List.of("DF-YESTERDAY"), overdueRows.stream()
                .map(Views.DefectView::defectNo).toList());
    }

    // ------------------------------------------------------------------
    // 2. 不先打开故障页：首页总览 + 超期清单先读，数量/明细就是对的（读按日期派生）
    // ------------------------------------------------------------------
    @Test
    void overview_and_overdue_query_correct_without_visiting_defect_page() {
        defect("DF-A", -2, MroDict.DEFECT_OPEN);
        defect("DF-B", -5, MroDict.DEFECT_OPEN);
        defect("DF-C", 3, MroDict.DEFECT_OPEN);

        // 直接读顶部总览（相当于首页第一次进入）
        Views.Overview first = hangarService.overview();
        assertEquals(2, first.defectOverdue());
        assertEquals(1, first.defectOpen());

        // 直接用超期查询
        List<Views.DefectView> overdueRows = defectService.overdue();
        assertEquals(2, overdueRows.size());
        assertTrue(overdueRows.stream().allMatch(Views.DefectView::overdue));

        // 即使后台异步校正已经跑完，读结果在它完成前后都保持正确（读按日期派生，不靠这次写入）
        reconciler.drainForTest();
        assertEquals(2, defectRepository.countByStatus(MroDict.DEFECT_OVERDUE));
        // 且后台收敛不改变任何入口的口径
        assertEquals(2, hangarService.overview().defectOverdue());
        assertEquals(2, defectService.overdue().size());
    }

    // ------------------------------------------------------------------
    // 3. 不同读取顺序在同一时间窗口得到相同结果
    // ------------------------------------------------------------------
    @Test
    void different_read_orders_return_consistent_results() {
        defect("DF-A", -2, MroDict.DEFECT_OPEN);
        defect("DF-B", -1, MroDict.DEFECT_OPEN);
        defect("DF-C", 0, MroDict.DEFECT_OPEN);
        defect("DF-D", 2, MroDict.DEFECT_OPEN);

        // 顺序一：首页 → 超期清单 → 故障列表
        long o1 = hangarService.overview().defectOverdue();
        long q1 = defectService.overdue().size();
        long l1 = defectService.list(null).stream().filter(Views.DefectView::overdue).count();

        // 顺序二：故障列表 → 超期清单 → 首页（再来一轮）
        long l2 = defectService.list(null).stream().filter(Views.DefectView::overdue).count();
        long q2 = defectService.overdue().size();
        long o2 = hangarService.overview().defectOverdue();

        // 顺序三：只看当前飞机
        long c1 = defectService.list(aircraftId).stream().filter(Views.DefectView::overdue).count();

        assertEquals(2, o1);
        assertEquals(o1, q1);
        assertEquals(q1, l1);
        assertEquals(l1, l2);
        assertEquals(l2, q2);
        assertEquals(q2, o2);
        assertEquals(o2, c1);

        // 开放口径同样一致：今天到期 + 明天后到期 = 2
        assertEquals(2, hangarService.overview().defectOpen());
    }

    // ------------------------------------------------------------------
    // 4. 后台批量校正：一条 SQL 把存量 OPEN 置 OVERDUE，普通查询不逐条写库
    // ------------------------------------------------------------------
    @Test
    void reconciler_batch_marks_stale_open_and_reads_do_not_write() {
        Long idA = defect("DF-A", -3, MroDict.DEFECT_OPEN);
        defect("DF-B", 0, MroDict.DEFECT_OPEN);
        defect("DF-C", -10, MroDict.DEFECT_CLOSED);

        // 情况一：全新进程（lastSweptDate=null），纯读触发后台异步校正，但读本身不逐条写库。
        // 读结果在校正完成前后都正确；等异步任务跑完，库里被一条 UPDATE 批量收敛。
        assertEquals(MroDict.DEFECT_OVERDUE,
                byNo(defectService.list(null)).get("DF-A").status());
        reconciler.drainForTest();
        assertEquals(MroDict.DEFECT_OVERDUE, defectRepository.findById(idA).orElseThrow().getStatus());
        assertEquals(MroDict.DEFECT_OPEN, defectRepository.findByDefectNo("DF-B").orElseThrow().getStatus());
        assertEquals(MroDict.DEFECT_CLOSED, defectRepository.findByDefectNo("DF-C").orElseThrow().getStatus());
        assertEquals(today, reconciler.getLastSweptDate());

        // 情况二：当天已校正完成后，再多读/多校正都不再产生写入（updated_at 保持校正时刻的值）
        LocalDateTime updatedAtA = defectRepository.findById(idA).orElseThrow().getUpdatedAt();
        defectService.list(null);
        defectService.overdue();
        hangarService.overview();
        reconciler.drainForTest();
        reconciler.runSweepIfNeeded(today);
        assertEquals(updatedAtA, defectRepository.findById(idA).orElseThrow().getUpdatedAt());
    }

    // ------------------------------------------------------------------
    // 5. 重复刷新/重复校正不产生额外状态变更、updated_at 不抖动、不重复写提示记录
    // ------------------------------------------------------------------
    @Test
    void repeated_sweeps_and_refreshes_cause_no_churn() {
        Long idA = defect("DF-A", -1, MroDict.DEFECT_OPEN);
        defect("DF-B", -2, MroDict.DEFECT_OPEN);
        ReleaseOrder order = pendingOrder("RO-T-1", aircraftId);

        reconciler.runSweepIfNeeded(today);
        reconciler.drainForTest();
        LocalDateTime afterFirstSweep = defectRepository.findById(idA).orElseThrow().getUpdatedAt();
        List<ReleaseEvent> eventsAfterFirst = eventRepository.findAllByReleaseIdOrderByEventAtAscIdAsc(order.getId());
        long invalidEvents = eventsAfterFirst.stream().filter(e -> "INVALIDATE".equals(e.getEventType())).count();
        assertEquals(1, invalidEvents, "每机只失效一次、只产生一条提示记录");

        // 模拟反复刷新列表/总览
        for (int i = 0; i < 5; i++) {
            defectService.list(null);
            defectService.list(aircraftId);
            defectService.overdue();
            hangarService.overview();
        }
        reconciler.drainForTest();
        // 当天再手动多跑几次校正（幂等）
        reconciler.runSweepIfNeeded(today);
        reconciler.runSweepIfNeeded(today);

        assertEquals(afterFirstSweep, defectRepository.findById(idA).orElseThrow().getUpdatedAt(),
                "updated_at 不应因重复刷新而变化");
        assertEquals(invalidEvents, eventRepository.findAllByReleaseIdOrderByEventAtAscIdAsc(order.getId())
                .stream().filter(e -> "INVALIDATE".equals(e.getEventType())).count());
    }

    // ------------------------------------------------------------------
    // 6. 运行期间跨过午夜：今天到期次日即超期，各入口立即一致，无需重启/重开页面
    // ------------------------------------------------------------------
    @Test
    void crossing_midnight_makes_due_today_overdue_everywhere() {
        defect("DF-CROSS", 0, MroDict.DEFECT_OPEN);

        assertFalse(byNo(defectService.list(null)).get("DF-CROSS").overdue());
        assertEquals(0, hangarService.overview().defectOverdue());
        assertEquals(0, defectService.overdue().size());

        // 服务不重启，时钟拨到下一业务日 00:01（跨过午夜）
        clock.setClock(Clock.fixed(today.plusDays(1).atTime(0, 1).toInstant(ZoneOffset.ofHours(8)),
                BusinessClock.BUSINESS_ZONE));

        // 各入口立即按新日期展示（不依赖后台校正已落库）
        assertTrue(byNo(defectService.list(null)).get("DF-CROSS").overdue());
        assertEquals(1, hangarService.overview().defectOverdue());
        assertEquals(List.of("DF-CROSS"), defectService.overdue().stream()
                .map(Views.DefectView::defectNo).toList());
        assertEquals(0, hangarService.overview().defectOpen());

        // 异步校正完成后查库也一致
        reconciler.drainForTest();
        assertEquals(MroDict.DEFECT_OVERDUE, defectRepository.findByDefectNo("DF-CROSS").orElseThrow().getStatus());
        assertEquals(today.plusDays(1), reconciler.getLastSweptDate());
    }

    // ------------------------------------------------------------------
    // 7. 跨午夜时待审放行立即失效（只失效一次）
    // ------------------------------------------------------------------
    @Test
    void crossing_midnight_invalidates_pending_release_once() {
        defect("DF-CROSS", 0, MroDict.DEFECT_OPEN);
        ReleaseOrder order = pendingOrder("RO-CROSS", aircraftId);

        // 跨过午夜
        clock.setClock(Clock.fixed(today.plusDays(1).atTime(0, 1).toInstant(ZoneOffset.ofHours(8)),
                BusinessClock.BUSINESS_ZONE));
        reconciler.requestCheck("test-midnight");
        reconciler.drainForTest();

        ReleaseOrder reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(MroDict.REL_INVALID, reloaded.getStatus());
        assertNull(reloaded.getActiveAircraftId());
        long events = eventRepository.findAllByReleaseIdOrderByEventAtAscIdAsc(order.getId())
                .stream().filter(e -> "INVALIDATE".equals(e.getEventType())).count();
        assertEquals(1, events);
    }

    // ------------------------------------------------------------------
    // 8. 升级存量数据：库里状态 OPEN 但日期已过，"重启后第一次读"即正确并完成校正
    // ------------------------------------------------------------------
    @Test
    void legacy_stale_open_rows_correct_on_first_read_after_restart() {
        // 模拟升级前的存量脏数据：状态还停在 OPEN，到期日早已过去
        defect("DF-LEGACY", -40, MroDict.DEFECT_OPEN);

        // 模拟进程重启：新建一个 reconciler 实例（lastSweptDate 为空）
        DefectOverdueReconciler fresh = new DefectOverdueReconciler(clock, sweep);

        // 不进故障页，直接读顶部统计 —— 第一次读就正确
        assertEquals(1, hangarService.overview().defectOverdue());
        assertEquals(1, defectService.overdue().size());

        // 重启后的启动校正（ApplicationReadyEvent 等价调用）
        fresh.onReady();
        fresh.drainForTest();
        assertEquals(today, fresh.getLastSweptDate());
        assertEquals(MroDict.DEFECT_OVERDUE, defectRepository.findByDefectNo("DF-LEGACY").orElseThrow().getStatus());

        // 原本就 OVERDUE 的记录保持可解释
        defect("DF-ALREADY", -2, MroDict.DEFECT_OVERDUE);
        assertTrue(byNo(defectService.list(null)).get("DF-ALREADY").overdue());
    }

    @Autowired com.aircraft.mro.service.DefectOverdueSweep sweep;

    // ------------------------------------------------------------------
    // 9. 校正失败可重试：单台飞机放行失效抛异常，不拖垮查询，下轮自动补做且不重复
    // ------------------------------------------------------------------
    @Test
    void invalidation_failure_retried_and_queries_stay_available() {
        defect("DF-A", -1, MroDict.DEFECT_OPEN);
        ReleaseOrder order = pendingOrder("RO-RETRY", aircraftId);

        // 注入：第一次失效抛异常（模拟 DB 抖动/锁超时等单条异常）
        doThrow(new RuntimeException("注入失败：放行锁超时"))
                .when(releaseService).invalidateOnFactChange(eq(aircraftId), anyString());

        reconciler.runSweepIfNeeded(today);
        // 校正未完成：读路径照常可用且结果正确
        assertEquals(1, defectService.overdue().size());
        assertEquals(1, hangarService.overview().defectOverdue());
        assertEquals(1, reconciler.pendingCountForTest());
        assertNull(reconciler.getLastSweptDate());
        assertEquals(MroDict.REL_WAIT_QA, orderRepository.findById(order.getId()).orElseThrow().getStatus());

        // 恢复后下一轮自动补做
        reset(releaseService);
        reconciler.runSweepIfNeeded(today);
        assertEquals(0, reconciler.pendingCountForTest());
        assertEquals(today, reconciler.getLastSweptDate());
        assertEquals(MroDict.REL_INVALID, orderRepository.findById(order.getId()).orElseThrow().getStatus());
        long events = eventRepository.findAllByReleaseIdOrderByEventAtAscIdAsc(order.getId())
                .stream().filter(e -> "INVALIDATE".equals(e.getEventType())).count();
        assertEquals(1, events, "重试成功后只产生一条提示，没有重复记录");
    }

    // ------------------------------------------------------------------
    // 10. 大量记录下查询稳定完成（3000 条），且不逐条写库
    // ------------------------------------------------------------------
    @Test
    void large_dataset_queries_complete_and_remain_read_only() {
        int n = 3000;
        LocalDateTime marker = LocalDateTime.now().minusDays(100);
        for (int i = 0; i < n; i++) {
            int offset = (i % 9) - 4; // -4..4：含昨天/今天/明天等各边界
            String status = (i % 50 == 0) ? MroDict.DEFECT_CLOSED : MroDict.DEFECT_OPEN;
            Defect d = new Defect();
            d.setDefectNo(String.format("DF-BULK-%05d", i));
            d.setAircraftId(aircraftId);
            d.setAtaChapter("32-11");
            d.setDescription("批量验收");
            d.setLimitUnit("DAY");
            d.setLimitValue(10);
            LocalDate due = today.plusDays(offset);
            d.setFoundDate(due.minusDays(10));
            d.setDueDate(due);
            d.setStatus(status);
            d.setReporter("批量");
            d.setUpdatedAt(marker);
            defectRepository.save(d);
        }
        long expectedOverdue = defectRepository.findAllByOrderByDefectNoAsc().stream()
                .filter(d -> !MroDict.DEFECT_CLOSED.equals(d.getStatus()) && d.getDueDate().isBefore(today))
                .count();
        long expectedOpen = defectRepository.findAllByOrderByDefectNoAsc().stream()
                .filter(d -> MroDict.DEFECT_OPEN.equals(d.getStatus()) && !d.getDueDate().isBefore(today))
                .count();
        // 以数据库实际存储精度取基线（H2 DATETIME 会截掉纳秒）
        LocalDateTime baseline00001 = defectRepository.findByDefectNo("DF-BULK-00001").orElseThrow().getUpdatedAt();
        LocalDateTime baseline00004 = defectRepository.findByDefectNo("DF-BULK-00004").orElseThrow().getUpdatedAt();

        // 预置"今天已校正完成"：此时任何纯读都不应再触发后台写动作（确定性验证读不写库）
        reconciler.markSweptForTest(today);

        long t0 = System.currentTimeMillis();
        long listOverdue = defectService.list(null).stream().filter(Views.DefectView::overdue).count();
        long qOverdue = defectService.overdue().size();
        Views.Overview ov = hangarService.overview();
        long elapsed = System.currentTimeMillis() - t0;

        assertEquals(expectedOverdue, listOverdue);
        assertEquals(expectedOverdue, qOverdue);
        assertEquals(expectedOverdue, ov.defectOverdue());
        assertEquals(expectedOpen, ov.defectOpen());
        assertTrue(elapsed < 10000, "3000 条记录的读路径应稳定快速完成，实际 " + elapsed + "ms");

        // 纯读不写库：读了三遍，库里仍没有任何记录被置 OVERDUE，updated_at 也不抖动
        reconciler.drainForTest();
        assertEquals(0, defectRepository.countByStatus(MroDict.DEFECT_OVERDUE));
        assertEquals(baseline00001, defectRepository.findByDefectNo("DF-BULK-00001").orElseThrow().getUpdatedAt());
        assertEquals(baseline00004, defectRepository.findByDefectNo("DF-BULK-00004").orElseThrow().getUpdatedAt());

        // 模拟新一天/重启后显式校正一次即收敛全部
        reconciler.resetStateForTest();
        long s0 = System.currentTimeMillis();
        reconciler.runSweepIfNeeded(today);
        long sweepMs = System.currentTimeMillis() - s0;
        assertEquals(expectedOverdue, defectRepository.countByStatus(MroDict.DEFECT_OVERDUE));
        assertTrue(sweepMs < 15000, "批量校正应快速完成，实际 " + sweepMs + "ms");

        // 再次校正零写入（UPDATE 命中 0 行）
        reconciler.runSweepIfNeeded(today);
        assertEquals(expectedOverdue, defectRepository.countByStatus(MroDict.DEFECT_OVERDUE));
    }

    // ------------------------------------------------------------------
    // 11. 同一时间窗口并发读取：口径一致
    // ------------------------------------------------------------------
    @Test
    void concurrent_reads_agree() throws Exception {
        for (int i = 0; i < 100; i++) {
            defect(String.format("DF-C-%04d", i), (i % 5) - 2,
                    (i % 37 == 0) ? MroDict.DEFECT_CLOSED : MroDict.DEFECT_OPEN);
        }
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Map<Integer, Long> results = new HashMap<>();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    long v = switch (idx % 4) {
                        case 0 -> hangarService.overview().defectOverdue();
                        case 1 -> defectService.overdue().size();
                        case 2 -> defectService.list(null).stream().filter(Views.DefectView::overdue).count();
                        default -> defectService.list(aircraftId).stream().filter(Views.DefectView::overdue).count();
                    };
                    synchronized (results) {
                        results.put(idx, v);
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        long distinct = results.values().stream().distinct().count();
        assertEquals(1, distinct, "四个入口并发读到的超期数必须完全一致：" + results);
    }

    // ------------------------------------------------------------------
    // 12. HTTP 入口：三个接口（顶部总览 / 超期清单 / 故障列表）返回的口径一致，
    //     且"先查总览"与"先查列表"两种访问顺序结果完全相同
    // ------------------------------------------------------------------
    @Test
    void http_endpoints_agree_regardless_of_visit_order() throws Exception {
        defect("DF-WEB-1", -1, MroDict.DEFECT_OPEN);
        defect("DF-WEB-2", 0, MroDict.DEFECT_OPEN);
        defect("DF-WEB-3", -9, MroDict.DEFECT_CLOSED);
        reconciler.resetStateForTest();

        // 顺序 A：先顶部总览，再超期清单，再故障列表（模拟不进故障页）
        String overviewA = httpGet("/api/hangar/overview");
        String overdueA = httpGet("/api/defects/overdue");
        String listA = httpGet("/api/defects");
        reconciler.drainForTest();

        // 顺序 B：重启态（清掉进程内校正记忆）后先列表再总览再超期清单
        reconciler.resetStateForTest();
        String listB = httpGet("/api/defects");
        String overviewB = httpGet("/api/hangar/overview");
        String overdueB = httpGet("/api/defects/overdue");
        reconciler.drainForTest();

        assertEquals(longAt(overviewA, "defectOverdue"), 1L);
        assertEquals(longAt(overviewB, "defectOverdue"), 1L);
        assertEquals(longAt(overviewA, "defectOpen"), 1L, "今天到期仍计入开放");
        assertEquals(longAt(overviewB, "defectOpen"), 1L);
        assertEquals(1, countRows(overdueA));
        assertEquals(1, countRows(overdueB));
        assertEquals(countRows(listA), countRows(listB));
        assertEquals(countRows(overdueA), countRows(overdueB));
        assertTrue(overdueA.contains("DF-WEB-1"));
        assertFalse(overdueA.contains("DF-WEB-3"), "已关闭缺陷不能出现在超期清单");
        assertTrue(listA.contains("\"dueToday\":true") || listA.contains("\"dueToday\": true"));
    }

    private String httpGet(String url) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private long longAt(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        assertTrue(m.find(), "JSON 中未找到字段 " + field + "：" + json);
        return Long.parseLong(m.group(1));
    }

    private int countRows(String jsonArray) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"defectNo\"\\s*:\\s*\"").matcher(jsonArray);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private ReleaseOrder pendingOrder(String no, Long acId) {        ReleaseOrder order = new ReleaseOrder();
        order.setReleaseNo(no);
        order.setAircraftId(acId);
        order.setActiveAircraftId(acId);
        order.setStatus(MroDict.REL_WAIT_QA);
        order.setInitiator("发起人");
        order.setMechSigner("机械员");
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        return orderRepository.save(order);
    }
}
