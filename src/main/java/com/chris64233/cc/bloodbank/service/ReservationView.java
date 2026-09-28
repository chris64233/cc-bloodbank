package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.Reservation;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;

import java.time.Instant;
import java.util.List;

/**
 * @param blocked      召回导致换血未完成时为 true，预留暂停发放
 * @param replacements 历次换血的前后明细（被替换单位 -> 替代单位）
 */
public record ReservationView(Long reservationId, String requestNo, ReservationStatus status, boolean blocked,
                              Instant reservedAt, Instant expiresAt, Instant issuedAt, Instant releasedAt,
                              List<ReservationItemView> items,
                              List<ReplacementItemView> replacements) {

    public static ReservationView of(Reservation r, List<ReservationItemView> items,
                                     List<ReplacementItemView> replacements) {
        return new ReservationView(r.getId(), r.getRequest().getRequestNo(), r.getStatus(), r.isBlocked(),
                r.getReservedAt(), r.getExpiresAt(), r.getIssuedAt(), r.getReleasedAt(), items, replacements);
    }
}
