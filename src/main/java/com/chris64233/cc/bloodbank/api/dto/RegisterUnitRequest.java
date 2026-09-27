package com.chris64233.cc.bloodbank.api.dto;

import com.chris64233.cc.bloodbank.domain.BloodType;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 登记一袋血液成分。
 */
public record RegisterUnitRequest(
        @NotBlank String barcode,
        @NotNull ComponentType componentType,
        @NotNull BloodType bloodType,
        @NotNull Instant collectedAt,
        @NotNull Instant expiresAt) {
}
