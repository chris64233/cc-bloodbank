package com.chris64233.cc.bloodbank.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一笔预留：对应一份临床申请，包含若干预留明细，有有效截止时间。
 * 状态流转 RESERVED -> ISSUED / RELEASED，终态不可逆；
 * 流转通过条件更新（WHERE status = 'RESERVED'）保证并发下只有一个生效。
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一笔申请至多一笔预留，数据库唯一约束兜底 */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false, unique = true)
    private ClinicalRequest request;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status = ReservationStatus.RESERVED;

    @Column(nullable = false)
    private Instant reservedAt;

    /** 预留有效截止时间，到期未发放应释放全部单位 */
    @Column(nullable = false)
    private Instant expiresAt;

    @Column
    private Instant issuedAt;

    @Column
    private Instant releasedAt;

    /**
     * 召回导致换血库存不足时置为 true：保留原预留关系但禁止发放。
     * 后续换血成功或释放/终止时清除（终态不再参与发放）。
     */
    @Column(nullable = false)
    private boolean blocked = false;

    /**
     * 预留状态版本号：每次成功换血递增。发放/到期释放的条件流转必须匹配持锁时读到的版本，
     * 一旦持锁期间发生换血（版本变化），基于旧单位集的发放/释放将整体不生效，
     * 防止"换血完成后又被旧的释放操作把替代单位退回库存"。
     */
    @Column(nullable = false)
    private long stateVersion = 0;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ReservationItem> items = new ArrayList<>();

    protected Reservation() {
    }

    public Reservation(ClinicalRequest request, Instant reservedAt, Instant expiresAt) {
        this.request = request;
        this.reservedAt = reservedAt;
        this.expiresAt = expiresAt;
    }

    public void addItem(ReservationItem item) {
        items.add(item);
        item.setReservation(this);
    }

    public Long getId() {
        return id;
    }

    public ClinicalRequest getRequest() {
        return request;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public Instant getReservedAt() {
        return reservedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public long getStateVersion() {
        return stateVersion;
    }

    public void incrementStateVersion() {
        this.stateVersion++;
    }

    public List<ReservationItem> getItems() {
        return items;
    }
}
