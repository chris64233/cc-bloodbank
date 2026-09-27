package com.chris64233.cc.bloodbank.api.dto;

import com.chris64233.cc.bloodbank.domain.BloodUnit;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.UnitStatus;

import java.time.Instant;

public record UnitView(
        String barcode,
        ComponentType componentType,
        String bloodType,
        Instant collectedAt,
        Instant expiresAt,
        UnitStatus status) {

    public static UnitView from(BloodUnit unit) {
        return new UnitView(
                unit.getBarcode(),
                unit.getComponentType(),
                unit.getBloodType().label(),
                unit.getCollectedAt(),
                unit.getExpiresAt(),
                unit.getStatus());
    }
}
