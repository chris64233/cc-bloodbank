package com.chris64233.cc.bloodbank;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.ReplacementStatus;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import com.chris64233.cc.bloodbank.service.ComponentService;
import com.chris64233.cc.bloodbank.service.IssueBlockedException;
import com.chris64233.cc.bloodbank.service.RecallDetailView;
import com.chris64233.cc.bloodbank.service.RecallService;
import com.chris64233.cc.bloodbank.service.RecallView;
import com.chris64233.cc.bloodbank.service.RequestConflictException;
import com.chris64233.cc.bloodbank.service.ReplacementService;
import com.chris64233.cc.bloodbank.service.ReplacementTaskView;
import com.chris64233.cc.bloodbank.service.RequestView;
import com.chris64233.cc.bloodbank.service.ReservationService;
import com.chris64233.cc.bloodbank.service.ReservationView;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.ClinicalRequestRepository;
import com.chris64233.cc.bloodbank.repository.RecallRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementItemRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementTaskRepository;
import com.chris64233.cc.bloodbank.repository.ReservationItemRepository;
import com.chris64233.cc.bloodbank.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次召回与召回后换血：
 * 召回幂等/冲突、在库隔离、换血任务生成、相容与 FEFO 选单位、库存不足原子阻断、
 * 重复执行幂等、人工终止、到期释放作废、查询展示（召回范围/受影响申请/换血前后明细）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class RecallReplacementServiceTest {

    private static final Instant LATEST_USE = TestClockConfig.T0.plus(Duration.ofHours(12));

    @Autowired RecallService recallService;
    @Autowired ReplacementService replacementService;
    @Autowired ReservationService reservationService;
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

    private void register(String barcode, String batch, ComponentType type, AboGroup abo, RhFactor rh,
                          Instant expiresAt) {
        componentService.register(barcode, batch, type, abo, rh,
                TestClockConfig.T0.minus(Duration.ofDays(1)), expiresAt);
    }

    private void register(String barcode, String batch, Instant expiresAt) {
        register(barcode, batch, ComponentType.RED_CELLS, AboGroup.O, RhFactor.POSITIVE, expiresAt);
    }

    private void register(String barcode, String batch, AboGroup abo, RhFactor rh, Instant expiresAt) {
        register(barcode, batch, ComponentType.RED_CELLS, abo, rh, expiresAt);
    }

    private RecallView recallNow(String eventNo, String batch) {
        return recallService.publish(eventNo, batch, "模拟召回原因", TestClockConfig.T0);
    }

    private ReplacementTaskView onlyTask(String eventNo) {
        var tasks = recallService.getRecall(eventNo).affectedRequests();
        assertThat(tasks).hasSize(1);
        return tasks.get(0);
    }

    @Test
    void effectiveRecallQuarantinesAvailableStockAndBlocksNewReservations() {
        register("Q-AV", "B1", TestClockConfig.T0.plus(Duration.ofHours(24)));
        register("Q-OTHER", "B2", TestClockConfig.T0.plus(Duration.ofHours(24)));

        RecallView view = recallNow("EV-1", "B1");

        assertThat(view.activated()).isTrue();
        assertThat(componentRepository.findByBarcode("Q-AV").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RECALLED);
        assertThat(componentRepository.findByBarcode("Q-OTHER").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.AVAILABLE);

        // 被召回批次不能参与新预留：仅有其他批次 1 个单位，要 2 个 → 整笔失败
        assertThatThrownBy(() -> reservationService.createReservation(
                "RQ-NEW", AboGroup.O, RhFactor.POSITIVE, ComponentType.RED_CELLS, 2, LATEST_USE))
                .isInstanceOf(com.chris64233.cc.bloodbank.service.InsufficientStockException.class);
        // 其他批次单位未被占用
        assertThat(componentRepository.findByBarcode("Q-OTHER").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.AVAILABLE);
    }

    @Test
    void recallCreatesPendingTaskAndBlocksIssuingForAffectedReservation() {
        register("RC-1", "B1", TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("RQ-1", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);

        recallNow("EV-2", "B1");

        RequestView requestView = reservationService.getRequest("RQ-1");
        assertThat(requestView.reservation().status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(requestView.reservation().issueBlocked()).isTrue();
        assertThat(componentRepository.findByBarcode("RC-1").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RECALLED);
        assertThat(requestView.replacementTasks()).hasSize(1);
        assertThat(requestView.replacementTasks().get(0).status()).isEqualTo(ReplacementStatus.PENDING);

        // 被召回单位不得发放
        assertThatThrownBy(() -> reservationService.issue("RQ-1"))
                .isInstanceOf(IssueBlockedException.class);
        assertThat(componentRepository.findByBarcode("RC-1").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RECALLED);
    }

    @Test
    void successfulReplacementSwapsAllUnitsAtomicallyAndUnblocksIssuing() {
        // 被召回单位到期较早（初始预留按 FEFO 选中），替代品到期较晚但仍不早于最晚使用时间
        register("OLD-1", "B1", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("NEW-1", "B2", TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("RQ-2", AboGroup.A, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        recallNow("EV-3", "B1");
        Long taskId = onlyTask("EV-3").taskId();

        ReplacementTaskView result = replacementService.executeTask(taskId);

        assertThat(result.status()).isEqualTo(ReplacementStatus.COMPLETED);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).removed().barcode()).isEqualTo("OLD-1");
        assertThat(result.items().get(0).replacement().barcode()).isEqualTo("NEW-1");
        assertThat(result.items().get(0).outcome()).isEqualTo("REPLACED");

        // 被召回单位隔离；替代单位被本预留占用
        assertThat(componentRepository.findByBarcode("OLD-1").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RECALLED);
        assertThat(componentRepository.findByBarcode("NEW-1").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RESERVED);

        // 阻断解除，当前预留明细只剩替代单位；发放的是替代品
        ReservationView reservation = reservationService.getRequest("RQ-2").reservation();
        assertThat(reservation.issueBlocked()).isFalse();
        assertThat(reservation.items()).extracting("barcode").containsExactly("NEW-1");

        reservationService.issue("RQ-2");
        assertThat(componentRepository.findByBarcode("NEW-1").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.ISSUED);
        assertThat(componentRepository.findByBarcode("OLD-1").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RECALLED);
    }

    @Test
    void replacementUsesOnlyCompatibleUnitsEligibleByExpiryAndEarliestFirst() {
        // 患者 A 阳性，最晚使用时间 T0+12h
        register("OLD", "B1", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("CAND-EARLY", "B2", AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(6)));
        register("CAND-ABO-BAD", "B3", AboGroup.B, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(24)));
        register("CAND-TYPE-BAD", "B5", ComponentType.PLASMA, AboGroup.AB, RhFactor.POSITIVE,
                TestClockConfig.T0.plus(Duration.ofHours(24)));
        register("CAND-LATE", "B6", AboGroup.O, RhFactor.POSITIVE, TestClockConfig.T0.plus(Duration.ofHours(30)));
        reservationService.createReservation("RQ-3", AboGroup.A, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        recallNow("EV-4", "B1");

        ReplacementTaskView result = replacementService.executeForRequest("EV-4", "RQ-3");

        // 唯一合格的是 CAND-LATE（CAND-EARLY 早于最晚使用时间，B 型 ABO 不相容，PLASMA 成分不符）
        assertThat(result.status()).isEqualTo(ReplacementStatus.COMPLETED);
        assertThat(result.items().get(0).replacement().barcode()).isEqualTo("CAND-LATE");
    }

    @Test
    void insufficientStockKeepsReservationBlockedWithoutPartialSwapThenRetrySucceeds() {
        // 2 个被召回单位初始即被 FEFO 选中（到期早于替代品）； initially 只有 1 个合格替代品
        register("OLD-1", "B1", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("OLD-2", "B1", TestClockConfig.T0.plus(Duration.ofHours(22)));
        register("ONLY-ALT", "B2", TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("RQ-4", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 2, LATEST_USE);
        recallNow("EV-5", "B1");
        Long taskId = onlyTask("EV-5").taskId();

        ReplacementTaskView failed = replacementService.executeTask(taskId);
        assertThat(failed.status()).isEqualTo(ReplacementStatus.PENDING);
        assertThat(failed.unresolvedReason()).contains("合格替代品不足");
        // 未做任何部分替换：替代品仍在库，原单位仍占用且隔离，明细仍为两个被召回单位
        assertThat(componentRepository.findByBarcode("ONLY-ALT").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.AVAILABLE);
        ReservationView blocked = reservationService.getRequest("RQ-4").reservation();
        assertThat(blocked.issueBlocked()).isTrue();
        assertThat(blocked.items()).extracting("barcode").containsExactlyInAnyOrder("OLD-1", "OLD-2");
        assertThatThrownBy(() -> reservationService.issue("RQ-4")).isInstanceOf(IssueBlockedException.class);

        // 补货后重试：一次性全部替换
        register("SECOND-ALT", "B3", TestClockConfig.T0.plus(Duration.ofHours(26)));
        ReplacementTaskView retried = replacementService.executeTask(taskId);
        assertThat(retried.status()).isEqualTo(ReplacementStatus.COMPLETED);
        assertThat(retried.items()).extracting(i -> i.replacement().barcode())
                .containsExactlyInAnyOrder("ONLY-ALT", "SECOND-ALT");
        assertThat(reservationService.getRequest("RQ-4").reservation().issueBlocked()).isFalse();
    }

    @Test
    void repeatedExecutionIsIdempotentAndKeepsSameResult() {
        register("OLD", "B1", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("NEW", "B2", TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("RQ-5", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        recallNow("EV-6", "B1");
        Long taskId = onlyTask("EV-6").taskId();

        ReplacementTaskView first = replacementService.executeTask(taskId);
        ReplacementTaskView second = replacementService.executeTask(taskId);

        assertThat(second.status()).isEqualTo(ReplacementStatus.COMPLETED);
        assertThat(second.completedAt()).isEqualTo(first.completedAt());
        assertThat(second.items()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(first.items());
        // 没有重复占用：NEW 仍是 RESERVED 而非被二次操作
        assertThat(componentRepository.findByBarcode("NEW").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RESERVED);
        assertThat(replacementItemRepository.findAll()).hasSize(1);
    }

    @Test
    void manualTerminationReleasesGoodUnitsAndQuarantinesRecalledOnes() {
        // 两个单位都被选中（各批次到期错开）；召回 B1 后 BAD 被隔离、GOOD 仍合格占用
        register("BAD", "B1", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("GOOD", "B2", TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("RQ-6", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 2, LATEST_USE);
        recallNow("EV-7", "B1");
        Long taskId = onlyTask("EV-7").taskId();
        // 库存不足，无法换血
        assertThat(replacementService.executeTask(taskId).status()).isEqualTo(ReplacementStatus.PENDING);

        ReservationView terminated = replacementService.terminateUnresolvable("RQ-6", "EV-7", "临床取消");

        assertThat(terminated.status()).isEqualTo(ReservationStatus.TERMINATED);
        assertThat(terminated.items()).isEmpty();
        assertThat(componentRepository.findByBarcode("GOOD").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.AVAILABLE);
        assertThat(componentRepository.findByBarcode("BAD").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RECALLED);
        assertThat(replacementTaskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(ReplacementStatus.TERMINATED);

        // 终态后发放不再改变状态；重复终止幂等
        ReservationView again = replacementService.terminateUnresolvable("RQ-6", "EV-7", "再次确认");
        assertThat(again.status()).isEqualTo(ReservationStatus.TERMINATED);
        assertThat(componentRepository.findByBarcode("GOOD").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.AVAILABLE);
    }

    @Test
    void expiryReleaseAbandonsPendingTasksAndQuarantinesRecalledUnits() {
        register("BAD", "B1", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("GOOD", "B2", TestClockConfig.T0.plus(Duration.ofHours(24)));
        reservationService.createReservation("RQ-7", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 2, LATEST_USE);
        recallNow("EV-8", "B1");
        Long taskId = onlyTask("EV-8").taskId();

        clock.advance(Duration.ofHours(2));
        assertThat(reservationService.releaseExpired()).isEqualTo(1);

        assertThat(reservationService.getRequest("RQ-7").reservation().status())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThat(componentRepository.findByBarcode("GOOD").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.AVAILABLE);
        assertThat(componentRepository.findByBarcode("BAD").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RECALLED);
        assertThat(replacementTaskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(ReplacementStatus.ABANDONED);

        // 任务作废后再执行不会改库存
        ReplacementTaskView view = replacementService.executeTask(taskId);
        assertThat(view.status()).isEqualTo(ReplacementStatus.ABANDONED);
        assertThat(componentRepository.findByBarcode("GOOD").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.AVAILABLE);
    }

    @Test
    void sameEventReplayReturnsOriginalAndDifferentContentConflicts() {
        RecallView first = recallNow("EV-9", "B1");
        Instant t1 = first.publishedAt();

        RecallView replay = recallService.publish("EV-9", "B1", "模拟召回原因", TestClockConfig.T0);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.publishedAt()).isEqualTo(t1);

        assertThatThrownBy(() -> recallService.publish("EV-9", "B2", "模拟召回原因", TestClockConfig.T0))
                .isInstanceOf(RequestConflictException.class);
        assertThatThrownBy(() -> recallService.publish("EV-9", "B1", "不同原因", TestClockConfig.T0))
                .isInstanceOf(RequestConflictException.class);
        assertThatThrownBy(() -> recallService.publish("EV-9", "B1", "模拟召回原因",
                TestClockConfig.T0.plus(Duration.ofHours(1))))
                .isInstanceOf(RequestConflictException.class);
    }

    @Test
    void futureRecallActivatesAtEffectiveTimeViaSweeper() {
        register("FUT-1", "B9", TestClockConfig.T0.plus(Duration.ofHours(24)));
        // 生效时间在 1 小时后：发布时不激活，单位仍可预留
        RecallView pending = recallService.publish("EV-10", "B9", "定时召回",
                TestClockConfig.T0.plus(Duration.ofHours(1)));
        assertThat(pending.activated()).isFalse();
        reservationService.createReservation("RQ-8", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        assertThat(componentRepository.findByBarcode("FUT-1").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RESERVED);

        // 生效前扫描无动作
        assertThat(recallService.activateDue()).isZero();

        clock.advance(Duration.ofHours(1));
        assertThat(recallService.activateDue()).isEqualTo(1);
        assertThat(recallService.activateDue()).isZero(); // 幂等

        assertThat(componentRepository.findByBarcode("FUT-1").orElseThrow().getStatus())
                .isEqualTo(ComponentStatus.RECALLED);
        assertThat(reservationService.getRequest("RQ-8").reservation().issueBlocked()).isTrue();
        assertThat(onlyTask("EV-10").status()).isEqualTo(ReplacementStatus.PENDING);
    }

    @Test
    void sequentialRecallsOnSameReservationEachBlockUntilReplaced() {
        // 初始 FEFO 选中到期最早的 U-A/U-B；替代品 U-X/U-Y 到期较晚留库
        register("U-A", "BA", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("U-B", "BB", TestClockConfig.T0.plus(Duration.ofHours(21)));
        register("U-X", "BX", TestClockConfig.T0.plus(Duration.ofHours(26)));
        register("U-Y", "BY", TestClockConfig.T0.plus(Duration.ofHours(27)));
        reservationService.createReservation("RQ-9", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 2, LATEST_USE);

        recallNow("EV-11A", "BA");
        ReplacementTaskView t1 = onlyTask("EV-11A");
        ReplacementTaskView r1 = replacementService.executeTask(t1.taskId());
        assertThat(r1.status()).isEqualTo(ReplacementStatus.COMPLETED);
        // 另一个单位未受影响，换血完成后解除阻断
        assertThat(reservationService.getRequest("RQ-9").reservation().issueBlocked()).isFalse();

        // 第二次召回命中剩余的原始单位
        recallNow("EV-11B", "BB");
        ReplacementTaskView t2 = onlyTask("EV-11B");
        assertThat(reservationService.getRequest("RQ-9").reservation().issueBlocked()).isTrue();
        ReplacementTaskView r2 = replacementService.executeTask(t2.taskId());
        assertThat(r2.status()).isEqualTo(ReplacementStatus.COMPLETED);
        assertThat(reservationService.getRequest("RQ-9").reservation().issueBlocked()).isFalse();

        ReservationView finalView = reservationService.getRequest("RQ-9").reservation();
        assertThat(finalView.items()).extracting("barcode").containsExactlyInAnyOrder("U-X", "U-Y");
        // 历史明细保留：2 原始行 REPLACED + 2 当前行 ACTIVE
        assertThat(itemRepository.findAll()).hasSize(4);
    }

    @Test
    void recallDetailShowsScopeAffectedRequestsAndBeforeAfterItems() {
        // 两个批次单位到期最早，按 FEFO 先后被两笔预留选中；替代品到期较晚留库
        register("D-ISSUED", "B1", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("D-RES", "B1", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("D-ALT", "B2", TestClockConfig.T0.plus(Duration.ofHours(24)));
        // 一笔已发放预留（不受召回影响）+ 一笔生效预留（受影响）
        reservationService.createReservation("RQ-DONE", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        reservationService.issue("RQ-DONE");
        reservationService.createReservation("RQ-OPEN", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);

        recallNow("EV-12", "B1");
        Long taskId = onlyTask("EV-12").taskId();
        replacementService.executeTask(taskId);

        RecallDetailView detail = recallService.getRecall("EV-12");
        assertThat(detail.recall().eventNo()).isEqualTo("EV-12");
        assertThat(detail.scope()).extracting("barcode").containsExactly("D-ISSUED", "D-RES");
        assertThat(detail.scopeStatusCounts().get(ComponentStatus.ISSUED)).isEqualTo(1);
        assertThat(detail.scopeStatusCounts().get(ComponentStatus.RECALLED)).isEqualTo(1);
        // 已发放预留不生成换血任务；受影响申请只有 RQ-OPEN，含换血前后明细
        assertThat(detail.affectedRequests()).hasSize(1);
        ReplacementTaskView task = detail.affectedRequests().get(0);
        assertThat(task.requestNo()).isEqualTo("RQ-OPEN");
        assertThat(task.status()).isEqualTo(ReplacementStatus.COMPLETED);
        assertThat(task.items().get(0).removed().barcode()).isEqualTo("D-RES");
        assertThat(task.items().get(0).replacement().barcode()).isEqualTo("D-ALT");
    }

    @Test
    void executeForUnknownRecallOrTaskReturns404Semantics() {
        assertThatThrownBy(() -> replacementService.executeTask(999L))
                .isInstanceOf(com.chris64233.cc.bloodbank.service.NotFoundException.class);
        assertThatThrownBy(() -> replacementService.executeForRequest("NOPE", "RQ-X"))
                .isInstanceOf(com.chris64233.cc.bloodbank.service.NotFoundException.class);
        assertThatThrownBy(() -> recallService.getRecall("NOPE"))
                .isInstanceOf(com.chris64233.cc.bloodbank.service.NotFoundException.class);
    }
}
