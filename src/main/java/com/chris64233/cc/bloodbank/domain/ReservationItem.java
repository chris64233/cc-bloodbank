package com.chris64233.cc.bloodbank.domain;

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

/**
 * 预留明细：一笔预留分配到的一个血液成分单位。
 * 明细保留历史（释放/替换后不删除），因此 component_id 不加唯一约束；
 * "一个单位同一时刻至多属于一笔生效预留"由 blood_component.status 的
 * 条件更新（WHERE status = 'AVAILABLE'）在数据库层面兜底。
 * 行状态 {@link ItemState} 区分当前占用与被替换/释放的历史行。
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

    @Enumerated(EnumType.STRING)
    private ItemState state = ItemState.ACTIVE;

    /** 该行单位被哪一起召回影响（仅受影响行记录，历史溯源用） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recall_id")
    private Recall recall;

    protected ReservationItem() {
    }

    public ReservationItem(BloodComponent component) {
        this.component = component;
    }

    public void markReplaced(Recall recall) {
        this.state = ItemState.REPLACED;
        this.recall = recall;
    }

    public void markReleased() {
        this.state = ItemState.RELEASED;
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

    public ItemState getState() {
        return state;
    }

    public Recall getRecall() {
        return recall;
    }
}
