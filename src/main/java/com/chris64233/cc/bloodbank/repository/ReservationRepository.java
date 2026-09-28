package com.chris64233.cc.bloodbank.repository;

import com.chris64233.cc.bloodbank.domain.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByRequest_RequestNo(String requestNo);

    /** 发放/释放前按申请号行锁定预留，串行化并发的状态流转 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.request.requestNo = :requestNo")
    Optional<Reservation> findByRequestNoForUpdate(@Param("requestNo") String requestNo);

    /** 发放/释放前按行锁定预留，串行化并发的状态流转 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    /**
     * 条件状态流转：仅当预留仍处于 RESERVED 且未被召回阻断（issueBlocked=false）时才置为 ISSUED。
     * 返回 1 表示本次发放生效；0 表示已被并发流转、处于终态或含未换血完成的被召回单位。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reservation r
            SET r.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.ISSUED, r.issuedAt = :at
            WHERE r.id = :id
              AND r.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.RESERVED
              AND r.issueBlocked = false
            """)
    int markIssuedIfReserved(@Param("id") Long id, @Param("at") Instant at);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reservation r
            SET r.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.RELEASED, r.releasedAt = :at
            WHERE r.id = :id AND r.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.RESERVED
            """)
    int markReleasedIfReserved(@Param("id") Long id, @Param("at") Instant at);

    /** 人工确认无法换血：RESERVED -> TERMINATED 终态，条件流转保证并发下只有一个生效 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reservation r
            SET r.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.TERMINATED, r.releasedAt = :at
            WHERE r.id = :id AND r.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.RESERVED
            """)
    int markTerminatedIfReserved(@Param("id") Long id, @Param("at") Instant at);

    /** 已到期的生效中预留，按 id 排序避免与候选单位加锁顺序不一致造成死锁 */
    @Query("""
            SELECT r.id FROM Reservation r
            WHERE r.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.RESERVED
              AND r.expiresAt <= :now
            ORDER BY r.id ASC
            """)
    List<Long> findExpiredReservedIds(@Param("now") Instant now);

    /**
     * 受批次召回影响的生效预留：仍含占用该批次单位的 ACTIVE 明细。
     * 按预留 id 排序，激活召回时逐笔加行锁处理，避免加锁顺序不一致造成死锁。
     */
    @Query("""
            SELECT DISTINCT i.reservation.id FROM ReservationItem i
            WHERE i.state = com.chris64233.cc.bloodbank.domain.ItemState.ACTIVE
              AND i.component.batchNo = :batchNo
              AND i.reservation.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.RESERVED
            ORDER BY i.reservation.id ASC
            """)
    List<Long> findAffectedReservationIdsForBatch(@Param("batchNo") String batchNo);
}
