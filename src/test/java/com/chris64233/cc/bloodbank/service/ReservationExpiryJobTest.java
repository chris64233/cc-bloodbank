package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.BloodType;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.UnitStatus;
import com.chris64233.cc.bloodbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationExpiryJobTest extends AbstractIntegrationTest {

    @Autowired
    private ReservationExpiryJob expiryJob;

    private static final Instant USE_BY = Instant.parse("2026-09-29T08:00:00Z");

    @Test
    void scanReleasesAllExpiredAndKeepsActiveReservations() {
        registerUnit("J1", ComponentType.RED_CELLS, BloodType.O_NEG, USE_BY);
        registerUnit("J2", ComponentType.RED_CELLS, BloodType.O_NEG, USE_BY);
        registerUnit("J3", ComponentType.RED_CELLS, BloodType.O_NEG, USE_BY);

        reservationService.reserve(request("JOB-EXPIRED-1", BloodType.O_NEG,
                ComponentType.RED_CELLS, 1, USE_BY, T0.plus(Duration.ofMinutes(5))));
        reservationService.reserve(request("JOB-EXPIRED-2", BloodType.O_NEG,
                ComponentType.RED_CELLS, 1, USE_BY, T0.plus(Duration.ofMinutes(8))));
        reservationService.reserve(request("JOB-ACTIVE", BloodType.O_NEG,
                ComponentType.RED_CELLS, 1, USE_BY, T0.plus(Duration.ofHours(2))));

        clock.advance(Duration.ofMinutes(10));
        expiryJob.releaseExpiredReservations();

        assertThat(reservationService.getRequiredWithItems("JOB-EXPIRED-1").getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservationService.getRequiredWithItems("JOB-EXPIRED-2").getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservationService.getRequiredWithItems("JOB-ACTIVE").getStatus())
                .isEqualTo(ReservationStatus.RESERVED);

        assertThat(unitRepository.findById("J1").orElseThrow().getStatus())
                .isEqualTo(UnitStatus.AVAILABLE);
        assertThat(unitRepository.findById("J2").orElseThrow().getStatus())
                .isEqualTo(UnitStatus.AVAILABLE);
        assertThat(unitRepository.findById("J3").orElseThrow().getStatus())
                .isEqualTo(UnitStatus.RESERVED);

        // 再扫一轮：幂等，不再释放
        assertThat(expiryJob.releaseExpiredReservations()).isZero();
    }
}
