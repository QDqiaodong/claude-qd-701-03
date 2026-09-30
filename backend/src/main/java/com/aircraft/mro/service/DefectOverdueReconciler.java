package com.aircraft.mro.service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aircraft.mro.common.BusinessClock;

/**
 * 故障保留跨日校正调度器。
 *
 * <p>设计要点（对应跨日一致性验收口径）：
 * <ul>
 *   <li><b>读路径永远先按日期派生正确结果</b>（列表/只看本机/超期清单/顶部总览），
 *       因此任何入口在任何时刻读到的超期口径都一致，不要求先打开故障页。</li>
 *   <li><b>落库校正与读路径解耦</b>：启动后、每个业务分钟、以及任意读请求发现
 *       "今天还没校正过"时，都只是往单线程 worker 投递一次异步校正；
 *       普通查询不被阻塞，也不逐条写库。</li>
 *   <li><b>每天最多一次成功校正</b>：{@link #lastSweptDate} 记录最后成功校正到的业务日期；
 *       校正是按日期的幂等批量 UPDATE，重复刷新不会再产生状态变更、updated_at 抖动或提示记录。</li>
 *   <li><b>失败可重试、单条异常不拖垮查询</b>：worker 内逐机失效各自 try/catch，
 *       任何异常都不影响读路径；失败机号进入 {@link #pendingInvalidation}，
 *       下一分钟（或下一次读请求）继续重试，置位事务与失效动作都幂等，重试不产生重复记录。</li>
 *   <li><b>跨午夜</b>：业务日期一变，lastSweptDate 落后，下一次 tick/读请求即触发新一天的校正。</li>
 * </ul>
 */
@Component
public class DefectOverdueReconciler {

    private static final Logger log = LoggerFactory.getLogger(DefectOverdueReconciler.class);

    private final BusinessClock clock;
    private final DefectOverdueSweep sweep;

    /** 已成功校正到的业务日期（含启动前就已经干净的日期）；null 表示进程内还没校正过 */
    private volatile LocalDate lastSweptDate;
    /** 同一时刻只允许一个校正任务，杜绝并发重复校正 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 放行失效尚未成功的机号：批量置位已提交但逐机失效失败时，靠它保证后续重试不漏机 */
    private final Set<Long> pendingInvalidation = ConcurrentHashMap.newKeySet();

    /** 单线程 daemon worker：校正不占 Web 请求线程，任务串行、天然去重 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "defect-overdue-reconciler");
        t.setDaemon(true);
        return t;
    });

    public DefectOverdueReconciler(BusinessClock clock, DefectOverdueSweep sweep) {
        this.clock = clock;
        this.sweep = sweep;
    }

    /** 启动后先跑一次：升级库里"状态仍 OPEN 但日期已过"的存量数据，第一次读就正确 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        requestCheck("application-ready");
    }

    /** 每分钟兜底一次：服务运行期间跨过午夜后，最晚一分钟内完成后台收敛 */
    @Scheduled(cron = "10 * * * * *")
    public void scheduledTick() {
        requestCheck("scheduled-tick");
    }

    /**
     * 读路径入口：发现今天还没成功校正过、或还有失效待重试时，异步投递一次校正并立即返回。
     * 绝不阻塞、绝不抛异常——读结果本身按日期派生，校正只是后台收敛。
     */
    public void requestCheck(@SuppressWarnings("unused") String trigger) {
        LocalDate today = clock.today();
        if (today.equals(lastSweptDate) && pendingInvalidation.isEmpty()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    // 以 worker 实际执行时刻的业务日期为准，避免任务排队跨过午夜时用了旧日期
                    runSweepIfNeeded(clock.today());
                } catch (Throwable t) {
                    // 校正失败不影响任何查询；不推进 lastSweptDate，下一分钟/下次读取自动重试
                    log.warn("故障跨日校正失败（业务日期 {}），将自动重试：{}", clock.today(), t.toString());
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            // executor 关停等极端情况下也要放行读请求
            running.set(false);
            log.warn("故障跨日校正任务投递失败，跳过本次后台收敛：{}", rejected.toString());
        }
    }

    /**
     * 执行一次校正（由单线程 worker 调用；测试可直接同步调用验证）。
     *
     * <p>先补做历史失败机号的放行失效（幂等），再跑当天批量置位；
     * 只有"无存量 OPEN 且无待补失效"时才把当天标记为已完成。
     * 单机失效异常只影响该机号的待重试集合，不阻断其他飞机与任何读路径。
     */
    public void runSweepIfNeeded(LocalDate today) {
        // 1) 先重试之前失败的单机失效（置位事务当时已提交，只差这一步）
        retryPending();

        // 2) 再处理今天的批量置位（内部：短事务置位提交后，事务外逐机失效）
        if (!today.equals(lastSweptDate) || sweep.hasStaleOpen(today)) {
            List<Long> failed = sweep.sweepOnce(today);
            if (!failed.isEmpty()) {
                pendingInvalidation.addAll(failed);
            }
        }

        // 3) 全部干净才推进日期；任一机未完成则下轮继续
        if (pendingInvalidation.isEmpty() && !sweep.hasStaleOpen(today)) {
            lastSweptDate = today;
        }
    }

    private void retryPending() {
        Set<Long> snapshot = new LinkedHashSet<>(pendingInvalidation);
        for (Long aircraftId : snapshot) {
            try {
                sweep.retryInvalidation(aircraftId);
                pendingInvalidation.remove(aircraftId);
            } catch (RuntimeException e) {
                log.warn("机号 {} 的待审放行失效重试仍失败，下轮继续：{}", aircraftId, e.toString());
            }
        }
    }

    /** 测试/运维用：最后成功校正到的业务日期 */
    public LocalDate getLastSweptDate() {
        return lastSweptDate;
    }

    /** 测试用：尚未完成放行失效、等待重试的机号数 */
    public int pendingCountForTest() {
        return pendingInvalidation.size();
    }

    /** 测试用：把"今天已校正完成"预置到位（用于证明纯读不触发任何写动作） */
    public void markSweptForTest(LocalDate date) {
        lastSweptDate = date;
    }

    /** 测试用：清空进程内校正状态（模拟全新进程 / 用例间隔离） */
    public void resetStateForTest() {
        lastSweptDate = null;
        pendingInvalidation.clear();
    }

    /** 测试用：同步等待已投递的校正任务完成（未投递则立即返回） */
    public void drainForTest() {
        try {
            worker.submit(() -> {
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
