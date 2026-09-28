package com.chris64233.cc.bloodbank.repository;

import com.chris64233.cc.bloodbank.domain.Recall;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RecallRepository extends JpaRepository<Recall, Long> {

    Optional<Recall> findByEventNo(String eventNo);

    /** 发布重放时按外部事件号行锁串行化；唯一约束同时兜底 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Recall r WHERE r.eventNo = :eventNo")
    Optional<Recall> findByEventNoForUpdate(@Param("eventNo") String eventNo);

    /** 已到生效时间但尚未激活的召回，按 id 稳定排序交给定时任务/手动触发处理 */
    @Query("""
            SELECT r FROM Recall r
            WHERE r.activated = false AND r.effectiveAt <= :now
            ORDER BY r.id ASC
            """)
    List<Recall> findDueActivations(@Param("now") Instant now);

    /** 批次是否存在已生效召回：释放单位时据此决定回库还是隔离 */
    boolean existsByBatchNoAndActivatedTrue(String batchNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Recall r WHERE r.id = :id")
    Optional<Recall> findByIdForUpdate(@Param("id") Long id);
}
