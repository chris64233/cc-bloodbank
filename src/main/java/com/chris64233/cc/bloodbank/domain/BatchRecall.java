package com.chris64233.cc.bloodbank.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 批次召回：血液中心按外部事件号发布，包含召回批次、原因与生效时间。
 * 事件号唯一：同号同内容重复提交返回原记录，同号异内容冲突。
 * 生效时间到达后批次立即冻结（不能再参与新预留），并为受影响的未发放预留生成换血任务。
 */
@Entity
@Table(name = "batch_recall")
public class BatchRecall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 外部事件号，数据库唯一约束兜底幂等 */
    @Column(nullable = false, unique = true, length = 64)
    private String eventNo;

    /** 被召回的采供血批次号 */
    @Column(nullable = false, length = 64)
    private String batchNo;

    @Column(nullable = false, length = 512)
    private String reason;

    /** 生效时间：到达后批次冻结并触发换血 */
    @Column(nullable = false)
    private Instant effectiveAt;

    /** 是否已激活（冻结批次、生成换血任务） */
    @Column(nullable = false)
    private boolean activated = false;

    @Column
    private Instant activatedAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected BatchRecall() {
    }

    public BatchRecall(String eventNo, String batchNo, String reason, Instant effectiveAt, Instant createdAt) {
        this.eventNo = eventNo;
        this.batchNo = batchNo;
        this.reason = reason;
        this.effectiveAt = effectiveAt;
        this.createdAt = createdAt;
    }

    /** 除事件号外的业务内容，用于重放冲突检测 */
    public boolean sameContentAs(String batchNo, String reason, Instant effectiveAt) {
        return this.batchNo.equals(batchNo)
                && this.reason.equals(reason)
                && this.effectiveAt.equals(effectiveAt);
    }

    public Long getId() {
        return id;
    }

    public String getEventNo() {
        return eventNo;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public String getReason() {
        return reason;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public boolean isActivated() {
        return activated;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markActivated(Instant at) {
        this.activated = true;
        this.activatedAt = at;
    }
}
