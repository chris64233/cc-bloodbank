package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.ClinicalRequest;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.RhFactor;

import java.time.Instant;

public record RequestView(String requestNo, AboGroup patientAbo, RhFactor patientRh,
                          ComponentType componentType, int unitCount, Instant latestUseTime,
                          ReservationView reservation) {

    public static RequestView of(ClinicalRequest req, ReservationView reservation) {
        return new RequestView(req.getRequestNo(), req.getPatientAbo(), req.getPatientRh(),
                req.getComponentType(), req.getUnitCount(), req.getLatestUseTime(), reservation);
    }
}
