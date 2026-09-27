package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.api.error.BusinessConflictException;
import com.chris64233.cc.bloodbank.domain.BloodType;
import com.chris64233.cc.bloodbank.domain.BloodUnit;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.ItemStatus;
import com.chris64233.cc.bloodbank.domain.Reservation;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.UnitStatus;
import com.chris64233.cc.bloodbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationServiceTest extends AbstractIntegrationTest {

    private static final Instant D1 = Instant.parse("2026-09-28T08:00:00Z");
    private static final Instant D2 = Instant.parse("2026-09-28T12:00:00Z");
    private static final Instant D3 = Instant.parse("2026-09-29T08:00:00Z");
    private static final Instant D4 = Instant.parse("2026-09-30T08:00:00Z");

    private List<String> itemBarcodes(Reservation r) {
        return r.getItems().stream().map(i -> i.getUnit().getBarcode()).toList();
    }

    @Test
    void reservesByFefoThenBarcode_ignoringUnitsThatExpireBeforeLatestUse() {
        // A+ 患者可用 O-、O+、A-、A+；U001(A+) 在最晚使用时间前失效，必须排除
        registerUnit("U001", ComponentType.RED_CELLS, BloodType.A_POS, D1);
        registerUnit("U002", ComponentType.RED_CELLS, BloodType.O_NEG, D2);
        registerUnit("U003", ComponentType.RED_CELLS, BloodType.O_POS, D3);
        registerUnit("U004", ComponentType.RED_CELLS, BloodType.A_NEG, D4);

        Instant latestUse = Instant.parse("2026-09-28T10:00:00Z");
        var outcome = reservationService.reserve(
                request("REQ-1", BloodType.A_POS, ComponentType.RED_CELLS, 3, latestUse));

        assertThat(outcome.created()).isTrue();
        assertThat(itemBarcodes(outcome.reservation())).containsExactly("U002", "U003", "U004");
        assertThat(outcome.reservation().getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(unitRepository.findById("U001").orElseThrow().getStatus())
                .isEqualTo(UnitStatus.AVAILABLE);
        for (String barcode : List.of("U002", "U003", "U004")) {
            assertThat(unitRepository.findById(barcode).orElseThrow().getStatus())
                    .isEqualTo(UnitStatus.RESERVED);
        }
    }

    @Test
    void tiesOnExpiryBreakByBarcode() {
        registerUnit("U-B02", ComponentType.RED_CELLS, BloodType.O_NEG, D2);
        registerUnit("U-B01", ComponentType.RED_CELLS, BloodType.O_NEG, D2);
        registerUnit("U-A99", ComponentType.RED_CELLS, BloodType.O_NEG, D2);

        var outcome = reservationService.reserve(
                request("REQ-TIE", BloodType.A_POS, ComponentType.RED_CELLS, 3, D2));

        assertThat(itemBarcodes(outcome.reservation()))
                .containsExactly("U-A99", "U-B01", "U-B02");
    }

    @Test
    void onlyCompatibleBloodTypesAreEligible() {
        // O- 患者只能接受 O-
        registerUnit("U-A-", ComponentType.RED_CELLS, BloodType.A_NEG, D2);
        registerUnit("U-O+", ComponentType.RED_CELLS, BloodType.O_POS, D2);
        registerUnit("U-O-", ComponentType.RED_CELLS, BloodType.O_NEG, D3);

        var outcome = reservationService.reserve(
                request("REQ-COMPAT", BloodType.O_NEG, ComponentType.RED_CELLS, 1, D2));

        assertThat(itemBarcodes(outcome.reservation())).containsExactly("U-O-");
    }

    @Test
    void componentTypeMustMatchExactly() {
        registerUnit("U-PLASMA", ComponentType.FROZEN_PLASMA, BloodType.O_NEG, D3);
        registerUnit("U-RBC", ComponentType.RED_CELLS, BloodType.O_NEG, D3);

        var outcome = reservationService.reserve(
                request("REQ-COMP", BloodType.O_NEG, ComponentType.RED_CELLS, 1, D3));

        assertThat(itemBarcodes(outcome.reservation())).containsExactly("U-RBC");
    }

    @Test
    void alreadyExpiredUnitsAreNotEligible() {
        registerUnit("U-EXPIRED", ComponentType.RED_CELLS, BloodType.O_NEG,
                T0.minus(Duration.ofMinutes(1)));
        registerUnit("U-FRESH", ComponentType.RED_CELLS, BloodType.O_NEG, D3);

        var outcome = reservationService.reserve(
                request("REQ-EXP", BloodType.O_NEG, ComponentType.RED_CELLS, 1, T0));

        assertThat(itemBarcodes(outcome.reservation())).containsExactly("U-FRESH");
    }

    @Test
    void insufficientUnitsFailsAtomicallyNothingIsReserved() {
        registerUnit("U1", ComponentType.RED_CELLS, BloodType.O_NEG, D3);
        registerUnit("U2", ComponentType.RED_CELLS, BloodType.O_NEG, D4);

        assertThatThrownBy(() -> reservationService.reserve(
                request("REQ-FAIL", BloodType.O_NEG, ComponentType.RED_CELLS, 3, D3)))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("不足");

        assertThat(reservationRepository.count()).isZero();
        assertThat(unitRepository.findAll())
                .allSatisfy(u -> assertThat(u.getStatus()).isEqualTo(UnitStatus.AVAILABLE));
    }

    @Test
    void replayWithSameContentReturnsOriginalAllocation() {
        registerUnit("U1", ComponentType.RED_CELLS, BloodType.O_NEG, D3);
        registerUnit("U2", ComponentType.RED_CELLS, BloodType.O_NEG, D4);

        var first = reservationService.reserve(
                request("REQ-REPLAY", BloodType.A_POS, ComponentType.RED_CELLS, 2, D3));
        var second = reservationService.reserve(
                request("REQ-REPLAY", BloodType.A_POS, ComponentType.RED_CELLS, 2, D3));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(itemBarcodes(second.reservation()))
                .isEqualTo(itemBarcodes(first.reservation()));
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(unitRepository.findByStatusOrderByExpiresAtAscBarcodeAsc(UnitStatus.RESERVED))
                .hasSize(2);
    }

    @Test
    void replayWithDifferentContentConflicts() {
        registerUnit("U1", ComponentType.RED_CELLS, BloodType.O_NEG, D3);
        registerUnit("U2", ComponentType.RED_CELLS, BloodType.O_NEG, D4);
        reservationService.reserve(
                request("REQ-X", BloodType.A_POS, ComponentType.RED_CELLS, 1, D3));

        // 单位数不同
        assertThatThrownBy(() -> reservationService.reserve(
                request("REQ-X", BloodType.A_POS, ComponentType.RED_CELLS, 2, D3)))
                .isInstanceOf(BusinessConflictException.class)
                .extracting(ex -> ((BusinessConflictException) ex).getCode())
                .isEqualTo("REQUEST_CONTENT_CONFLICT");

        // 患者血型不同
        assertThatThrownBy(() -> reservationService.reserve(
                request("REQ-X", BloodType.O_NEG, ComponentType.RED_CELLS, 1, D3)))
                .isInstanceOf(BusinessConflictException.class);

        // 最晚使用时间不同
        assertThatThrownBy(() -> reservationService.reserve(
                request("REQ-X", BloodType.A_POS, ComponentType.RED_CELLS, 1, D4)))
                .isInstanceOf(BusinessConflictException.class);

        // 成分不同
        assertThatThrownBy(() -> reservationService.reserve(
                request("REQ-X", BloodType.A_POS, ComponentType.PLATELETS, 1, D3)))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void issueIsTerminalAndIdempotent() {
        registerUnit("U1", ComponentType.RED_CELLS, BloodType.O_NEG, D3);
        reservationService.reserve(
                request("REQ-ISSUE", BloodType.O_NEG, ComponentType.RED_CELLS, 1, D3));

        Reservation issued = reservationService.issue("REQ-ISSUE");
        assertThat(issued.getStatus()).isEqualTo(ReservationStatus.ISSUED);
        assertThat(issued.getItems()).allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(ItemStatus.ISSUED));
        assertThat(unitRepository.findById("U1").orElseThrow().getStatus()).isEqualTo(UnitStatus.ISSUED);

        // 重复发放：幂等返回，不重复改变库存
        Reservation again = reservationService.issue("REQ-ISSUE");
        assertThat(again.getStatus()).isEqualTo(ReservationStatus.ISSUED);
        assertThat(unitRepository.findById("U1").orElseThrow().getStatus()).isEqualTo(UnitStatus.ISSUED);
    }

    @Test
    void cannotIssueAfterExpiry() {
        registerUnit("U1", ComponentType.RED_CELLS, BloodType.O_NEG, D3);
        reservationService.reserve(request("REQ-LATE", BloodType.O_NEG, ComponentType.RED_CELLS,
                1, D3, T0.plus(Duration.ofMinutes(10))));

        clock.advance(Duration.ofMinutes(11));

        assertThatThrownBy(() -> reservationService.issue("REQ-LATE"))
                .isInstanceOf(BusinessConflictException.class)
                .extracting(ex -> ((BusinessConflictException) ex).getCode())
                .isEqualTo("RESERVATION_EXPIRED");
        assertThat(unitRepository.findById("U1").orElseThrow().getStatus())
                .isEqualTo(UnitStatus.RESERVED);
    }

    @Test
    void expiredReservationReleasesAllUnitsAndReleaseIsIdempotent() {
        registerUnit("U1", ComponentType.RED_CELLS, BloodType.O_NEG, D3);
        registerUnit("U2", ComponentType.RED_CELLS, BloodType.O_NEG, D4);
        reservationService.reserve(request("REQ-REL", BloodType.O_NEG, ComponentType.RED_CELLS,
                2, D3, T0.plus(Duration.ofMinutes(10))));

        clock.advance(Duration.ofMinutes(11));
        assertThat(reservationService.releaseIfExpired("REQ-REL")).isTrue();

        Reservation released = reservationService.getRequiredWithItems("REQ-REL");
        assertThat(released.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(released.getItems())
                .allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(ItemStatus.RELEASED));
        for (String barcode : List.of("U1", "U2")) {
            assertThat(unitRepository.findById(barcode).orElseThrow().getStatus())
                    .isEqualTo(UnitStatus.AVAILABLE);
        }

        // 重复释放：不再次改变库存
        assertThat(reservationService.releaseIfExpired("REQ-REL")).isFalse();
        assertThat(unitRepository.findById("U1").orElseThrow().getStatus()).isEqualTo(UnitStatus.AVAILABLE);

        // 已释放后再发放：拒绝
        assertThatThrownBy(() -> reservationService.issue("REQ-REL"))
                .isInstanceOf(BusinessConflictException.class)
                .extracting(ex -> ((BusinessConflictException) ex).getCode())
                .isEqualTo("RESERVATION_RELEASED");
    }

    @Test
    void notYetExpiredReservationIsNotReleased() {
        registerUnit("U1", ComponentType.RED_CELLS, BloodType.O_NEG, D3);
        reservationService.reserve(request("REQ-KEEP", BloodType.O_NEG, ComponentType.RED_CELLS,
                1, D3, T0.plus(Duration.ofMinutes(10))));

        clock.advance(Duration.ofMinutes(5));
        assertThat(reservationService.releaseIfExpired("REQ-KEEP")).isFalse();
        assertThat(reservationService.getRequiredWithItems("REQ-KEEP").getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(unitRepository.findById("U1").orElseThrow().getStatus())
                .isEqualTo(UnitStatus.RESERVED);
    }

    @Test
    void releasedUnitsCanBeReservedAgainAndReplayStillReturnsOriginal() {
        registerUnit("U1", ComponentType.RED_CELLS, BloodType.O_NEG, D3);
        reservationService.reserve(request("REQ-OLD", BloodType.O_NEG, ComponentType.RED_CELLS,
                1, D3, T0.plus(Duration.ofMinutes(10))));
        clock.advance(Duration.ofMinutes(11));
        reservationService.releaseIfExpired("REQ-OLD");

        // 释放后的单位可被新申请预留
        var next = reservationService.reserve(
                request("REQ-NEW", BloodType.O_NEG, ComponentType.RED_CELLS, 1, D3));
        assertThat(itemBarcodes(next.reservation())).containsExactly("U1");
        assertThat(unitRepository.findById("U1").orElseThrow().getStatus())
                .isEqualTo(UnitStatus.RESERVED);

        // 旧申请相同内容重放：返回原分配（RELEASED），不抢占新库存
        var replay = reservationService.reserve(request("REQ-OLD", BloodType.O_NEG,
                ComponentType.RED_CELLS, 1, D3, T0.plus(Duration.ofMinutes(10))));
        assertThat(replay.created()).isFalse();
        assertThat(replay.reservation().getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(unitRepository.findById("U1").orElseThrow().getStatus())
                .isEqualTo(UnitStatus.RESERVED);
    }

    @Test
    void issuedUnitsAreNeverReleasedOrReallocated() {
        registerUnit("U1", ComponentType.RED_CELLS, BloodType.O_NEG, D3);
        reservationService.reserve(request("REQ-DONE", BloodType.O_NEG, ComponentType.RED_CELLS,
                1, D3, T0.plus(Duration.ofMinutes(10))));
        reservationService.issue("REQ-DONE");

        clock.advance(Duration.ofHours(1));
        assertThat(reservationService.releaseIfExpired("REQ-DONE")).isFalse();
        assertThat(unitRepository.findById("U1").orElseThrow().getStatus()).isEqualTo(UnitStatus.ISSUED);

        // 已发放单位不会再出现在候选集中
        assertThatThrownBy(() -> reservationService.reserve(
                request("REQ-NEW", BloodType.O_NEG, ComponentType.RED_CELLS, 1, D3)))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void defaultTtlAppliesWhenExpireAtOmitted() {
        registerUnit("U1", ComponentType.RED_CELLS, BloodType.O_NEG, D3);
        var outcome = reservationService.reserve(
                request("REQ-TTL", BloodType.O_NEG, ComponentType.RED_CELLS, 1, D3));
        assertThat(outcome.reservation().getExpireAt()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
    }

    @Test
    void reservationInThePastIsRejected() {
        registerUnit("U1", ComponentType.RED_CELLS, BloodType.O_NEG, D3);
        assertThatThrownBy(() -> reservationService.reserve(request("REQ-PAST",
                BloodType.O_NEG, ComponentType.RED_CELLS, 1, D3, T0.minusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issueUnknownRequestThrowsNotFound() {
        assertThatThrownBy(() -> reservationService.issue("NO-SUCH"))
                .isInstanceOf(com.chris64233.cc.bloodbank.api.error.ResourceNotFoundException.class);
    }
}
