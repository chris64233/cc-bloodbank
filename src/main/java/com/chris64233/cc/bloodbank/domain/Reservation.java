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

    public List<ReservationItem> getItems() {
        return items;
    }
}
