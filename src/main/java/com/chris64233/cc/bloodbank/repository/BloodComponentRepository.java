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
     * 已被生效召回冻结的批次（单位状态为 RECALLED）不参与新预留。
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
            ORDER BY c.expiresAt ASC, c.barcode ASC
            """)
    List<BloodComponent> lockAvailableCandidates(@Param("type") ComponentType type,
                                                 @Param("aboGroups") Collection<AboGroup> aboGroups,
                                                 @Param("rhFactors") Collection<RhFactor> rhFactors,
                                                 @Param("now") Instant now,
                                                 @Param("latestUseTime") Instant latestUseTime);

    /**
     * 召回激活时锁定该批次当前在库（AVAILABLE）或已被预留（RESERVED）的全部单位，
     * 排序与预留候选查询一致（失效时间、条码），避免并发锁定多单位时顺序不一致死锁；
     * 已发放（ISSUED）或已召回（RECALLED）的不再处理。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM BloodComponent c
            WHERE c.batchNo = :batchNo
              AND c.status IN (com.chris64233.cc.bloodbank.domain.ComponentStatus.AVAILABLE,
                               com.chris64233.cc.bloodbank.domain.ComponentStatus.RESERVED)
            ORDER BY c.expiresAt ASC, c.barcode ASC
            """)
    List<BloodComponent> lockBatchForRecall(@Param("batchNo") String batchNo);

    /** 召回范围查询：批次下全部单位（含已发放/已冻结） */
    List<BloodComponent> findByBatchNoOrderByIdAsc(String batchNo);

    /**
     * 数据库层面的状态兜底：仅当单位仍处于 AVAILABLE 时才置为 RESERVED。
     * 返回受影响行数，0 表示并发下该单位已被其他事务占用。
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE BloodComponent c SET c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.RESERVED
            WHERE c.id = :id AND c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.AVAILABLE
            """)
    int reserveIfAvailable(@Param("id") Long id);

    /**
     * 发放兜底：仅当前仍被预留（RESERVED）的单位可置为 ISSUED；已被召回冻结（RECALLED）的不受影响。
     * 返回受影响行数，调用方据此判断是否混入被召回单位（数量不符则整笔回滚，绝不发放召回单位）。
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE BloodComponent c SET c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.ISSUED
            WHERE c.id IN :ids AND c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.RESERVED
            """)
    int issueReservedIn(@Param("ids") Collection<Long> ids);

    /**
     * 释放兜底：仅当前仍被预留（RESERVED）的合格单位回到 AVAILABLE；
     * 已被召回冻结（RECALLED）的单位保持冻结，不会被旧的释放操作退回库存。
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE BloodComponent c SET c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.AVAILABLE
            WHERE c.id IN :ids AND c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.RESERVED
            """)
    int releaseReservedIn(@Param("ids") Collection<Long> ids);
}
