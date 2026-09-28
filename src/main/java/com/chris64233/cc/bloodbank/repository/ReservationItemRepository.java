package com.chris64233.cc.bloodbank.repository;

import com.chris64233.cc.bloodbank.domain.ReservationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ReservationItemRepository extends JpaRepository<ReservationItem, Long> {

    /** 一笔预留的全部明细（含已换血替换/释放的历史），查询展示用 */
    @Query("""
            SELECT i FROM ReservationItem i JOIN FETCH i.component
            WHERE i.reservation.id = :reservationId
            ORDER BY i.id ASC
            """)
    List<ReservationItem> findByReservationIdWithComponent(@Param("reservationId") Long reservationId);

    /** 一笔预留当前生效（未被换血替换/释放）的明细，发放、释放、换血等库存操作用 */
    @Query("""
            SELECT i FROM ReservationItem i JOIN FETCH i.component
            WHERE i.reservation.id = :reservationId AND i.active = true
            ORDER BY i.id ASC
            """)
    List<ReservationItem> findActiveByReservationId(@Param("reservationId") Long reservationId);

    /**
     * 召回建任务时：给定单位中当前仍被预留占用（active）的明细，
     * 用于定位受影响申请；返回预留是否仍 RESERVED 由调用方在行锁后再判断。
     */
    @Query("""
            SELECT i FROM ReservationItem i
            JOIN FETCH i.component c
            JOIN FETCH i.reservation r
            JOIN FETCH r.request
            WHERE c.id IN :componentIds AND i.active = true
            ORDER BY r.id ASC, i.id ASC
            """)
    List<ReservationItem> findActiveByComponentIds(@Param("componentIds") Collection<Long> componentIds);
}
