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
     * 条件状态流转：仅当预留仍处于 RESERVED 且状态版本未变（期间没有换血）时才置为 ISSUED。
     * 返回 1 表示本次流转生效，0 表示已被并发流转、已终态或持锁期间发生换血（应放弃本次操作）。
     * 该更新在事务末尾执行并清空上下文，确保随后的查询读到最新状态。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reservation r
            SET r.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.ISSUED, r.issuedAt = :at
            WHERE r.id = :id
              AND r.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.RESERVED
              AND r.stateVersion = :version
            """)
    int markIssuedIfReserved(@Param("id") Long id, @Param("at") Instant at, @Param("version") long version);

    /**
     * 条件状态流转：仅当预留仍处于 RESERVED 且状态版本未变时才置为 RELEASED。
     * 版本不匹配说明持锁期间发生换血，旧的释放必须放弃（不能把替代单位退回库存）。
     * 该更新在事务末尾执行并清空上下文，确保随后的查询读到最新状态。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reservation r
            SET r.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.RELEASED, r.releasedAt = :at
            WHERE r.id = :id
              AND r.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.RESERVED
              AND r.stateVersion = :version
            """)
    int markReleasedIfReserved(@Param("id") Long id, @Param("at") Instant at, @Param("version") long version);

    /** 人工确认无法换血：仅 RESERVED 可终止；在事务末尾执行并清空上下文，避免游离状态被回写 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reservation r
            SET r.status = com.chris64233.cc.bloodbank.domain.ReservationStatus.TERMINATED, r.releasedAt = :at,
                r.blocked = false
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
}
