package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.api.dto.ReservationRequest;
import com.chris64233.cc.bloodbank.api.error.BusinessConflictException;
import com.chris64233.cc.bloodbank.domain.BloodType;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.UnitStatus;
import com.chris64233.cc.bloodbank.repo.ReservationRepository;
import com.chris64233.cc.bloodbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实多线程并发测试：依赖数据库行锁/条件更新兜底，而不是应用层加锁。
 */
class ReservationConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final Instant USE_BY = Instant.parse("2026-09-29T08:00:00Z");

    private record RaceResult(boolean succeeded, String requestNo, Set<String> barcodes,
                              Throwable error) {
    }

    private RaceResult raceReserve(String requestNo, int count, CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            ReservationRequest req = request(requestNo, BloodType.O_NEG,
                    ComponentType.RED_CELLS, count, USE_BY);
            var outcome = reservationService.reserve(req);
            Set<String> barcodes = new HashSet<>();
            outcome.reservation().getItems().forEach(i -> barcodes.add(i.getUnit().getBarcode()));
            return new RaceResult(true, requestNo, barcodes, null);
        } catch (Throwable t) {
            return new RaceResult(false, requestNo, Set.of(), t);
        }
    }

    private static List<RaceResult> awaitAll(List<Future<RaceResult>> futures) {
        return futures.stream().map(f -> {
            try {
                return f.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();
    }

    @Test
    void concurrentSingleUnitReservationsNeverOverlap() throws Exception {
        for (int i = 1; i <= 3; i++) {
            registerUnit("C-U" + i, ComponentType.RED_CELLS, BloodType.O_NEG, USE_BY);
        }
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<RaceResult>> futures = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            String no = "C-REQ-" + i;
            futures.add(pool.submit(() -> raceReserve(no, 1, barrier)));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<RaceResult> results = awaitAll(futures);
        List<RaceResult> winners = results.stream().filter(RaceResult::succeeded).toList();
        List<RaceResult> losers = results.stream().filter(r -> !r.succeeded()).toList();

        assertThat(winners).hasSize(3);
        assertThat(losers).hasSize(2);
        // 失败原因必须是业务冲突/锁竞争而非数据损坏
        assertThat(losers).allSatisfy(r -> assertThat(r.error())
                .isInstanceOfAny(BusinessConflictException.class,
                        org.springframework.dao.PessimisticLockingFailureException.class,
                        org.springframework.dao.CannotAcquireLockException.class,
                        org.springframework.dao.CannotSerializeTransactionException.class));

        Set<String> allocated = new HashSet<>();
        winners.forEach(r -> allocated.addAll(r.barcodes()));
        assertThat(allocated).hasSize(3); // 没有任何单位被两笔预留同时拿到

        assertThat(unitRepository.findByStatusOrderByExpiresAtAscBarcodeAsc(UnitStatus.RESERVED))
                .hasSize(3);
        assertThat(reservationRepository.count()).isEqualTo(3);
    }

    @Test
    void concurrentMultiUnitRequestsAreAtomicExactlyOneWins() throws Exception {
        for (int i = 1; i <= 3; i++) {
            registerUnit("M-U" + i, ComponentType.RED_CELLS, BloodType.O_NEG, USE_BY);
        }
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<RaceResult>> futures = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            String no = "M-REQ-" + i;
            futures.add(pool.submit(() -> raceReserve(no, 2, barrier)));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<RaceResult> results = awaitAll(futures);
        assertThat(results.stream().filter(RaceResult::succeeded).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.succeeded()).count()).isEqualTo(1);

        // 胜方拿走 2 个，败方零残留：没有部分预留
        assertThat(unitRepository.findByStatusOrderByExpiresAtAscBarcodeAsc(UnitStatus.RESERVED))
                .hasSize(2);
        assertThat(unitRepository.findByStatusOrderByExpiresAtAscBarcodeAsc(UnitStatus.AVAILABLE))
                .hasSize(1);
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    /**
     * 发放条件更新与释放条件更新在数据库层直接竞争，重复多轮：
     * 每轮影响行数之和恰好为 1，最终状态唯一且合法。
     */
    @Test
    void issueAndReleaseCasExactlyOneWinsEveryRound() throws Exception {
        int rounds = 20;
        Instant issueClock = T0; // 截止时间 T0+1h，发放条件 expireAt > now 成立，双方真实竞争
        for (int round = 1; round <= rounds; round++) {
            String requestNo = "RACE-" + round;
            registerUnit("RACE-U" + round, ComponentType.RED_CELLS, BloodType.O_NEG, USE_BY);
            reservationService.reserve(request(requestNo, BloodType.O_NEG,
                    ComponentType.RED_CELLS, 1, USE_BY, T0.plus(Duration.ofHours(1))));

            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Integer> issueFuture = pool.submit(() ->
                    runCas(barrier, () -> transactionTemplate.execute(status ->
                            reservationRepository.compareAndSetForIssue(
                                    requestNo, ReservationStatus.RESERVED,
                                    ReservationStatus.ISSUED, issueClock))));
            Future<Integer> releaseFuture = pool.submit(() ->
                    runCas(barrier, () -> transactionTemplate.execute(status ->
                            reservationRepository.compareAndSetStatus(
                                    requestNo, ReservationStatus.RESERVED,
                                    ReservationStatus.RELEASED))));
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("round " + round).isTrue();

            int issueRows = getCasRows(issueFuture);
            int releaseRows = getCasRows(releaseFuture);
            assertThat(issueRows + releaseRows)
                    .as("第 %d 轮：发放与释放影响行数之和必须恰好为 1", round)
                    .isEqualTo(1);

            // 直接 CAS 只断言预留行状态唯一合法；库存联动路径由服务层单线程用例覆盖
            ReservationStatus finalStatus = reservationRepository.findById(requestNo)
                    .orElseThrow().getStatus();
            assertThat(finalStatus)
                    .as("第 %d 轮", round)
                    .isIn(ReservationStatus.ISSUED, ReservationStatus.RELEASED);
            assertThat(finalStatus == ReservationStatus.ISSUED ? issueRows : releaseRows)
                    .isEqualTo(1);
        }
    }

    private int runCas(CyclicBarrier barrier, java.util.function.Supplier<Integer> cas) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return cas.get();
    }

    /** CAS 因锁/序列化冲突抛错等价于“未赢得”，记 0。 */
    private static int getCasRows(Future<Integer> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 两线程同时确认发放：恰好一方完成 RESERVED→ISSUED，另一方幂等返回，库存只变化一次。
     */
    @Test
    void concurrentDuplicateIssuesChangeStateOnce() throws Exception {
        registerUnit("I-U1", ComponentType.RED_CELLS, BloodType.O_NEG, USE_BY);
        registerUnit("I-U2", ComponentType.RED_CELLS, BloodType.O_NEG, USE_BY);
        reservationService.reserve(request("I-REQ", BloodType.O_NEG,
                ComponentType.RED_CELLS, 2, USE_BY, T0.plus(Duration.ofHours(1))));

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ReservationStatus>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return reservationService.issue("I-REQ").getStatus();
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<ReservationStatus> f : futures) {
            assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(ReservationStatus.ISSUED);
        }
        assertThat(unitRepository.findByStatusOrderByExpiresAtAscBarcodeAsc(UnitStatus.ISSUED))
                .hasSize(2);
        assertThat(reservationService.getRequiredWithItems("I-REQ").getStatus())
                .isEqualTo(ReservationStatus.ISSUED);
    }

    /**
     * 到期后两线程同时释放：恰好一方返回 true 并完成释放，另一方幂等返回 false，库存只变化一次。
     */
    @Test
    void concurrentDuplicateReleasesChangeStateOnce() throws Exception {
        registerUnit("L-U1", ComponentType.RED_CELLS, BloodType.O_NEG, USE_BY);
        reservationService.reserve(request("L-REQ", BloodType.O_NEG,
                ComponentType.RED_CELLS, 1, USE_BY, T0.plus(Duration.ofMinutes(10))));
        clock.advance(Duration.ofMinutes(11));

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return reservationService.releaseIfExpired("L-REQ");
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long trueCount = futures.stream().filter(f -> {
            try {
                return f.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).count();
        assertThat(trueCount).isEqualTo(1);

        assertThat(reservationService.getRequiredWithItems("L-REQ").getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThat(unitRepository.findById("L-U1").orElseThrow().getStatus())
                .isEqualTo(UnitStatus.AVAILABLE);
    }
}
