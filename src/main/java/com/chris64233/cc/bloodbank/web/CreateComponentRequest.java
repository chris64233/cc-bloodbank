package com.chris64233.cc.bloodbank.web;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateComponentRequest(@NotBlank String barcode,
                                     @NotBlank String batchNo,
                                     @NotNull ComponentType componentType,
                                     @NotNull AboGroup aboGroup,
                                     @NotNull RhFactor rhFactor,
                                     @NotNull Instant collectedAt,
                                     @NotNull Instant expiresAt) {
}
