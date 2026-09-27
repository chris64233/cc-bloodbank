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
 * 临床用血申请：唯一申请号、患者血型、所需成分、单位数、最晚使用时间。
 * 申请号有数据库唯一约束，相同申请号重放时用于幂等判断。
 */
@Entity
@Table(name = "clinical_request")
public class ClinicalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String requestNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private AboGroup patientAbo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private RhFactor patientRh;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ComponentType componentType;

    @Column(nullable = false)
    private int unitCount;

    @Column(nullable = false)
    private Instant latestUseTime;

    protected ClinicalRequest() {
    }

    public ClinicalRequest(String requestNo, AboGroup patientAbo, RhFactor patientRh,
                           ComponentType componentType, int unitCount, Instant latestUseTime) {
        this.requestNo = requestNo;
        this.patientAbo = patientAbo;
        this.patientRh = patientRh;
        this.componentType = componentType;
        this.unitCount = unitCount;
        this.latestUseTime = latestUseTime;
    }

    /** 除申请号外的业务内容，用于重放冲突检测 */
    public boolean sameContentAs(AboGroup patientAbo, RhFactor patientRh, ComponentType componentType,
                                 int unitCount, Instant latestUseTime) {
        return this.patientAbo == patientAbo
                && this.patientRh == patientRh
                && this.componentType == componentType
                && this.unitCount == unitCount
                && this.latestUseTime.equals(latestUseTime);
    }

    public Long getId() {
        return id;
    }

    public String getRequestNo() {
        return requestNo;
    }

    public AboGroup getPatientAbo() {
        return patientAbo;
    }

    public RhFactor getPatientRh() {
        return patientRh;
    }

    public ComponentType getComponentType() {
        return componentType;
    }

    public int getUnitCount() {
        return unitCount;
    }

    public Instant getLatestUseTime() {
        return latestUseTime;
    }
}
