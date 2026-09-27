package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.Reservation;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;

import java.time.Instant;
import java.util.List;

public record ReservationView(Long reservationId, String requestNo, ReservationStatus status,
                              Instant reservedAt, Instant expiresAt, Instant issuedAt, Instant releasedAt,
                              List<ReservationItemView> items) {

    public static ReservationView of(Reservation r, List<ReservationItemView> items) {
        return new ReservationView(r.getId(), r.getRequest().getRequestNo(), r.getStatus(),
                r.getReservedAt(), r.getExpiresAt(), r.getIssuedAt(), r.getReleasedAt(), items);
    }
}
