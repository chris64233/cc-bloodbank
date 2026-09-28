package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.BloodComponent;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.RhFactor;

import java.time.Instant;

public record ReservationItemView(String barcode, String batchNo, ComponentType componentType,
                                  AboGroup aboGroup, RhFactor rhFactor, Instant expiresAt) {

    public static ReservationItemView from(BloodComponent c) {
        return new ReservationItemView(c.getBarcode(), c.getBatchNo(), c.getComponentType(), c.getAboGroup(),
                c.getRhFactor(), c.getExpiresAt());
    }
}
