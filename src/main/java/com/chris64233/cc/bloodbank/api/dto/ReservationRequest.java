package com.chris64233.cc.bloodbank.api.dto;

import com.chris64233.cc.bloodbank.domain.BloodType;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 临床用血预留申请。{@code expireAt} 为空时使用服务端默认预留有效期。
 */
public record ReservationRequest(
        @NotBlank String requestNo,
        @NotNull BloodType patientBloodType,
        @NotNull ComponentType componentType,
        @Positive int unitCount,
        @NotNull Instant latestUseAt,
        Instant expireAt) {
}
