package com.chris64233.cc.bloodbank.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 临床用血申请及其预留结果。
 *
 * <p>申请内容（患者血型、成分、单位数、最晚使用时间）在首次预留时落库；
 * 相同申请号重放时据此判断“幂等返回”还是“内容冲突”。
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    /** 唯一申请号 */
    @Id
    @Column(name = "request_no", length = 40, nullable = false, updatable = false)
    private String requestNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "patient_blood_type", length = 8, nullable = false, updatable = false)
    private BloodType patientBloodType;

    @Enumerated(EnumType.STRING)
    @Column(name = "component_type", length = 16, nullable = false, updatable = false)
    private ComponentType componentType;

    @Column(name = "unit_count", nullable = false, updatable = false)
    private int unitCount;

    /** 最晚使用时间：被选单位失效时间不得早于此时间 */
    @Column(name = "latest_use_at", nullable = false, updatable = false)
    private Instant latestUseAt;

    /** 预留有效截止时间，到期未发放则释放 */
    @Column(name = "expire_at", nullable = false)
    private Instant expireAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ReservationStatus status = ReservationStatus.RESERVED;

    @Version
    @Column(name = "version")
    private long version;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<ReservationItem> items = new ArrayList<>();

    protected Reservation() {
    }

    public Reservation(String requestNo, BloodType patientBloodType, ComponentType componentType,
                       int unitCount, Instant latestUseAt, Instant expireAt) {
        this.requestNo = requestNo;
        this.patientBloodType = patientBloodType;
        this.componentType = componentType;
        this.unitCount = unitCount;
        this.latestUseAt = latestUseAt;
        this.expireAt = expireAt;
        this.status = ReservationStatus.RESERVED;
    }

    public void addItem(ReservationItem item) {
        items.add(item);
    }

    public String getRequestNo() {
        return requestNo;
    }

    public BloodType getPatientBloodType() {
        return patientBloodType;
    }

    public ComponentType getComponentType() {
        return componentType;
    }

    public int getUnitCount() {
        return unitCount;
    }

    public Instant getLatestUseAt() {
        return latestUseAt;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public List<ReservationItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
