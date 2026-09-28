package com.chris64233.cc.bloodbank.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 批次召回：血液中心按外部事件号发布，针对一个成分批次，记录原因与生效时间。
 * 外部事件号有数据库唯一约束：同号重放用于幂等判断，同号异内容返回冲突。
 */
@Entity
@Table(name = "recall")
public class Recall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 外部事件号，全局唯一 */
    @Column(nullable = false, unique = true, length = 64)
    private String eventNo;

    @Column(nullable = false, length = 64)
    private String batchNo;

    @Column(nullable = false, length = 512)
    private String reason;

    /** 召回生效时间：到达该时刻后批次单位立即不能参与新预留 */
    @Column(nullable = false)
    private Instant effectiveAt;

    @Column(nullable = false)
    private Instant publishedAt;

    /** 是否已处理（生效激活、受影响预留任务生成）；发布即生效时同步置真 */
    @Column(nullable = false)
    private boolean activated = false;

    private Instant activatedAt;

    protected Recall() {
    }

    public Recall(String eventNo, String batchNo, String reason, Instant effectiveAt, Instant publishedAt) {
        this.eventNo = eventNo;
        this.batchNo = batchNo;
        this.reason = reason;
        this.effectiveAt = effectiveAt;
        this.publishedAt = publishedAt;
    }

    /** 除外部事件号外的业务内容，用于同号重放冲突检测 */
    public boolean sameContentAs(String batchNo, String reason, Instant effectiveAt) {
        return this.batchNo.equals(batchNo)
                && this.reason.equals(reason)
                && this.effectiveAt.equals(effectiveAt);
    }

    public void markActivated(Instant at) {
        this.activated = true;
        this.activatedAt = at;
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

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public boolean isActivated() {
        return activated;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }
}
