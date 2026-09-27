package com.chris64233.cc.bloodbank.web;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateReservationRequest(@NotBlank String requestNo,
                                       @NotNull AboGroup patientAbo,
                                       @NotNull RhFactor patientRh,
                                       @NotNull ComponentType componentType,
                                       @Min(1) int unitCount,
                                       @NotNull Instant latestUseTime) {
}
