package com.chris64233.cc.bloodbank.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 预留明细行：一笔预留中的一个被分配单位。
 *
 * <p>{@code active_unit_barcode} 为数据库生成列——仅当明细处于 ALLOCATED（有效预留）时等于单位条码，
 * 否则为 NULL。其上的唯一约束保证：同一血液单位在全库至多存在于一条有效明细中，
 * 从数据库层面兜底并发预留冲突；多个 NULL 不冲突，单位发放/释放后可再次出现于新预留。
 */
@Entity
@Table(name = "reservation_item", uniqueConstraints =
        @UniqueConstraint(name = "uk_item_active_unit", columnNames = "active_unit_barcode"))
public class ReservationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_no", nullable = false, updatable = false)
    private Reservation reservation;

    /** 在本笔预留中的分配序号（按 FEFO+条码排序） */
    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_barcode", nullable = false, updatable = false)
    private BloodUnit unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ItemStatus status = ItemStatus.ALLOCATED;

    /**
     * H2 生成列，请勿在应用中赋值。
     */
    @Column(name = "active_unit_barcode", insertable = false, updatable = false,
            columnDefinition = "VARCHAR(32) GENERATED ALWAYS AS "
                    + "(CASE WHEN status = 'ALLOCATED' THEN unit_barcode ELSE NULL END)")
    private String activeUnitBarcode;

    protected ReservationItem() {
    }

    public ReservationItem(Reservation reservation, int position, BloodUnit unit) {
        this.reservation = reservation;
        this.position = position;
        this.unit = unit;
        this.status = ItemStatus.ALLOCATED;
    }

    public Long getId() {
        return id;
    }

    public int getPosition() {
        return position;
    }

    public BloodUnit getUnit() {
        return unit;
    }

    public ItemStatus getStatus() {
        return status;
    }
}
