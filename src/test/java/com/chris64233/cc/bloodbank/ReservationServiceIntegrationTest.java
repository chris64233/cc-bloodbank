package com.chris64233.cc.bloodbank;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.ClinicalRequestRepository;
import com.chris64233.cc.bloodbank.repository.ReservationItemRepository;
import com.chris64233.cc.bloodbank.repository.ReservationRepository;
import com.chris64233.cc.bloodbank.service.ComponentService;
import com.chris64233.cc.bloodbank.service.InsufficientStockException;
import com.chris64233.cc.bloodbank.service.RequestConflictException;
import com.chris64233.cc.bloodbank.service.RequestView;
import com.chris64233.cc.bloodbank.service.ReservationService;
import com.chris64233.cc.bloodbank.service.ReservationView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestClockConfig.class)
class ReservationServiceIntegrationTest {

    private static final Instant LATEST_USE = TestClockConfig.T0.plus(Duration.ofHours(12));

    @Autowired
    ReservationService reservationService;
    @Autowired
    ComponentService componentService;
    @Autowired
    BloodComponentRepository componentRepository;
    @Autowired
    ClinicalRequestRepository requestRepository;
    @Autowired
    ReservationRepository reservationRepository;
    @Autowired
    ReservationItemRepository itemRepository;
    @Autowired
    MutableClock clock;

    @BeforeEach
    void cleanUp() {
        itemRepository.deleteAll();
        reservationRepository.deleteAll();
        requestRepository.deleteAll();
        componentRepository.deleteAll();
        clock.setInstant(TestClockConfig.T0);
    }

    private void register(String barcode, ComponentType type, AboGroup abo, RhFactor rh, Instant expiresAt) {
        componentService.register(barcode, "BATCH-DEFAULT", type, abo, rh,
                TestClockConfig.T0.minus(Duration.ofDays(1)), expiresAt);
    }

    private ComponentStatus statusOf(String barcode) {
        return componentRepository.findByBarcode(barcode).orElseThrow().getStatus();
    }

    @Test
    void allocatesByEarliestExpiryThenBarcode() {
        // 失效时间最早优先；失效时间相同按条码稳定排序
        register("BC-C", ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));
        register("BC-A", ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("BC-B", ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(20)));

        ReservationView view = reservationService.createReservation(
                "RQ-1", AboGroup.A, RhFactor.POSITIVE, ComponentType.RED_CELLS, 2, LATEST_USE);

        assertThat(view.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(view.reservedAt()).isEqualTo(TestClockConfig.T0);
        assertThat(view.expiresAt()).isEqualTo(TestClockConfig.T0.plus(Duration.ofHours(1)));
        // BC-A 与 BC-B 失效时间相同，按条码排序；BC-C 失效最晚，不被选中
        assertThat(view.items()).extracting("barcode").containsExactly("BC-A", "BC-B");
        assertThat(statusOf("BC-A")).isEqualTo(ComponentStatus.RESERVED);
        assertThat(statusOf("BC-B")).isEqualTo(ComponentStatus.RESERVED);
        assertThat(statusOf("BC-C")).isEqualTo(ComponentStatus.AVAILABLE);
    }

