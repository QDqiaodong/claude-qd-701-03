package com.aircraft.mro.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aircraft.mro.domain.Defect;
import com.aircraft.mro.repository.DefectRepository;

/**
 * {@link DefectOverdueService} 纯单元测试：不依赖 Spring / 数据库，
 * 用内存假仓储验证「单批异常可重试、单条通知异常不拖垮整批」。
 */
class DefectOverdueServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    static final int BATCH = 50;

    /** 内存假仓储（只实现校正实际用到的方法，其余代理方法返回 null） */
    static class FakeRepo {
        final Map<Long, Defect> store = new HashMap<>();
        final List<Defect> saved = new ArrayList<>();
        RuntimeException failOnce;
        int flushCount = 0;

        DefectRepository proxy() {
            return (DefectRepository) Proxy.newProxyInstance(
                    DefectRepository.class.getClassLoader(),
                    new Class<?>[]{DefectRepository.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "findOpenOverdueCandidates" -> {
                            if (failOnce != null) {
                                RuntimeException e = failOnce;
                                failOnce = null;
                                throw e;
                            }
                            long afterId = (long) args[1];
                            Pageable pageable = (Pageable) args[2];
                            int limit = pageable.getPageSize();
                            List<Defect> page = store.values().stream()
                                    .filter(d -> "OPEN".equals(d.getStatus()) && d.getDueDate().isBefore((LocalDate) args[0])
                                            && d.getId() > afterId)
                                    .sorted(java.util.Comparator.comparing(Defect::getId))
                                    .limit(limit)
                                    .toList();
                            yield new ArrayList<>(page);
                        }
                        case "save" -> {
                            Defect d = (Defect) args[0];
                            store.put(d.getId(), d);
                            saved.add(d);
                            yield d;
                        }
                        case "flush" -> {
                            flushCount++;
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
        }

        static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
    }

    /** 空事务管理器：每批的事务边界照走，但不落任何真实资源 */
    private PlatformTransactionManager noopTxManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }

    private Defect defect(long id, String status, LocalDate due) {
        Defect d = new Defect();
        d.setId(id);
        d.setDefectNo("DF-" + id);
        d.setAircraftId(1L);
        d.setStatus(status);
        d.setDueDate(due);
        d.setFoundDate(due.minusDays(3));
        d.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 8, 0));
        return d;
    }

    private DefectOverdueService service(FakeRepo repo, DefectOverdueNotifier notifier) {
        BusinessClock clock = new BusinessClock("") {
        };
        clock.setDate(TODAY);
        return new DefectOverdueService(repo.proxy(), notifier, clock, noopTxManager(), null, BATCH);
    }

    @Test
    void singleBatchFailure_doesNotAbortRefresh_andNextRunRetries() {
        FakeRepo repo = new FakeRepo();
        // 跨 3 批：id 1..150 全部 OPEN 且过期
        for (long i = 1; i <= 150; i++) {
            Defect d = defect(i, "OPEN", TODAY.minusDays(1));
            repo.store.put(i, d);
        }
        AtomicInteger notified = new AtomicInteger();
        DefectOverdueService svc = service(repo, (ac, no) -> notified.incrementAndGet());

        // 第一次跑：第一批（id 1..50）查询直接抛错，必须跳过继续，后续两批成功
        repo.failOnce = new RuntimeException("模拟 DB 瞬时抖动");
        int flippedFirstRun = svc.runRefresh();
        assertEquals(100, flippedFirstRun, "失败批次之外的 100 条必须照常校正，单批异常不能让全部查询不可用");
        assertEquals(100, notified.get());
        assertEquals("OPEN", repo.store.get(1L).getStatus(), "失败批次状态保持 OPEN，等待重试");
        assertEquals("OVERDUE", repo.store.get(51L).getStatus());
        assertEquals("OVERDUE", repo.store.get(150L).getStatus());

        // 第二次跑：从断点全量重扫，漏网的第一批补上
        int flippedSecondRun = svc.runRefresh();
        assertEquals(50, flippedSecondRun);
        assertEquals(150, notified.get());
        assertEquals("OVERDUE", repo.store.get(1L).getStatus());

        // 第三次跑：全部已置位，零写入
        assertEquals(0, svc.runRefresh());
        assertEquals(150, notified.get());
    }

    @Test
    void singleNotifierFailure_doesNotBlockFlipOrOtherItems() {
        FakeRepo repo = new FakeRepo();
        for (long i = 1; i <= BATCH; i++) {
            Defect d = defect(i, "OPEN", TODAY.minusDays(2));
            repo.store.put(i, d);
        }
        AtomicInteger notified = new AtomicInteger();
        DefectOverdueService svc = service(repo, (ac, no) -> {
            int n = notified.incrementAndGet();
            if (n == 1) {
                throw new RuntimeException("模拟放行模块瞬时异常");
            }
            return 0;
        });

        int flipped = svc.runRefresh();
        assertEquals(BATCH, flipped, "通知异常的那条超期状态仍然校正成功");
        assertEquals(BATCH, notified.get(), "其余条目通知不受影响，全部逐条尝试");
        assertTrue(repo.store.values().stream().allMatch(d -> "OVERDUE".equals(d.getStatus())),
                "整批 50 条都应已置为 OVERDUE");
    }

    @Test
    void closedDefectsAreNeverTouched_evenWhenFarPastDue() {
        FakeRepo repo = new FakeRepo();
        repo.store.put(1L, defect(1, "CLOSED", TODAY.minusYears(5)));
        repo.store.put(2L, defect(2, "OPEN", TODAY));
        AtomicInteger notified = new AtomicInteger();
        DefectOverdueService svc = service(repo, (ac, no) -> notified.incrementAndGet());

        assertEquals(0, svc.runRefresh());
        assertEquals(0, notified.get());
        assertEquals("CLOSED", repo.store.get(1L).getStatus());
        assertEquals("OPEN", repo.store.get(2L).getStatus(), "今天到期的 OPEN 不能被置成超期");
    }
}
