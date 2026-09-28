package com.chris64233.cc.bloodbank;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.ReplacementStatus;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import com.chris64233.cc.bloodbank.repository.BatchRecallRepository;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.ClinicalRequestRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementItemRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementTaskRepository;
import com.chris64233.cc.bloodbank.repository.ReservationItemRepository;
import com.chris64233.cc.bloodbank.repository.ReservationRepository;
import com.chris64233.cc.bloodbank.service.ComponentService;
import com.chris64233.cc.bloodbank.service.RecallService;
import com.chris64233.cc.bloodbank.service.ReservationService;
import com.chris64233.cc.bloodbank.service.ReservationView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

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
 * 被召回单位绝不发放、替代单位不被重复占用、换血完成后旧释放不能退回替代单位。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class RecallConcurrencyTest {

    private static final Instant LATEST_USE = TestClockConfig.T0.plus(Duration.ofHours(12));

    @Autowired
    RecallService recallService;
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
    ReplacementTaskRepository taskRepository;
    @Autowired
    ReplacementItemRepository replacementItemRepository;
    @Autowired
    BatchRecallRepository recallRepository;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    MutableClock clock;

    @BeforeEach
    void cleanUp() {
        replacementItemRepository.deleteAll();
        taskRepository.deleteAll();
        itemRepository.deleteAll();
        reservationRepository.deleteAll();
        requestRepository.deleteAll();
        recallRepository.deleteAll();
        componentRepository.deleteAll();
        clock.setInstant(TestClockConfig.T0);
    }

    private void register(String barcode, String batch, Instant expiresAt) {
        componentService.register(barcode, batch, ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE,
                TestClockConfig.T0.minus(Duration.ofDays(1)), expiresAt);
    }

    private static List<Future<Object>> race(Callable2<?> a, Callable2<?> b) throws InterruptedException {
        @SuppressWarnings("unchecked")
        Callable2<Object> ca = (Callable2<Object>) a;
        @SuppressWarnings("unchecked")
        Callable2<Object> cb = (Callable2<Object>) b;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable2<Object> wa = () -> { ready.countDown(); start.await(); return ca.call(); };
        Callable2<Object> wb = () -> { ready.countDown(); start.await(); return cb.call(); };
        Future<Object> fa = pool.submit(wa::call);
        Future<Object> fb = pool.submit(wb::call);
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return List.of(fa, fb);
    }

    @FunctionalInterface
    interface Callable2<T> {
        T call() throws Exception;
    }

    @Test
    void replacementAndIssueRaceNeverIssuesRecalledUnit() throws Exception {
        register("RC-OLD", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(16)));
        register("RC-NEW", "BATCH-X", TestClockConfig.T0.plus(Duration.ofHours(20)));
        reservationService.createReservation("RQ-C", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        recallService.publishRecall("EV-C", "BATCH-R", "原因", clock.instant());

        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        Callable2<ReservationView> issue = () -> {
            try {
                return reservationService.issue("RQ-C");
            } catch (RuntimeException e) {
                errors.add(e);
                return null;
            }
        };
        Callable2<?> replace = () -> recallService.replaceForRequest("RQ-C");

        for (Future<?> f : race(issue, replace)) {
            f.get();
        }

        ReservationView finalView = reservationService.getRequest("RQ-C").reservation();
        ComponentStatus oldStatus = componentRepository.findByBarcode("RC-OLD").orElseThrow().getStatus();
        ComponentStatus newStatus = componentRepository.findByBarcode("RC-NEW").orElseThrow().getStatus();
        // 被召回单位绝不能发放
        assertThat(oldStatus).isEqualTo(ComponentStatus.RECALLED);

        if (finalView.status() == com.chris64233.cc.bloodbank.domain.ReservationStatus.ISSUED) {
            // 发放先生效：只能在换血/召回冻结之前发生于原单位——但召回已先发布并冻结，
            // 因此发放要么被阻止（errors 非空），要么发放的是替代单位。
            assertThat(finalView.items()).extracting("barcode").doesNotContain("RC-OLD");
            assertThat(newStatus).isEqualTo(ComponentStatus.ISSUED);
        } else {
            // 换血先生效：预留仍 RESERVED，持有替代单位，旧单位冻结；发放尝试被阻止（或换血后未再发放）
            assertThat(finalView.items()).extracting("barcode").containsExactly("RC-NEW");
            assertThat(newStatus).isEqualTo(ComponentStatus.RESERVED);
        }
        // 所有异常都应是召回阻止（409），不能出现其他错误
        for (Throwable t : errors) {
            assertThat(t.getClass().getSimpleName()).isEqualTo("RecallBlockedException");
        }
    }

    @Test
    void replacementAndExpiryReleaseRaceLeavesStateConsistent() throws Exception {
        register("RX-OLD", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(16)));
        register("RX-NEW", "BATCH-X", TestClockConfig.T0.plus(Duration.ofHours(20)));
        reservationService.createReservation("RQ-X", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        recallService.publishRecall("EV-X", "BATCH-R", "原因", clock.instant());

        Callable2<?> replace = () -> recallService.replaceForRequest("RQ-X");
        Callable2<?> release = () -> {
            clock.advance(Duration.ofHours(2));
            return reservationService.releaseExpired();
        };

        for (Future<?> f : race(replace, release)) {
            f.get();
        }

        // 无论二者如何交错，状态必须自洽：
        // 被召回单位始终冻结；替代单位要么被预留占用（换血生效），要么在库，绝不出现重复/脏占用
        ReservationView finalView = reservationService.getRequest("RQ-X").reservation();
        ComponentStatus oldStatus = componentRepository.findByBarcode("RX-OLD").orElseThrow().getStatus();
        ComponentStatus newStatus = componentRepository.findByBarcode("RX-NEW").orElseThrow().getStatus();
        assertThat(oldStatus).isEqualTo(ComponentStatus.RECALLED);
        if (finalView.status() == com.chris64233.cc.bloodbank.domain.ReservationStatus.RELEASED) {
            assertThat(newStatus).isEqualTo(ComponentStatus.AVAILABLE);
            assertThat(finalView.items()).isEmpty();
        } else {
            assertThat(finalView.status())
                    .isEqualTo(com.chris64233.cc.bloodbank.domain.ReservationStatus.RESERVED);
            assertThat(finalView.items()).extracting("barcode").containsExactly("RX-NEW");
            assertThat(newStatus).isEqualTo(ComponentStatus.RESERVED);
        }
        Long reservationId = reservationRepository.findByRequest_RequestNo("RQ-X").orElseThrow().getId();
        assertThat(taskRepository.findByReservationId(reservationId))
                .allSatisfy(t -> assertThat(t.getStatus()).isIn(
                        ReplacementStatus.REPLACED, ReplacementStatus.OBSOLETE));
    }

    @Test
    void staleVersionedReleaseAfterReplacementIsRejected() {
        register("RS-OLD", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(16)));
        register("RS-NEW", "BATCH-X", TestClockConfig.T0.plus(Duration.ofHours(20)));
        reservationService.createReservation("RQ-S", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        recallService.publishRecall("EV-S", "BATCH-R", "原因", clock.instant());

        // 成功换血：预留状态版本 0 -> 1
        recallService.replaceForRequest("RQ-S");
        Long reservationId = reservationRepository.findByRequest_RequestNo("RQ-S").orElseThrow().getId();
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStateVersion()).isEqualTo(1);

        // 持旧版本（0）的释放必须被拒绝，替代单位绝不能被退回库存
        boolean released = transactionTemplate.execute(tx ->
                reservationService.releaseLocked(reservationId, clock.instant(), 0));
        assertThat(released).isFalse();
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(com.chris64233.cc.bloodbank.domain.ReservationStatus.RESERVED);
        assertThat(componentRepository.findByBarcode("RS-NEW").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RESERVED);
        assertThat(itemRepository.findActiveByReservationId(reservationId))
                .extracting(i -> i.getComponent().getBarcode()).containsExactly("RS-NEW");
    }

    @Test
    void concurrentReplacementsForSameRequestExecuteOnce() throws Exception {
        register("RR-OLD", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(16)));
        register("RR-NEW", "BATCH-X", TestClockConfig.T0.plus(Duration.ofHours(20)));
        reservationService.createReservation("RQ-R", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        recallService.publishRecall("EV-R", "BATCH-R", "原因", clock.instant());

        Callable2<?> replace = () -> recallService.replaceForRequest("RQ-R");
        for (Future<?> f : race(replace, replace)) {
            f.get();
        }

        // 只产生一次换血：替代单位仅被占用一次，换血明细仅一条
        assertThat(componentRepository.findByBarcode("RR-NEW").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RESERVED);
        assertThat(replacementItemRepository.findAll()).hasSize(1);
        assertThat(taskRepository.findAll()).hasSize(1);
        assertThat(taskRepository.findAll().get(0).getStatus()).isEqualTo(ReplacementStatus.REPLACED);
    }
}
