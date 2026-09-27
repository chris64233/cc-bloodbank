package com.chris64233.cc.bloodbank.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 血液成分单位（一袋血）。
 */
@Entity
@Table(name = "blood_unit")
public class BloodUnit {

    /** 唯一条码 */
    @Id
    @Column(name = "barcode", length = 32, nullable = false, updatable = false)
    private String barcode;

    /** 成分类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "component_type", length = 16, nullable = false, updatable = false)
    private ComponentType componentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "blood_type", length = 8, nullable = false, updatable = false)
    private BloodType bloodType;

    /** 采集时间 */
    @Column(name = "collected_at", nullable = false, updatable = false)
    private Instant collectedAt;

    /** 失效时间 */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** 库存状态，预留/发放的并发兜底依赖该列的条件更新 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private UnitStatus status = UnitStatus.AVAILABLE;

    protected BloodUnit() {
    }

    public BloodUnit(String barcode, ComponentType componentType, BloodType bloodType,
                     Instant collectedAt, Instant expiresAt) {
        this.barcode = barcode;
        this.componentType = componentType;
        this.bloodType = bloodType;
        this.collectedAt = collectedAt;
        this.expiresAt = expiresAt;
        this.status = UnitStatus.AVAILABLE;
    }

    public String getBarcode() {
        return barcode;
    }

    public ComponentType getComponentType() {
        return componentType;
    }

    public BloodType getBloodType() {
        return bloodType;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UnitStatus getStatus() {
        return status;
    }
}
