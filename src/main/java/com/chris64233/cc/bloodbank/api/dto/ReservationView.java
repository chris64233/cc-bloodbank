package com.chris64233.cc.bloodbank.api.dto;

import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.Reservation;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;

import java.time.Instant;
import java.util.List;

public record ReservationView(
        String requestNo,
        String patientBloodType,
        ComponentType componentType,
        int unitCount,
        Instant latestUseAt,
        Instant expireAt,
        ReservationStatus status,
        List<ItemView> items) {

    public static ReservationView from(Reservation reservation) {
        return new ReservationView(
                reservation.getRequestNo(),
                reservation.getPatientBloodType().label(),
                reservation.getComponentType(),
                reservation.getUnitCount(),
                reservation.getLatestUseAt(),
                reservation.getExpireAt(),
                reservation.getStatus(),
                reservation.getItems().stream().map(ItemView::from).toList());
    }
}
