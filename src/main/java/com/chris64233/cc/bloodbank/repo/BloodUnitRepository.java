package com.chris64233.cc.bloodbank.repo;

import com.chris64233.cc.bloodbank.domain.BloodType;
import com.chris64233.cc.bloodbank.domain.BloodUnit;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.UnitStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface BloodUnitRepository extends JpaRepository<BloodUnit, String> {

    /**
     * 选取并以行级写锁锁定候选单位：成分一致、血型相容、当前可用、当前未失效且能用到最晚使用时间。
     * 按失效时间最早优先、条码升序稳定排序。并发预留在此串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u from BloodUnit u
            where u.componentType = :component
              and u.bloodType in :bloodTypes
              and u.status = :status
              and u.expiresAt >= :now
              and u.expiresAt >= :latestUseAt
            order by u.expiresAt asc, u.barcode asc
            """)
    List<BloodUnit> lockCandidates(@Param("component") ComponentType component,
                                   @Param("bloodTypes") Collection<BloodType> bloodTypes,
                                   @Param("status") UnitStatus status,
                                   @Param("now") Instant now,
                                   @Param("latestUseAt") Instant latestUseAt);

    /**
     * 单单位条件状态更新，仅当前状态匹配时生效，返回受影响行数。并发状态机的数据库兜底。
     */
    @Modifying
    @Query("update BloodUnit u set u.status = :to "
            + "where u.barcode = :barcode and u.status = :from")
    int compareAndSetStatus(@Param("barcode") String barcode,
                            @Param("from") UnitStatus from,
                            @Param("to") UnitStatus to);

    /**
     * 批量条件状态更新，返回受影响行数；调用方必须校验行数与入集合大小一致。
     */
    @Modifying
    @Query("update BloodUnit u set u.status = :to "
            + "where u.barcode in :barcodes and u.status = :from")
    int compareAndSetStatusBatch(@Param("barcodes") Collection<String> barcodes,
                                 @Param("from") UnitStatus from,
                                 @Param("to") UnitStatus to);

    List<BloodUnit> findByStatusOrderByExpiresAtAscBarcodeAsc(UnitStatus status);

    List<BloodUnit> findAllByOrderByBarcodeAsc();
}
