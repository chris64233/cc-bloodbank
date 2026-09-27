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
     * 数据库层面的状态兜底：仅当单位仍处于 AVAILABLE 时才置为 RESERVED。
     * 返回受影响行数，0 表示并发下该单位已被其他事务占用。
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE BloodComponent c SET c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.RESERVED
            WHERE c.id = :id AND c.status = com.chris64233.cc.bloodbank.domain.ComponentStatus.AVAILABLE
            """)
    int reserveIfAvailable(@Param("id") Long id);
}
