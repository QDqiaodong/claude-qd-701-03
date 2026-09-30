package com.aircraft.mro.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aircraft.mro.domain.Defect;

public interface DefectRepository extends JpaRepository<Defect, Long> {

    List<Defect> findAllByOrderByDefectNoAsc();

    List<Defect> findAllByAircraftIdOrderByDefectNoAsc(Long aircraftId);

    Optional<Defect> findByDefectNo(String defectNo);

    boolean existsByDefectNo(String defectNo);

    List<Defect> findAllByStatusOrderByDueDateAsc(String status);

    long countByStatus(String status);

    // ---------------- 跨日超期口径：日期派生查询（与 MroDict.defect* 判据保持一致） ----------------

    /** 生效超期：未关闭（OPEN/OVERDUE 都算）且到期日早于业务日期 */
    @Query("select d from Defect d where d.status <> 'CLOSED' and d.dueDate < :today order by d.dueDate asc, d.defectNo asc")
    List<Defect> findEffectiveOverdue(@Param("today") LocalDate today);

    /** 生效超期数量：未关闭且到期日早于业务日期（顶部"超期保留"口径） */
    @Query("select count(d) from Defect d where d.status <> 'CLOSED' and d.dueDate < :today")
    long countEffectiveOverdue(@Param("today") LocalDate today);

    /** 生效开放数量：OPEN 且到期日不早于业务日期（到期日当天仍算开放） */
    @Query("select count(d) from Defect d where d.status = 'OPEN' and d.dueDate >= :today")
    long countEffectiveOpen(@Param("today") LocalDate today);

    /** 库里仍挂 OPEN 但已到超期边界的记录涉及的机号（后台校正时按机失效待审放行，去重） */
    @Query("select distinct d.aircraftId from Defect d where d.status = 'OPEN' and d.dueDate < :today")
    List<Long> findStaleOpenAircraftIds(@Param("today") LocalDate today);

    /**
     * 批量校正：OPEN 且到期日早于业务日期 → OVERDUE，一条 SQL 完成。
     * 只真正发生状态变化的行才更新 updated_at，重复执行零写入、零抖动。
     */
    @Modifying
    @Query("update Defect d set d.status = 'OVERDUE', d.updatedAt = :now "
            + "where d.status = 'OPEN' and d.dueDate < :today")
    int markOpenOverdue(@Param("today") LocalDate today, @Param("now") java.time.LocalDateTime now);
}
