package com.aircraft.mro.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aircraft.mro.domain.Defect;
import com.aircraft.mro.repository.DefectRepository;

/**
 * 故障跨日批量校正（唯一写状态的地方）。
 *
 * <p>设计目标（对应跨日一致性验收）：
 * <ul>
 *   <li><b>口径唯一</b>：只按 {@code OPEN && dueDate < 业务日} 把状态置成 OVERDUE。到期日 == 当天
 *       仍在有效期内，下一业务日才超期；CLOSED 永不参与，日期再早也不会复活成超期。</li>
 *   <li><b>读取不写库</b>：列表 / 只看当前机 / 超期查询 / 顶部总览全部按日期口径即时派生，
 *       普通查询不逐条 save、不更新 updatedAt、不重复写提示记录；它们只在业务日推进后
 *       “顺带预约”一次异步校正，请求线程立即返回，不等校正跑完。</li>
 *   <li><b>幂等可重入</b>：条件更新 {@code status='OPEN' AND dueDate<today}，重复执行/刷新列表
 *       第二次起零写入；状态变化只发生一次，updatedAt / 放行失效提示不随刷新抖动。</li>
 *   <li><b>失败可重试、故障隔离</b>：keyset 分批，每批独立事务；单条/单批异常只记录并继续后续批次，
 *       下一次触发（定时/任意读取）从断点重新扫描，漏网批次最终被补上。校正只异步进行，
 *       任何异常都不会让故障列表或顶部总览不可用。</li>
 * </ul>
 */
@Service
public class DefectOverdueService {

    private static final Logger log = LoggerFactory.getLogger(DefectOverdueService.class);

    /** 异步校正失败后的最小重试间隔，避免同业务日内每个请求都重试打日志 */
    private static final long MIN_RETRY_MILLIS = 30_000L;

    private final DefectRepository defectRepository;
    private final DefectOverdueNotifier notifier;
    private final BusinessClock clock;
    private final TransactionTemplate transactionTemplate;
    private final DefectOverdueService self;
    private final int batchSize;

    /** 同一时刻只允许一个校正在跑（异步触发 + 定时触发可能并发） */
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final Condition refreshFinished = refreshLock.newCondition();
    private volatile boolean running = false;
    /** 最近一次完成校正的业务日；同一天内已成功跑完就不再重复扫库 */
    private volatile LocalDate refreshedFor = null;
    /** 最近一次尝试的业务日（含失败），配合失败重试节流 */
    private volatile LocalDate attemptedFor = null;
    private volatile long lastAttemptAt = 0L;

    public DefectOverdueService(DefectRepository defectRepository,
                                DefectOverdueNotifier notifier,
                                BusinessClock clock,
                                PlatformTransactionManager transactionManager,
                                @org.springframework.context.annotation.Lazy DefectOverdueService self,
                                @Value("${mro.defect.overdue-batch-size:200}") int batchSize) {
        this.defectRepository = defectRepository;
        this.notifier = notifier;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.self = self;
        this.batchSize = batchSize <= 0 ? 200 : batchSize;
    }

    /** 进程启动后先校正一次：库里状态还是 OPEN 但日期已过的存量数据，首次读任何入口前就收敛 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        requestRefresh();
    }

    /** 每分钟兜底一次，覆盖服务运行期间跨过午夜的场景（跨日后无需任何人打开页面） */
    @Scheduled(fixedDelayString = "${mro.defect.overdue-schedule-millis:60000}", initialDelay = 5_000)
    public void scheduledRefresh() {
        requestRefresh();
    }

    /**
     * 预约一次异步校正。普通查询调用它：业务日没推进（同一天已成功跑过）直接返回，
     * 真正扫描在独立线程进行，不阻塞列表和总览；已有校正在跑则跳过（幂等）。
     */
    public void requestRefresh() {
        LocalDate today = clock.today();
        if (today.equals(refreshedFor)) {
            return;
        }
        if (running) {
            return;
        }
        // 失败重试节流：同一业务日 30s 内不因别的查询再扫一遍
        if (today.equals(attemptedFor) && System.currentTimeMillis() - lastAttemptAt < MIN_RETRY_MILLIS) {
            return;
        }
        self.runRefreshAsync();
    }

    /** 异步入口：异常只记日志，绝不向调用方（HTTP 查询）传播 */
    @org.springframework.scheduling.annotation.Async("defectOverdueExecutor")
    public void runRefreshAsync() {
        try {
            runRefresh();
        } catch (Exception e) {
            log.warn("故障超期批量校正本轮未完成，将在下次触发时重试：{}", e.getMessage());
        }
    }

