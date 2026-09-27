package com.chris64233.cc.bloodbank.repo;

import com.chris64233.cc.bloodbank.domain.ItemStatus;
import com.chris64233.cc.bloodbank.domain.ReservationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReservationItemRepository extends JpaRepository<ReservationItem, Long> {

    List<ReservationItem> findByReservationRequestNoOrderByPositionAsc(String requestNo);

    /**
     * 明细行条件状态流转，返回受影响行数。
     */
    @Modifying
    @Query("update ReservationItem i set i.status = :to "
            + "where i.reservation.requestNo = :requestNo and i.status = :from")
    int compareAndSetStatusForReservation(@Param("requestNo") String requestNo,
                                          @Param("from") ItemStatus from,
                                          @Param("to") ItemStatus to);
}
