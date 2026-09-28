package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.BloodComponent;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.RhFactor;

import java.time.Instant;

public record ComponentView(Long id, String barcode, String batchNo, ComponentType componentType,
                            AboGroup aboGroup, RhFactor rhFactor,
                            Instant collectedAt, Instant expiresAt, ComponentStatus status) {

    public static ComponentView from(BloodComponent c) {
        return new ComponentView(c.getId(), c.getBarcode(), c.getBatchNo(), c.getComponentType(), c.getAboGroup(),
                c.getRhFactor(), c.getCollectedAt(), c.getExpiresAt(), c.getStatus());
    }
}
