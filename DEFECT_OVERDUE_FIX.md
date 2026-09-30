# 故障保留「跨日超期」一致性修复 — 验收说明

## 1. 问题根因

修复前只有 `DefectService.list()`（用户进入故障列表）会把到期日已过的 OPEN 缺陷**逐条写库**
改成 OVERDUE。顶部总览（`countByStatus`）、超期清单（只查 status='OVERDUE'）都直接读状态列，
因此：

- 先开首页 → 总览把已过期 OPEN 算进“开放”，超期数偏小；
- 直接用超期查询 → 漏掉库里仍是 OPEN 的过期记录；
- 先进故障页再回首页 → 数量又变了。

业务状态被页面访问顺序改变，且刷新列表会反复 `save()`、抖动 `updated_at` 并反复触发放行失效。

## 2. 修复方案（单一口径 + 读派生 + 后台幂等校正）

统一时间边界（`MroDict`，所有入口共用）：

- **超期**：`status <> CLOSED 且 due_date < 业务日期`（到期日当天仍有效，下一业务日起才算超期）；
- **今天到期**：`status <> CLOSED 且 due_date = 业务日期`，与“已经超期”严格区分；
- **已关闭**：无论到期日多早都不再算超期，不会“复活”。

业务日期/时刻统一由 `BusinessClock`（Asia/Shanghai，可在测试中拨钟）提供。

| 入口 | 修复后行为 |
|---|---|
| 故障列表 `GET /api/defects` | 纯读，按日期派生生效状态，不写库；返回 `overdue` 与 `dueToday` 两个标记 |
| 只看当前飞机 `GET /api/defects?aircraftId=` | 同上，同一判据 |
| 超期查询 `GET /api/defects/overdue` | 改为按日期查“未关闭且到期日 < 今天”，不依赖状态列是否已置位 |
| 顶部总览 `GET /api/hangar/overview` | `defectOpen`/`defectOverdue` 全部按日期 COUNT，与列表/超期清单同口径 |
| 放行门禁/快照差异 | `ReleaseFactsCollector`、`ReleaseService` 全部改用同一判据与统一时钟 |

后台收敛（`DefectOverdueReconciler` + `DefectOverdueSweep(Tx)`）：

- 启动后（ApplicationReadyEvent）、每分钟定时、以及任意读请求发现“今天还没成功校正过”时，
  向**单线程 daemon worker** 异步投递一次校正；读请求立即返回、绝不阻塞、绝不逐条写库；
- 校正 = **一条批量 UPDATE**：`OPEN 且 due_date < 今天 → OVERDUE`，只更新真正变化的行；
  未变化时命中 0 行，重复刷新/重复校正零写入、`updated_at` 不抖动、不重复写放行失效提示；
- 置位事务先提交，再在事务外**按机号**（去重）失效待审放行，每架飞机各自独立 try/catch；
  单机失败进入待重试集合，下一分钟/下次读自动补做；任何失败都不影响查询，且动作幂等不产生重复记录；
- 跨午夜后业务日期变化即自动触发新一天校正；重启后第一次读按日期派生本就正确，启动校正随后落库。

前端（`DefectView.vue`）：状态标签区分「已经超期 / 今天到期 / 开放 / 已关闭」，
行颜色（红=超期、橙=今天到期）、剩余天数、页内计数与顶部总览完全同口径。

## 3. 随包交付的验收数据（mysql/schema.sql，两份 schema 已同步）

| 缺陷单 | 到期日 | 库内初始状态 | 修复后期望 |
|---|---|---|---|
| DF-2026-0015 | **昨天** | OPEN（升级存量脏数据） | 任一入口首读即按超期；后台批量置 OVERDUE |
| DF-2026-0016 | **今天** | OPEN | 「今天到期」橙色提示，仍计开放，不算超期 |
| DF-2026-0017 | **明天** | OPEN | 正常开放，剩余 1 天 |
| DF-2026-0018 | 30 天前 | CLOSED | 永不算超期，不进超期清单 |
| DF-2026-0003/0006/0012 | 已过去 | OVERDUE | 保持超期（可解释，不重复变更） |
| DF-2026-0004/0008 | 已过去 | CLOSED | 保持关闭 |

## 4. 自动化检查

```bash
cd backend
mvn -s settings.xml test                      # 全部 20 个用例
mvn -s settings.xml test -Dtest=DefectOverdueIntegrationTest   # 仅本模块 12 个
```

`DefectOverdueIntegrationTest`（H2 MySQL 兼容模式，固定可拨时钟）覆盖：

1. `boundary_yesterday_today_tomorrow_and_closed_old` — 昨天/今天/明天/已关闭但日期已过；
2. `overview_and_overdue_query_correct_without_visiting_defect_page` — 不先开故障页直接读顶部与超期清单；
3. `different_read_orders_return_consistent_results` — 首页↔列表↔超期查询↔只看本机 不同顺序结果一致；
4. `reconciler_batch_marks_stale_open_and_reads_do_not_write` — 纯读不写库；批量 UPDATE 一次收敛；
5. `repeated_sweeps_and_refreshes_cause_no_churn` — 重复刷新/校正无额外状态变更、无 updated_at/提示抖动；
6. `crossing_midnight_makes_due_today_overdue_everywhere` — 服务不重启跨午夜，各入口立即一致；
7. `crossing_midnight_invalidates_pending_release_once` — 跨午夜待审放行只失效一次；
8. `legacy_stale_open_rows_correct_on_first_read_after_restart` — 升级存量 + “重启”后首读正确；
9. `invalidation_failure_retried_and_queries_stay_available` — 失败可重试、单机异常不拖垮查询；
10. `large_dataset_queries_complete_and_remain_read_only` — 3000 条记录读路径稳定快速、读不写库、批量秒级收敛；
11. `concurrent_reads_agree` — 8 线程四入口同窗口并发读取超期数完全一致；
12. `http_endpoints_agree_regardless_of_visit_order` — HTTP 层两种访问顺序返回一致。

## 5. 手工冒烟（起栈后）

```bash
./start.sh
# 任意顺序反复调用，三个数字/明细始终一致：
curl -s localhost:$BACKEND_PORT/api/hangar/overview   # defectOpen / defectOverdue
curl -s localhost:$BACKEND_PORT/api/defects/overdue
curl -s "localhost:$BACKEND_PORT/api/defects?aircraftId=1"
```
