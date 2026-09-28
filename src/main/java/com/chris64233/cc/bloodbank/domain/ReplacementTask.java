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

import java.time.Instant;

/**
 * 换血任务：一起生效召回影响到一笔尚未发放的预留时生成（每起召回 × 每笔预留至多一条，
 * 数据库唯一约束兜底）。任务要么一次性替换全部受影响单位（COMPLETED），
 * 要么库存不足停留 PENDING——此时原预留保留但标记不可发放，绝不只换一部分。
 */
@Entity
@Table(name = "replacement_task",
        uniqueConstraints = @UniqueConstraint(name = "uk_replacement_task_recall_reservation",
                columnNames = {"recall_id", "reservation_id"}))
public class ReplacementTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recall_id", nullable = false)
    private Recall recall;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReplacementStatus status = ReplacementStatus.PENDING;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    /** 未解决原因：如合格替代品不足 N 个、预留已过期等 */
    @Column(length = 512)
    private String unresolvedReason;

    protected ReplacementTask() {
    }

    public ReplacementTask(Recall recall, Reservation reservation, Instant createdAt) {
        this.recall = recall;
        this.reservation = reservation;
        this.createdAt = createdAt;
    }

    public void markCompleted(Instant at) {
        this.status = ReplacementStatus.COMPLETED;
        this.completedAt = at;
        this.unresolvedReason = null;
    }

    public void noteUnresolved(String reason) {
        this.unresolvedReason = reason;
    }

    public void markTerminated(Instant at, String reason) {
        this.status = ReplacementStatus.TERMINATED;
        this.completedAt = at;
        this.unresolvedReason = reason;
    }

    public void markAbandoned(Instant at, String reason) {
        this.status = ReplacementStatus.ABANDONED;
        this.completedAt = at;
        this.unresolvedReason = reason;
    }

    public Long getId() {
        return id;
    }

    public Recall getRecall() {
        return recall;
    }

    public Reservation getReservation() {
        return reservation;
    }

    public ReplacementStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getUnresolvedReason() {
        return unresolvedReason;
    }
}
