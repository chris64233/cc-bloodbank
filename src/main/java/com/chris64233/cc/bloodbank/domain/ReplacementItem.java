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

import java.time.Instant;

/**
 * 换血明细：一条记录对应一个被替换单位及其替代品，冗余记录触发召回，
 * 仅在换血一次性成功时写入（失败不保留半截替换关系）。
 */
@Entity
@Table(name = "replacement_item")
public class ReplacementItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private ReplacementTask task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recall_id", nullable = false)
    private BatchRecall recall;

    /** 被替换（召回）单位 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "old_component_id", nullable = false)
    private BloodComponent oldComponent;

    /** 替代单位 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "new_component_id", nullable = false)
    private BloodComponent newComponent;

    @Column(nullable = false)
    private Instant createdAt;

    protected ReplacementItem() {
    }

    public ReplacementItem(ReplacementTask task, BatchRecall recall,
                           BloodComponent oldComponent, BloodComponent newComponent, Instant createdAt) {
        this.task = task;
        this.recall = recall;
        this.oldComponent = oldComponent;
        this.newComponent = newComponent;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public ReplacementTask getTask() {
        return task;
    }

    public BatchRecall getRecall() {
        return recall;
    }

    public BloodComponent getOldComponent() {
        return oldComponent;
    }

    public BloodComponent getNewComponent() {
        return newComponent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
