package com.chris64233.cc.bloodbank.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 预留明细：一笔预留分配到的一个血液成分单位。
 * 明细保留历史（释放/换血后不删除），因此 component_id 不加唯一约束；
 * "一个单位同一时刻至多属于一笔生效预留"由 blood_component.status 的
 * 条件更新（WHERE status = 'AVAILABLE'）在数据库层面兜底。
 * active=false 表示该单位已被召回换血替换或释放，仅作历史记录；
 * 发放与到期释放只操作 active=true 的当前明细。
 */
@Entity
@Table(name = "reservation_item")
public class ReservationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    private BloodComponent component;

    @Column(nullable = false)
    private boolean active = true;

    protected ReservationItem() {
    }

    public ReservationItem(BloodComponent component) {
        this.component = component;
    }

    public Long getId() {
        return id;
    }

    public Reservation getReservation() {
        return reservation;
    }

    public void setReservation(Reservation reservation) {
        this.reservation = reservation;
    }

    public BloodComponent getComponent() {
        return component;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
