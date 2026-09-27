package com.chris64233.cc.bloodbank.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 血液成分单位：唯一条码、成分类型、ABO/Rh 血型、采集与失效时间、库存状态。
 */
@Entity
@Table(name = "blood_component")
public class BloodComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 唯一条码，数据库唯一约束兜底 */
    @Column(nullable = false, unique = true, length = 64)
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ComponentType componentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private AboGroup aboGroup;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private RhFactor rhFactor;

    @Column(nullable = false)
    private Instant collectedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ComponentStatus status = ComponentStatus.AVAILABLE;

    protected BloodComponent() {
    }

    public BloodComponent(String barcode, ComponentType componentType, AboGroup aboGroup, RhFactor rhFactor,
                          Instant collectedAt, Instant expiresAt) {
        this.barcode = barcode;
        this.componentType = componentType;
        this.aboGroup = aboGroup;
        this.rhFactor = rhFactor;
        this.collectedAt = collectedAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getBarcode() {
        return barcode;
    }

    public ComponentType getComponentType() {
        return componentType;
    }

    public AboGroup getAboGroup() {
        return aboGroup;
    }

    public RhFactor getRhFactor() {
        return rhFactor;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public ComponentStatus getStatus() {
        return status;
    }

    public void setStatus(ComponentStatus status) {
        this.status = status;
    }
}
