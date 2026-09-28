package com.chris64233.cc.bloodbank.repository;

import com.chris64233.cc.bloodbank.domain.ReplacementStatus;
import com.chris64233.cc.bloodbank.domain.ReplacementTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReplacementTaskRepository extends JpaRepository<ReplacementTask, Long> {

    List<ReplacementTask> findByRecall_EventNoOrderByIdAsc(String eventNo);

    List<ReplacementTask> findByReservation_IdOrderByIdAsc(Long reservationId);

    Optional<ReplacementTask> findByRecall_EventNoAndReservation_Request_RequestNo(String eventNo, String requestNo);

    /** 执行/终止任务前行锁，保证同一任务重复/并发执行串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM ReplacementTask t WHERE t.id = :id")
    Optional<ReplacementTask> findByIdForUpdate(@Param("id") Long id);

    /**
     * 预留终止/到期后，其全部仍在 PENDING 的任务作废。
     * 终态任务（COMPLETED/TERMINATED/ABANDONED）保持不变。
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ReplacementTask t
            SET t.status = com.chris64233.cc.bloodbank.domain.ReplacementStatus.ABANDONED,
                t.completedAt = :at,
                t.unresolvedReason = :reason
            WHERE t.reservation.id = :reservationId
              AND t.status = com.chris64233.cc.bloodbank.domain.ReplacementStatus.PENDING
            """)
    int abandonPendingForReservation(@Param("reservationId") Long reservationId,
                                     @Param("at") Instant at,
                                     @Param("reason") String reason);

    /** 人工确认无法换血：指定任务记 TERMINATED（仅当仍 PENDING 时生效，幂等） */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ReplacementTask t
            SET t.status = com.chris64233.cc.bloodbank.domain.ReplacementStatus.TERMINATED,
                t.completedAt = :at,
                t.unresolvedReason = :reason
            WHERE t.id = :taskId
              AND t.status = com.chris64233.cc.bloodbank.domain.ReplacementStatus.PENDING
            """)
    int terminateByIdIfPending(@Param("taskId") Long taskId,
                               @Param("at") Instant at,
                               @Param("reason") String reason);
}
