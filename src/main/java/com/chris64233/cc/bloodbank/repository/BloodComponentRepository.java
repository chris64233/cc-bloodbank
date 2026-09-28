package com.chris64233.cc.bloodbank.repository;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.BloodComponent;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BloodComponentRepository extends JpaRepository<BloodComponent, Long> {

    Optional<BloodComponent> findByBarcode(String barcode);

    List<BloodComponent> findByStatusOrderByExpiresAtAscBarcodeAsc(ComponentStatus status);

    /**
     * 预留候选单位：成分一致、血型相容、未失效且失效时间不早于最晚使用时间。
     * 按失效时间最早优先、条码稳定排序；FOR UPDATE 悲观锁保证并发事务不会拿到同一批单位。
     * 已生效召回批次的单位（batchNo 出现在已激活召回中）一律不得参与新预留。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM BloodComponent c
            WHERE c.componentType = :type
              AND c.aboGroup IN :aboGroups
              AND c.rhFactor IN :rhFactors
              AND c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.AVAILABLE
              AND c.expiresAt > :now
              AND c.expiresAt >= :latestUseTime
              AND c.batchNo NOT IN (
                  SELECT rc.batchNo FROM Recall rc WHERE rc.activated = true
              )
            ORDER BY c.expiresAt ASC, c.barcode ASC
            """)
    List<BloodComponent> lockAvailableCandidates(@Param("type") ComponentType type,
                                                 @Param("aboGroups") Collection<AboGroup> aboGroups,
                                                 @Param("rhFactors") Collection<RhFactor> rhFactors,
                                                 @Param("now") Instant now,
                                                 @Param("latestUseTime") Instant latestUseTime);

    /**
     * 数据库层面的状态兜底：仅当单位仍处于 AVAILABLE 且所属批次未被生效召回时才置为 RESERVED。
     * 返回受影响行数，0 表示并发下该单位已被其他事务占用或批次刚刚被召回。
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE BloodComponent c SET c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.RESERVED
            WHERE c.id = :id
              AND c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.AVAILABLE
              AND c.batchNo NOT IN (
                  SELECT rc.batchNo FROM Recall rc WHERE rc.activated = true
              )
            """)
    int reserveIfAvailable(@Param("id") Long id);

    /** 召回批次内仍在库可用的单位：生效后立即锁定为 RECALLED，不再参与新预留 */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE BloodComponent c SET c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.RECALLED
            WHERE c.batchNo = :batchNo
              AND c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.AVAILABLE
            """)
    int markAvailableInBatchRecalled(@Param("batchNo") String batchNo);

    /** 激活召回时锁定该预留占用的批次单位（正常为 RESERVED；重入边界下可能已是 RECALLED） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM BloodComponent c
            WHERE c.batchNo = :batchNo
              AND c.status IN (com.chris64233.cc.bloodbank.domain.ComponentStatus.RESERVED,
                               com.chris64233.cc.bloodbank.domain.ComponentStatus.RECALLED)
              AND c.id IN (
                  SELECT i.component.id FROM ReservationItem i
                  WHERE i.reservation.id = :reservationId AND i.state = com.chris64233.cc.bloodbank.domain.ItemState.ACTIVE
              )
            ORDER BY c.id ASC
            """)
    List<BloodComponent> lockReservedOrRecalledInBatchForReservation(@Param("batchNo") String batchNo,
                                                                     @Param("reservationId") Long reservationId);

    /**
     * 换血替代候选：与新预留相同的相容/未失效/最晚使用时间规则，排除本预留当前已占用单位，
     * 排除一切已生效召回批次。按失效时间最早优先、条码升序，FOR UPDATE 锁定。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM BloodComponent c
            WHERE c.componentType = :type
              AND c.aboGroup IN :aboGroups
              AND c.rhFactor IN :rhFactors
              AND c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.AVAILABLE
              AND c.expiresAt > :now
              AND c.expiresAt >= :latestUseTime
              AND c.id NOT IN :occupiedIds
              AND c.batchNo NOT IN (
                  SELECT rc.batchNo FROM Recall rc WHERE rc.activated = true
              )
            ORDER BY c.expiresAt ASC, c.barcode ASC
            """)
    List<BloodComponent> lockReplacementCandidates(@Param("type") ComponentType type,
                                                   @Param("aboGroups") Collection<AboGroup> aboGroups,
                                                   @Param("rhFactors") Collection<RhFactor> rhFactors,
                                                   @Param("now") Instant now,
                                                   @Param("latestUseTime") Instant latestUseTime,
                                                   @Param("occupiedIds") Collection<Long> occupiedIds);

    long countByBatchNo(String batchNo);

    /** 召回范围查询：批次下全部单位（含在库/预留/发放/已隔离），按条码排序 */
    List<BloodComponent> findByBatchNoOrderByBarcodeAsc(String batchNo);
}