    /**
     * 同步执行整轮校正（异步入口与测试都走这里）。整轮扫描持锁串行化：异步校正与显式调用
     * 不会并发改同一批行（否则会出现乐观更新冲突）；后到者等前一轮跑完后直接复用结果。
     *
     * @return 本轮真正从 OPEN 置为 OVERDUE 的条数（等待期间别人已跑完则返回 0）
     */
    public int runRefresh() {
        refreshLock.lock();
        try {
            // 已有一轮在跑（通常是查询触发的异步校正）：等它跑完，绝不并发起第二轮
            if (running) {
                while (running) {
                    refreshFinished.await();
                }
                return 0;
            }
            running = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            refreshLock.unlock();
        }

        LocalDate today = clock.today();
        attemptedFor = today;
        lastAttemptAt = System.currentTimeMillis();
        int flippedTotal = 0;
        boolean allOk = true;
        try {
            long afterId = 0L;
            while (true) {
                final long cursor = afterId;
                BatchResult result;
                try {
                    result = flipBatch(today, cursor);
                } catch (Exception e) {
                    // 单批失败：记录并从下一批继续，失败的 id 段本轮先跳过（下次触发重新扫描会补上）。
                    // 必须带 failed 标记，否则空结果会被误判成「已扫完」而提前结束整轮。
                    allOk = false;
                    log.warn("故障超期校正批次（id > {}）失败，将在下次触发时重试：{}", cursor, e.getMessage());
                    result = BatchResult.failed(cursor + batchSize);
                }
                flippedTotal += result.flippedCount();
                if (result.nextCursor() < 0 || (!result.failed() && result.scanned() < batchSize)) {
                    break;
                }
                afterId = result.nextCursor();
            }
        } finally {
            refreshLock.lock();
            try {
                if (allOk) {
                    refreshedFor = today;
                }
                running = false;
                refreshFinished.signalAll();
            } finally {
                refreshLock.unlock();
            }
        }
        if (flippedTotal > 0) {
            log.info("故障跨日校正完成：业务日 {}，新增超期 {} 条", today, flippedTotal);
        }
        return flippedTotal;
    }

    /** 是否有一轮校正正在执行（测试用） */
    public boolean isRunning() {
        return running;
    }

    /** 清除"某业务日已校正"记忆（测试切换业务日期时用；生产无需调用） */
    public void resetForNewBusinessDay() {
        refreshLock.lock();
        try {
            if (running) {
                throw new IllegalStateException("校正正在执行，不能复位");
            }
            refreshedFor = null;
            attemptedFor = null;
            lastAttemptAt = 0L;
        } finally {
            refreshLock.unlock();
        }
    }

    /** 等待校正空闲（测试用）：最多等 timeoutMillis */
    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        refreshLock.lock();
        try {
            while (running) {
                long remainNanos = deadline - System.nanoTime();
                if (remainNanos <= 0) {
                    return false;
                }
                refreshFinished.awaitNanos(remainNanos);
            }
            return true;
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 处理一批：把候选故障条件更新为 OVERDUE，并逐条通知放行失效。
     * 本方法整体在一个短事务内（{@link TransactionTemplate}），方法内再对每条通知做异常隔离。
     * protected 便于纯单元测试直接调用验证批次失败隔离。
     */
    protected BatchResult flipBatch(LocalDate today, long afterId) {
        return transactionTemplate.execute(status -> {
            List<Defect> candidates = defectRepository.findOpenOverdueCandidates(
                    today, afterId, PageRequest.of(0, batchSize));
            LocalDateTime now = clock.now();
            List<Defect> flipped = new ArrayList<>();
            for (Defect defect : candidates) {
                // 条件置位（再次按 OPEN 判定，天然幂等，并发下也不会把 CLOSED 改回 OVERDUE）
                defect.setStatus("OVERDUE");
                defect.setUpdatedAt(now);
                defectRepository.save(defect);
                flipped.add(defect);
            }
            // flush 保证状态落库后再通知；通知本身的放行失效由 ReleaseService 走独立事务
            defectRepository.flush();
            int flippedCount = 0;
            for (Defect defect : flipped) {
                try {
                    notifier.notifyOverdue(defect.getAircraftId(), defect.getDefectNo());
                } catch (Exception e) {
                    // 单条通知失败（例如放行模块瞬时异常）不影响本批状态校正结果，不阻断其他条目与后续批次
                    log.warn("故障 {} 超期后通知放行失效失败，不影响超期校正结果：{}",
                            defect.getDefectNo(), e.getMessage());
                }
                flippedCount++;
            }
            long nextCursor = candidates.size() < batchSize ? -1L
                    : candidates.get(candidates.size() - 1).getId();
            return new BatchResult(flipped, nextCursor, flippedCount, false);
        });
    }

    /** 一批校正结果。nextCursor&lt;0 表示扫描结束；failed=true 表示本批异常、结果为空但需继续后续批次 */
    protected record BatchResult(List<Defect> flipped, long nextCursor, int flippedCount, boolean failed) {
        static BatchResult failed(long skipToCursor) {
            return new BatchResult(List.of(), skipToCursor, 0, true);
        }

        int scanned() {
            return flipped.size();
        }
    }
}
