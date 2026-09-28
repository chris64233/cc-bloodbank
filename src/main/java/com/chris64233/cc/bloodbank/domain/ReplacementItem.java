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
import jakarta.persistence.UniqueConstraint;

/**
 * 换血明细：受影响单位与替代单位一一配对，记录触发召回与处理结果。
 * 任务生成时按"受影响单位"落行（替代单位暂空）；替换成功时回填替代单位。
 * (任务, 受影响单位) 唯一，保证同一任务对同一单位只换一次。
 */
@Entity
@Table(name = "replacement_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_replacement_item_task_removed",
                columnNames = {"task_id", "removed_component_id"}))
public class ReplacementItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private ReplacementTask task;

    /** 被替换的被召回单位（生成任务时确定） */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "removed_component_id", nullable = false)
    private BloodComponent removedComponent;

    /** 替代单位：库存不足未替换时为空，成功时回填 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replacement_component_id")
    private BloodComponent replacementComponent;

    /** 该对换血结果：REPLACED 已替换；UNRESOLVED 暂无合格替代品 */
    @Column(nullable = false, length = 16)
    private String outcome = "UNRESOLVED";

    protected ReplacementItem() {
    }

    public ReplacementItem(ReplacementTask task, BloodComponent removedComponent) {
        this.task = task;
        this.removedComponent = removedComponent;
    }

    public void resolve(BloodComponent replacementComponent) {
        this.replacementComponent = replacementComponent;
        this.outcome = "REPLACED";
    }

    public Long getId() {
        return id;
    }

    public ReplacementTask getTask() {
        return task;
    }

    public BloodComponent getRemovedComponent() {
        return removedComponent;
    }

    public BloodComponent getReplacementComponent() {
        return replacementComponent;
    }

    public String getOutcome() {
        return outcome;
    }
}
