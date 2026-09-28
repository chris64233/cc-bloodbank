package com.chris64233.cc.bloodbank.repository;

import com.chris64233.cc.bloodbank.domain.ReplacementStatus;
import com.chris64233.cc.bloodbank.domain.ReplacementTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ReplacementTaskRepository extends JpaRepository<ReplacementTask, Long> {

    /** 一笔预留的全部任务（含预留申请与召回），按 id 排序 */
    @Query("""
            SELECT t FROM ReplacementTask t
            JOIN FETCH t.reservation r
            JOIN FETCH r.request
            JOIN FETCH t.recall
            WHERE r.id = :reservationId
            ORDER BY t.id ASC
            """)
    List<ReplacementTask> findByReservationId(@Param("reservationId") Long reservationId);

    /** 一笔预留的待处理任务（含预留申请与召回） */
    @Query("""
            SELECT t FROM ReplacementTask t
            JOIN FETCH t.reservation r
            JOIN FETCH r.request
            JOIN FETCH t.recall
            WHERE r.id = :reservationId
              AND t.status = com.chris64233.cc.bloodbank.domain.ReplacementStatus.PENDING
            ORDER BY t.id ASC
            """)
    List<ReplacementTask> findPendingByReservationId(@Param("reservationId") Long reservationId);

    /** 预留已发放/到期释放/终止：其待处理任务作废 */
    @Modifying
    @Query("""
            UPDATE ReplacementTask t SET t.status = com.chris64233.cc.bloodbank.domain.ReplacementStatus.OBSOLETE,
                   t.processedAt = :at
            WHERE t.reservation.id = :reservationId
              AND t.status = com.chris64233.cc.bloodbank.domain.ReplacementStatus.PENDING
            """)
    int markPendingObsolete(@Param("reservationId") Long reservationId, @Param("at") Instant at);

    /** 一个召回事件产生的全部任务（含预留申请与召回） */
    @Query("""
            SELECT t FROM ReplacementTask t
            JOIN FETCH t.reservation r
            JOIN FETCH r.request
            JOIN FETCH t.recall
            WHERE t.recall.id = :recallId
            ORDER BY t.id ASC
            """)
    List<ReplacementTask> findByRecallId(@Param("recallId") Long recallId);

    long countByRecall_IdAndStatus(Long recallId, ReplacementStatus status);
}
