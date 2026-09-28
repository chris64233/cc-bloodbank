package com.chris64233.cc.bloodbank.repository;

import com.chris64233.cc.bloodbank.domain.BatchRecall;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BatchRecallRepository extends JpaRepository<BatchRecall, Long> {

    Optional<BatchRecall> findByEventNo(String eventNo);

    /** 已到生效时间但尚未激活的召回，按 id 排序保证加锁顺序稳定 */
    @Query("""
            SELECT r FROM BatchRecall r
            WHERE r.activated = false AND r.effectiveAt <= :now
            ORDER BY r.id ASC
            """)
    List<BatchRecall> findDueRecalls(@Param("now") Instant now);

    /** 激活处理前行锁，串行化同事件号的并发提交/激活 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM BatchRecall r WHERE r.id = :id")
    Optional<BatchRecall> findByIdForUpdate(@Param("id") Long id);

    List<BatchRecall> findAllByOrderByIdAsc();
}
