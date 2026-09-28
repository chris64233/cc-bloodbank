package com.chris64233.cc.bloodbank;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.ReplacementStatus;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.ClinicalRequestRepository;
import com.chris64233.cc.bloodbank.repository.RecallRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementItemRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementTaskRepository;
import com.chris64233.cc.bloodbank.repository.ReservationItemRepository;
import com.chris64233.cc.bloodbank.repository.ReservationRepository;
import com.chris64233.cc.bloodbank.service.ComponentService;
import com.chris64233.cc.bloodbank.service.IssueBlockedException;
import com.chris64233.cc.bloodbank.service.RecallService;
import com.chris64233.cc.bloodbank.service.ReplacementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 召回换血与发放/到期释放并发：
 * 被召回单位绝不发放、替代单位不被重复占用、换血完成后不会被旧释放退回库存。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class RecallConcurrencyTest {

    private static final Instant LATEST_USE = TestClockConfig.T0.plus(Duration.ofHours(12));

    @Autowired RecallService recallService;
    @Autowired ReplacementService replacementService;
    @Autowired com.chris64233.cc.bloodbank.service.ReservationService reservationService;
    @Autowired ComponentService componentService;
    @Autowired BloodComponentRepository componentRepository;
    @Autowired ClinicalRequestRepository requestRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired ReservationItemRepository itemRepository;
    @Autowired ReplacementItemRepository replacementItemRepository;
    @Autowired ReplacementTaskRepository replacementTaskRepository;
    @Autowired RecallRepository recallRepository;
    @Autowired MutableClock clock;

    @BeforeEach
    void cleanUp() {
        replacementItemRepository.deleteAll();
        replacementTaskRepository.deleteAll();
        itemRepository.deleteAll();
        recallRepository.deleteAll();
        reservationRepository.deleteAll();
        requestRepository.deleteAll();
        componentRepository.deleteAll();
        clock.setInstant(TestClockConfig.T0);
    }

    private void register(String barcode, String batch, Instant expiresAt) {
        componentService.register(barcode, batch, ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE,
                TestClockConfig.T0.minus(Duration.ofDays(1)), expiresAt);
    }

    private static <T> List<Future<T>> race(ThrowingSupplier<T> a, ThrowingSupplier<T> b) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<T> fa = pool.submit(() -> { ready.countDown(); start.await(); return a.get(); });
        Future<T> fb = pool.submit(() -> { ready.countDown(); start.await(); return b.get(); });
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return List.of(fa, fb);
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @Test
    void concurrentReplacementExecutionsSwapExactlyOnce() throws Exception {
        register("OLD-1", "B1", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("OLD-2", "B1", TestClockConfig.T0.plus(Duration.ofHours(22)));
        register("NEW-1", "B2", TestClockConfig.T0.plus(Duration.ofHours(24)));
        register("NEW-2", "B3", TestClockConfig.T0.plus(Duration.ofHours(26)));
        reservationService.createReservation("CR-R", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 2, LATEST_USE);
        recallService.publish("EV-C1", "B1", "并发召回", TestClockConfig.T0);
        Long taskId = recallService.getRecall("EV-C1").affectedRequests().get(0).taskId();

        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        var futures = race(
                () -> replacementService.executeTask(taskId),
                () -> replacementService.executeTask(taskId));
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                errors.add(e);
            }
        }
        assertThat(errors).isEmpty();

        // 替代单位各被占用一次，没有重复占用
        assertThat(componentRepository.findByBarcode("NEW-1").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RESERVED);
        assertThat(componentRepository.findByBarcode("NEW-2").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RESERVED);
        assertThat(componentRepository.findByStatusOrderByExpiresAtAscBarcodeAsc(ComponentStatus.RESERVED))
                .hasSize(2);
        assertThat(replacementTaskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(ReplacementStatus.COMPLETED);
        assertThat(replacementItemRepository.findAll()).hasSize(2);
        assertThat(itemRepository.findAll()).hasSize(4);
    }

    @Test
    void issueAndReplacementRaceNeverIssuesRecalledUnit() throws Exception {
        register("OLD", "B1", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("NEW", "B2", TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("CR-IR", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        recallService.publish("EV-C2", "B1", "并发召回", TestClockConfig.T0);
        Long taskId = recallService.getRecall("EV-C2").affectedRequests().get(0).taskId();

        ConcurrentLinkedQueue<Throwable> blocked = new ConcurrentLinkedQueue<>();
        var futures = race(
                () -> {
                    try {
                        reservationService.issue("CR-IR");
                    } catch (IssueBlockedException e) {
                        blocked.add(e);
                    }
                    return null;
                },
                () -> replacementService.executeTask(taskId));
        for (Future<?> f : futures) {
            f.get();
        }

        // 无论顺序如何：被召回单位绝不发放
        assertThat(componentRepository.findByBarcode("OLD").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RECALLED);
        assertThat(replacementTaskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(ReplacementStatus.COMPLETED);
        var reservation = reservationRepository.findByRequest_RequestNo("CR-IR").orElseThrow();
        if (reservation.getStatus() == ReservationStatus.ISSUED) {
            // 换血先完成、发放后到：发放的只能是替代品
            assertThat(blocked).isEmpty();
            assertThat(componentRepository.findByBarcode("NEW").orElseThrow().getStatus())
                    .isEqualTo(ComponentStatus.ISSUED);
        } else {
            // 发放先尝试被阻断、换血后完成：替代品占用中，可再次发放
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(blocked).hasSize(1);
            assertThat(componentRepository.findByBarcode("NEW").orElseThrow().getStatus())
                    .isEqualTo(ComponentStatus.RESERVED);
        }
    }

    @Test
    void replacementAndExpiryReleaseRaceNeverReturnsSubstitutesAfterSwap() throws Exception {
        register("OLD", "B1", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("NEW", "B2", TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("CR-ER", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        recallService.publish("EV-C3", "B1", "并发召回", TestClockConfig.T0);
        Long taskId = recallService.getRecall("EV-C3").affectedRequests().get(0).taskId();

        var futures = race(
                () -> { clock.advance(Duration.ofHours(2)); reservationService.releaseExpired(); return null; },
                () -> { clock.advance(Duration.ofHours(2)); replacementService.executeTask(taskId); return null; });
        for (Future<?> f : futures) {
            f.get();
        }

        // 预留已过截止：两种顺序都收敛为"释放/作废"，替代品绝不被占用后又退回
        var reservation = reservationRepository.findByRequest_RequestNo("CR-ER").orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(componentRepository.findByBarcode("OLD").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RECALLED);
        assertThat(componentRepository.findByBarcode("NEW").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.AVAILABLE);
        assertThat(replacementTaskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(ReplacementStatus.ABANDONED);
    }
}
