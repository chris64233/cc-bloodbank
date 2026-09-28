package com.chris64233.cc.bloodbank.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateRecallRequest(@NotBlank String eventNo,
                                  @NotBlank String batchNo,
                                  @NotBlank String reason,
                                  @NotNull Instant effectiveAt) {
}
