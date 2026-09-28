package com.chris64233.cc.bloodbank.repository;

import com.chris64233.cc.bloodbank.domain.ReplacementItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReplacementItemRepository extends JpaRepository<ReplacementItem, Long> {

    @Query("""
            SELECT i FROM ReplacementItem i
            JOIN FETCH i.oldComponent
            JOIN FETCH i.newComponent
            JOIN FETCH i.recall
            WHERE i.task.id = :taskId
            ORDER BY i.id ASC
            """)
    List<ReplacementItem> findByTaskIdWithDetails(@Param("taskId") Long taskId);

    @Query("""
            SELECT i FROM ReplacementItem i
            JOIN FETCH i.oldComponent
            JOIN FETCH i.newComponent
            JOIN FETCH i.recall
            WHERE i.task.reservation.id = :reservationId
            ORDER BY i.id ASC
            """)
    List<ReplacementItem> findByReservationIdWithDetails(@Param("reservationId") Long reservationId);

    @Query("""
            SELECT i FROM ReplacementItem i
            JOIN FETCH i.oldComponent
            JOIN FETCH i.newComponent
            JOIN FETCH i.recall
            WHERE i.task.recall.id = :recallId
            ORDER BY i.id ASC
            """)
    List<ReplacementItem> findByRecallIdWithDetails(@Param("recallId") Long recallId);
}
