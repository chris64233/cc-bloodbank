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
 * 换血任务：一次批次召回影响到一笔尚未发放的预留时生成，
 * 同一预留可因不同批次召回存在多个任务（(reservation, recall) 唯一）。
 * 换血以预留为单位统一处理：该预留全部待处理任务涉及的受影响单位一次性替换，
 * 成功后所有待处理任务一并完成；库存不足则全部保持待处理、预留标记为不可发放。
 */
@Entity
@Table(name = "replacement_task", uniqueConstraints =
        @UniqueConstraint(name = "uk_replacement_task_reservation_recall",
                columnNames = {"reservation_id", "recall_id"}))
public class ReplacementTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    /** 触发本任务的召回 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recall_id", nullable = false)
    private BatchRecall recall;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReplacementStatus status = ReplacementStatus.PENDING;

    /** 库存不足等未解决原因，供查询展示 */
    @Column(length = 512)
    private String unresolvedReason;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant processedAt;

    protected ReplacementTask() {
    }

    public ReplacementTask(Reservation reservation, BatchRecall recall, Instant createdAt) {
        this.reservation = reservation;
        this.recall = recall;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Reservation getReservation() {
        return reservation;
    }

    public BatchRecall getRecall() {
        return recall;
    }

    public ReplacementStatus getStatus() {
        return status;
    }

    public void setStatus(ReplacementStatus status) {
        this.status = status;
    }

    public String getUnresolvedReason() {
        return unresolvedReason;
    }

    public void setUnresolvedReason(String unresolvedReason) {
        this.unresolvedReason = unresolvedReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
}
