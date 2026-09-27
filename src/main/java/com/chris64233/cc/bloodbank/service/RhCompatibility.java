package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.RhFactor;

import java.util.EnumSet;
import java.util.Set;

/**
 * Rh 相容性：Rh 阴性患者只能接受 Rh 阴性单位，Rh 阳性患者两者皆可。
 */
public final class RhCompatibility {

    private RhCompatibility() {
    }

    public static Set<RhFactor> acceptableDonorRh(RhFactor patientRh) {
        return patientRh == RhFactor.NEGATIVE
                ? EnumSet.of(RhFactor.NEGATIVE)
                : EnumSet.allOf(RhFactor.class);
    }
}
