package com.chris64233.cc.bloodbank.api.dto;

import com.chris64233.cc.bloodbank.domain.ItemStatus;
import com.chris64233.cc.bloodbank.domain.ReservationItem;

import java.time.Instant;

public record ItemView(
        int position,
        String barcode,
        String bloodType,
        Instant expiresAt,
        ItemStatus status) {

    public static ItemView from(ReservationItem item) {
        return new ItemView(
                item.getPosition(),
                item.getUnit().getBarcode(),
                item.getUnit().getBloodType().label(),
                item.getUnit().getExpiresAt(),
                item.getStatus());
    }
}