    @Test
    void skipsIncompatibleExpiredAndShortLivedUnits() {
        register("U-OK", ComponentType.RED_CELLS, AboGroup.B, RhFactor.NEGATIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));
        register("U-ABO", ComponentType.RED_CELLS, AboGroup.A, RhFactor.NEGATIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));
        register("U-RH", ComponentType.RED_CELLS, AboGroup.B, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));
        register("U-EXP", ComponentType.RED_CELLS, AboGroup.B, RhFactor.NEGATIVE, TestClockConfig.T0.minus(Duration.ofHours(1)));
        register("U-EARLY", ComponentType.RED_CELLS, AboGroup.B, RhFactor.NEGATIVE, TestClockConfig.T0.plus(Duration.ofHours(6)));
        register("U-PLASMA", ComponentType.PLASMA, AboGroup.B, RhFactor.NEGATIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));

        // B 阴性患者要 2 个红细胞单位：只有 U-OK 合格，整笔失败
        assertThatThrownBy(() -> reservationService.createReservation(
                "RQ-2", AboGroup.B, RhFactor.NEGATIVE, ComponentType.RED_CELLS, 2, LATEST_USE))
                .isInstanceOf(InsufficientStockException.class);

        // 原子性：没有任何单位被预留，申请也未落库
        for (String barcode : List.of("U-OK", "U-ABO", "U-RH", "U-EXP", "U-EARLY", "U-PLASMA")) {
            assertThat(statusOf(barcode)).isEqualTo(ComponentStatus.AVAILABLE);
        }
        assertThat(requestRepository.findByRequestNo("RQ-2")).isEmpty();
    }

    @Test
    void insufficientStockFailsAtomically() {
        register("ONLY-1", ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));

        assertThatThrownBy(() -> reservationService.createReservation(
                "RQ-3", AboGroup.O, RhFactor.POSITIVE, ComponentType.RED_CELLS, 2, LATEST_USE))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(statusOf("ONLY-1")).isEqualTo(ComponentStatus.AVAILABLE);
        assertThat(requestRepository.findByRequestNo("RQ-3")).isEmpty();
        assertThat(reservationRepository.findByRequest_RequestNo("RQ-3")).isEmpty();
    }

    @Test
    void replayWithSameContentReturnsOriginalAllocation() {
        register("RP-1", ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));
        register("RP-2", ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(26)));

        ReservationView first = reservationService.createReservation(
                "RQ-4", AboGroup.A, RhFactor.POSITIVE, ComponentType.RED_CELLS, 2, LATEST_USE);
        clock.advance(Duration.ofMinutes(10));
        ReservationView replay = reservationService.createReservation(
                "RQ-4", AboGroup.A, RhFactor.POSITIVE, ComponentType.RED_CELLS, 2, LATEST_USE);

        assertThat(replay.reservationId()).isEqualTo(first.reservationId());
        assertThat(replay.reservedAt()).isEqualTo(first.reservedAt());
        assertThat(replay.items()).extracting("barcode")
                .containsExactlyElementsOf(first.items().stream().map(i -> i.barcode()).toList());
        // 没有重复预留：仍然只有 2 个单位被占用
        assertThat(componentRepository.findByStatusOrderByExpiresAtAscBarcodeAsc(ComponentStatus.RESERVED))
                .hasSize(2);
    }

    @Test
    void replayWithDifferentContentConflicts() {
        register("CF-1", ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));
        register("CF-2", ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(26)));
        reservationService.createReservation("RQ-5", AboGroup.A, RhFactor.POSITIVE, ComponentType.RED_CELLS, 1, LATEST_USE);

        assertThatThrownBy(() -> reservationService.createReservation(
                "RQ-5", AboGroup.A, RhFactor.POSITIVE, ComponentType.RED_CELLS, 2, LATEST_USE))
                .isInstanceOf(RequestConflictException.class);
        assertThatThrownBy(() -> reservationService.createReservation(
                "RQ-5", AboGroup.B, RhFactor.POSITIVE, ComponentType.RED_CELLS, 1, LATEST_USE))
                .isInstanceOf(RequestConflictException.class);
    }

    @Test
    void issueBeforeDeadlineMarksUnitsIssuedAndIsIdempotent() {
        register("IS-1", ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("RQ-6", AboGroup.O, RhFactor.POSITIVE, ComponentType.RED_CELLS, 1, LATEST_USE);

        clock.advance(Duration.ofMinutes(30));
        ReservationView issued = reservationService.issue("RQ-6");

        assertThat(issued.status()).isEqualTo(ReservationStatus.ISSUED);
        assertThat(issued.issuedAt()).isEqualTo(TestClockConfig.T0.plus(Duration.ofMinutes(30)));
        assertThat(statusOf("IS-1")).isEqualTo(ComponentStatus.ISSUED);

        // 重复发放幂等：状态与时间不变，单位不会再次变更
        ReservationView again = reservationService.issue("RQ-6");
        assertThat(again.status()).isEqualTo(ReservationStatus.ISSUED);
        assertThat(again.issuedAt()).isEqualTo(issued.issuedAt());
        assertThat(statusOf("IS-1")).isEqualTo(ComponentStatus.ISSUED);
    }

    @Test
    void expiredReservationIsReleasedAndUnitsReusable() {
        register("EX-1", ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("RQ-7", AboGroup.O, RhFactor.POSITIVE, ComponentType.RED_CELLS, 1, LATEST_USE);

        clock.advance(Duration.ofHours(2));
        assertThat(reservationService.releaseExpired()).isEqualTo(1);

        RequestView view = reservationService.getRequest("RQ-7");
        assertThat(view.reservation().status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(view.reservation().releasedAt()).isEqualTo(TestClockConfig.T0.plus(Duration.ofHours(2)));
        assertThat(statusOf("EX-1")).isEqualTo(ComponentStatus.AVAILABLE);

        // 重复释放幂等
        assertThat(reservationService.releaseExpired()).isEqualTo(0);
        assertThat(statusOf("EX-1")).isEqualTo(ComponentStatus.AVAILABLE);

        // 释放后的单位可以被新的申请再次预留
        ReservationView again = reservationService.createReservation(
                "RQ-7B", AboGroup.O, RhFactor.POSITIVE, ComponentType.RED_CELLS, 1,
                TestClockConfig.T0.plus(Duration.ofHours(20)));
        assertThat(again.items()).extracting("barcode").containsExactly("EX-1");
        assertThat(statusOf("EX-1")).isEqualTo(ComponentStatus.RESERVED);
    }

    @Test
    void issueAfterExpiryReleasesInsteadOfIssuing() {
        register("LA-1", ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("RQ-8", AboGroup.O, RhFactor.POSITIVE, ComponentType.RED_CELLS, 1, LATEST_USE);

        clock.advance(Duration.ofHours(2));
        ReservationView view = reservationService.issue("RQ-8");

        assertThat(view.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(statusOf("LA-1")).isEqualTo(ComponentStatus.AVAILABLE);
    }

    @Test
    void queryReturnsRequestWithReservationDetails() {
        register("Q-1", ComponentType.PLASMA, AboGroup.AB, RhFactor.NEGATIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("RQ-9", AboGroup.A, RhFactor.NEGATIVE, ComponentType.PLASMA, 1, LATEST_USE);

        RequestView view = reservationService.getRequest("RQ-9");

        assertThat(view.requestNo()).isEqualTo("RQ-9");
        assertThat(view.patientAbo()).isEqualTo(AboGroup.A);
        assertThat(view.patientRh()).isEqualTo(RhFactor.NEGATIVE);
        assertThat(view.componentType()).isEqualTo(ComponentType.PLASMA);
        assertThat(view.unitCount()).isEqualTo(1);
        assertThat(view.latestUseTime()).isEqualTo(LATEST_USE);
        assertThat(view.reservation().status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(view.reservation().items()).extracting("barcode").containsExactly("Q-1");
    }

    @Test
    void rejectsInvalidRequests() {
        assertThatThrownBy(() -> reservationService.createReservation(
                "RQ-10", AboGroup.A, RhFactor.POSITIVE, ComponentType.RED_CELLS, 0, LATEST_USE))
                .isInstanceOf(com.chris64233.cc.bloodbank.service.BadRequestException.class);
        assertThatThrownBy(() -> reservationService.createReservation(
                "RQ-11", AboGroup.A, RhFactor.POSITIVE, ComponentType.RED_CELLS, 1,
                TestClockConfig.T0.minus(Duration.ofHours(1))))
                .isInstanceOf(com.chris64233.cc.bloodbank.service.BadRequestException.class);
    }
}
