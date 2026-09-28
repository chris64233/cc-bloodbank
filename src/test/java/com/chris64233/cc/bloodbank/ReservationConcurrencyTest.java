package com.chris64233.cc.bloodbank;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.Reservation;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.ClinicalRequestRepository;
import com.chris64233.cc.bloodbank.repository.ReservationItemRepository;
import com.chris64233.cc.bloodbank.repository.ReservationRepository;
import com.chris64233.cc.bloodbank.service.ComponentService;
import com.chris64233.cc.bloodbank.service.InsufficientStockException;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发正确性：并发申请不能预留同一单位；发放与到期释放并发时只有一个状态变化生效。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class ReservationConcurrencyTest {

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
    TransactionTemplate transactionTemplate;
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

    private void register(String barcode, Instant expiresAt) {
        componentService.register(barcode, "BATCH-DEFAULT", ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE,
                TestClockConfig.T0.minus(Duration.ofDays(1)), expiresAt);
    }

    /** 两个线程在同一时刻起跑执行 */
    private static <T> List<Future<T>> race(Callable2<T> a, Callable2<T> b) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable2<T> wrappedA = () -> { ready.countDown(); start.await(); return a.call(); };
        Callable2<T> wrappedB = () -> { ready.countDown(); start.await(); return b.call(); };
        Future<T> fa = pool.submit(() -> wrappedA.call());
        Future<T> fb = pool.submit(() -> wrappedB.call());
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
    void concurrentReservationsNeverShareTheSameUnit() throws Exception {
        // 4 个单位，两笔各要 3 个单位的申请并发：只能成功一笔，且占用 3 个不同单位
        for (int i = 1; i <= 4; i++) {
            register("CC-" + i, TestClockConfig.T0.plus(Duration.ofHours(20 + i)));
        }
        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        Callable2<Void> taskA = () -> {
            try {
                reservationService.createReservation("CR-A", AboGroup.O, RhFactor.POSITIVE,
                        ComponentType.RED_CELLS, 3, LATEST_USE);
                success.incrementAndGet();
            } catch (InsufficientStockException e) {
                insufficient.incrementAndGet();
            } catch (Throwable t) {
                unexpected.add(t);
            }
            return null;
        };
        Callable2<Void> taskB = () -> {
            try {
                reservationService.createReservation("CR-B", AboGroup.O, RhFactor.POSITIVE,
                        ComponentType.RED_CELLS, 3, LATEST_USE);
                success.incrementAndGet();
            } catch (InsufficientStockException e) {
                insufficient.incrementAndGet();
            } catch (Throwable t) {
                unexpected.add(t);
            }
            return null;
        };

        for (Future<Void> f : race(taskA, taskB)) {
            f.get();
        }

        assertThat(unexpected).isEmpty();
        assertThat(success.get()).isEqualTo(1);
        assertThat(insufficient.get()).isEqualTo(1);
        // 恰有 3 个单位被预留，且分属同一笔预留（另一笔整笔失败回滚）
        assertThat(componentRepository.findByStatusOrderByExpiresAtAscBarcodeAsc(ComponentStatus.RESERVED))
                .hasSize(3);
        assertThat(componentRepository.findByStatusOrderByExpiresAtAscBarcodeAsc(ComponentStatus.AVAILABLE))
                .hasSize(1);
        assertThat(reservationRepository.findAll()).hasSize(1);
        assertThat(itemRepository.findAll()).hasSize(3);
    }

    @Test
    void concurrentIssueIsIdempotent() throws Exception {
        register("CI-1", TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("CR-ISSUE", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);

        Callable2<ReservationView> issue = () -> reservationService.issue("CR-ISSUE");
        List<Future<ReservationView>> futures = race(issue, issue);

        for (Future<ReservationView> f : futures) {
            assertThat(f.get().status()).isEqualTo(ReservationStatus.ISSUED);
        }
        Reservation r = reservationRepository.findByRequest_RequestNo("CR-ISSUE").orElseThrow();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.ISSUED);
        assertThat(componentRepository.findByBarcode("CI-1").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.ISSUED);
    }

    @Test
    void issueAndExpiredReleaseRaceLeavesExactlyOneTerminalState() throws Exception {
        register("RC-1", TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("CR-RACE", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);

        // 线程 A 在截止前发放；线程 B 先把时钟推进到期之后，再执行到期清理
        Callable2<Void> issue = () -> {
            reservationService.issue("CR-RACE");
            return null;
        };
        Callable2<Void> release = () -> {
            clock.advance(Duration.ofHours(2));
            reservationService.releaseExpired();
            return null;
        };
        for (Future<Void> f : race(issue, release)) {
            f.get();
        }

        // 无论谁先生效，最终状态必须自洽：发放则单位不可恢复，释放则单位回到可用
        Reservation r = reservationRepository.findByRequest_RequestNo("CR-RACE").orElseThrow();
        ComponentStatus unitStatus = componentRepository.findByBarcode("RC-1").orElseThrow().getStatus();
        if (r.getStatus() == ReservationStatus.ISSUED) {
            assertThat(unitStatus).isEqualTo(ComponentStatus.ISSUED);
            assertThat(r.getIssuedAt()).isNotNull();
            assertThat(r.getReleasedAt()).isNull();
        } else {
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(unitStatus).isEqualTo(ComponentStatus.AVAILABLE);
            assertThat(r.getReleasedAt()).isNotNull();
            assertThat(r.getIssuedAt()).isNull();
        }
        // 之后再发放/释放都不再改变状态
        ReservationView after = reservationService.issue("CR-RACE");
        assertThat(after.status()).isEqualTo(r.getStatus());
        assertThat(reservationService.releaseExpired()).isZero();
    }

    @Test
    void conditionalTransitionAllowsExactlyOneWinner() throws Exception {
        register("CT-1", TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("CR-COND", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        Long reservationId = reservationRepository.findByRequest_RequestNo("CR-COND").orElseThrow().getId();
        Instant now = clock.instant();

        // 直接在数据库层面并发执行两个互斥的条件流转：有且只有一个生效
        Callable2<Integer> markIssued = () ->
                transactionTemplate.execute(tx -> reservationRepository.markIssuedIfReserved(reservationId, now, 0));
        Callable2<Integer> markReleased = () ->
                transactionTemplate.execute(tx -> reservationRepository.markReleasedIfReserved(reservationId, now, 0));

        int updated = 0;
        for (Future<Integer> f : race(markIssued, markReleased)) {
            updated += f.get();
        }
        assertThat(updated).isEqualTo(1);
    }
}
