package com.chris64233.cc.bloodbank.repository;

import com.chris64233.cc.bloodbank.domain.ReservationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReservationItemRepository extends JpaRepository<ReservationItem, Long> {

    @Query("SELECT i FROM ReservationItem i JOIN FETCH i.component WHERE i.reservation.id = :reservationId ORDER BY i.id ASC")
    List<ReservationItem> findByReservationIdWithComponent(@Param("reservationId") Long reservationId);
}
