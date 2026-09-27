package com.chris64233.cc.bloodbank.repo;

import com.chris64233.cc.bloodbank.domain.Reservation;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, String> {

    @EntityGraph(attributePaths = {"items", "items.unit"})
    @Query("select r from Reservation r where r.requestNo = :requestNo")
    Optional<Reservation> findWithItems(@Param("requestNo") String requestNo);

    @EntityGraph(attributePaths = {"items", "items.unit"})
    @Query("select distinct r from Reservation r order by r.requestNo asc")
    List<Reservation> findAllWithItems();

    /**
     * 预留单条件状态流转，仅 RESERVED 可迁出，返回受影响行数。
     * 发放（RESERVED→ISSUED）与到期释放（RESERVED→RELEASED）并发时恰好一方成功。
     */
    @Modifying
    @Query("update Reservation r set r.status = :to "
            + "where r.requestNo = :requestNo and r.status = :from")
    int compareAndSetStatus(@Param("requestNo") String requestNo,
                            @Param("from") ReservationStatus from,
                            @Param("to") ReservationStatus to);

    /**
     * 确认发放专用条件更新：必须仍为 RESERVED 且当前时间早于截止时间。
     * 到点后发放必然落空，由到期释放接管，从数据库层杜绝“超期发放”。
     */
    @Modifying
    @Query("update Reservation r set r.status = :to "
            + "where r.requestNo = :requestNo and r.status = :from and r.expireAt > :now")
    int compareAndSetForIssue(@Param("requestNo") String requestNo,
                              @Param("from") ReservationStatus from,
                              @Param("to") ReservationStatus to,
                              @Param("now") Instant now);

    @Query("select r.requestNo from Reservation r "
            + "where r.status = :status and r.expireAt <= :now")
    List<String> findExpiredRequestNos(@Param("status") ReservationStatus status,
                                       @Param("now") Instant now);
}
