package com.aircraft.mro.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /**
     * 跨日批量校正候选：状态仍为 OPEN 且到期日早于业务日的故障。
     * 时间边界统一为 dueDate &lt; today（到期日 == 当天仍有效，下一业务日才超期），
     * 已关闭（CLOSED）无论日期多早都不在候选内，绝不重新变超期。
     * keyset 分页（id 升序），配合条件更新，批量校正可重试、不卡长事务。
     */
    @Query("select d from Defect d where d.status = 'OPEN' and d.dueDate < :today and d.id > :afterId "
            + "order by d.id asc")
    List<Defect> findOpenOverdueCandidates(@Param("today") LocalDate today,
                                           @Param("afterId") long afterId,
                                           Pageable pageable);

    /** 未关闭且到期日早于业务日（超期口径，列表/只看当前机/超期查询/顶部总览共用） */
    @Query("select count(d) from Defect d where d.status <> 'CLOSED' and d.dueDate < :today")
    long countOverdue(@Param("today") LocalDate today);

    /** 未关闭且到期日不早于业务日（开放口径，含今天到期） */
    @Query("select count(d) from Defect d where d.status <> 'CLOSED' and d.dueDate >= :today")
    long countOpen(@Param("today") LocalDate today);

    /** 超期清单：与 {@link #countOverdue(LocalDate)} 同一口径，不依赖库里是否已置成 OVERDUE */
    @Query("select d from Defect d where d.status <> 'CLOSED' and d.dueDate < :today order by d.dueDate asc, d.defectNo asc")
    List<Defect> findAllOverdue(@Param("today") LocalDate today);
}
