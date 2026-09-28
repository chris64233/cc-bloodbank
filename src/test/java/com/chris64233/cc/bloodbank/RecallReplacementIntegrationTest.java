package com.chris64233.cc.bloodbank;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.ReplacementStatus;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import com.chris64233.cc.bloodbank.repository.BatchRecallRepository;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.ClinicalRequestRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementItemRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementTaskRepository;
import com.chris64233.cc.bloodbank.repository.ReservationItemRepository;
import com.chris64233.cc.bloodbank.repository.ReservationRepository;
import com.chris64233.cc.bloodbank.service.ComponentService;
import com.chris64233.cc.bloodbank.service.InsufficientStockException;
import com.chris64233.cc.bloodbank.service.RecallBlockedException;
import com.chris64233.cc.bloodbank.service.RecallService;
import com.chris64233.cc.bloodbank.service.RecallView;
import com.chris64233.cc.bloodbank.service.ReplacementTaskView;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次召回与召回换血：发布幂等/冲突、生效冻结、原子换血、库存不足阻止、
 * 人工确认终止、查询与重复执行幂等。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class RecallReplacementIntegrationTest {

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

    private ComponentStatus statusOf(String barcode) {
        return componentRepository.findByBarcode(barcode).orElseThrow().getStatus();
    }

    private RecallView publishRecallNow(String eventNo, String batch) {
        return recallService.publishRecall(eventNo, batch, "外观异常", clock.instant());
    }

    @Test
    void duplicateRecallSameContentReturnsOriginalAndDifferentContentConflicts() {
        RecallView first = publishRecallNow("EV-1", "BATCH-R");
        assertThat(first.eventNo()).isEqualTo("EV-1");
        assertThat(first.activated()).isTrue();

        // 同号同内容：返回原结果，不新建召回
        RecallView replay = recallService.publishRecall("EV-1", "BATCH-R", "外观异常", clock.instant());
        assertThat(replay.recallId()).isEqualTo(first.recallId());
        assertThat(recallRepository.findAll()).hasSize(1);

        // 同号异内容（批次/原因/生效时间任一不同）：冲突
        assertThatThrownBy(() -> recallService.publishRecall("EV-1", "BATCH-OTHER", "外观异常", clock.instant()))
                .isInstanceOf(RequestConflictException.class);
        assertThatThrownBy(() -> recallService.publishRecall("EV-1", "BATCH-R", "原因不同", clock.instant()))
                .isInstanceOf(RequestConflictException.class);
        assertThatThrownBy(() -> recallService.publishRecall(
                "EV-1", "BATCH-R", "外观异常", clock.instant().plusSeconds(1)))
                .isInstanceOf(RequestConflictException.class);
    }

    @Test
    void effectiveRecallFreezesAvailableReservedAndIssuedUnitsAndBlocksNewReservation() {
        // 在库、被预留、已发放各一个（同批次），外加一个其他批次单位
        register("R-AVAIL", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("R-RESV", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("R-ISSUE", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(20)));
        // RQ-HOLD 占 R-AVAIL/R-RESV 中较早的一个（条码 R-AVAIL 较早到期相同）
        reservationService.createReservation("RQ-HOLD", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        // RQ-ISSUE 占剩余一个并发放
        reservationService.createReservation("RQ-ISSUE", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        reservationService.issue("RQ-ISSUE");

        publishRecallNow("EV-2", "BATCH-R");

        // 在库与被预留的同批单位冻结；已发放单位终态不变
        String heldBarcode = reservationService.getRequest("RQ-HOLD").reservation()
                .items().get(0).barcode();
        assertThat(statusOf(heldBarcode)).isEqualTo(ComponentStatus.RECALLED);
        assertThat(statusOf("R-ISSUE")).isEqualTo(ComponentStatus.ISSUED);
        // 批次内其余单位也冻结，且没有其他合格单位，新预留整笔失败
        assertThatThrownBy(() -> reservationService.createReservation(
                "RQ-NEW", AboGroup.O, RhFactor.POSITIVE, ComponentType.RED_CELLS, 1, LATEST_USE))
                .isInstanceOf(InsufficientStockException.class);
        // 受影响的未发放预留产生了换血任务
        assertThat(recallService.getRecall("EV-2").tasks())
                .extracting("requestNo").containsExactly("RQ-HOLD");
    }

    @Test
    void recallCreatesReplacementTaskForAffectedUnexpiredReservation() {
        // 被召回单位到期最早（+16h）因而被预留先选中；替代单位更晚到期
        register("OLD-1", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(16)));
        register("NEW-1", "BATCH-X", TestClockConfig.T0.plus(Duration.ofHours(18)));
        register("NEW-2", "BATCH-X", TestClockConfig.T0.plus(Duration.ofHours(22)));
        reservationService.createReservation("RQ-RP", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);

        RecallView recall = publishRecallNow("EV-3", "BATCH-R");

        assertThat(recall.tasks()).hasSize(1);
        assertThat(recall.tasks().get(0).requestNo()).isEqualTo("RQ-RP");
        assertThat(recall.tasks().get(0).status()).isEqualTo(ReplacementStatus.PENDING);

        // 召回后、换血前：被召回单位不能发放
        assertThatThrownBy(() -> reservationService.issue("RQ-RP"))
                .isInstanceOf(RecallBlockedException.class);

        ReplacementTaskView result = recallService.replaceForRequest("RQ-RP");
        assertThat(result.status()).isEqualTo(ReplacementStatus.REPLACED);
        // 优先选择较早到期的合格单位 NEW-1（18h < 22h）
        assertThat(result.replacements()).hasSize(1);
        assertThat(result.replacements().get(0).oldBarcode()).isEqualTo("OLD-1");
        assertThat(result.replacements().get(0).newBarcode()).isEqualTo("NEW-1");
        assertThat(result.replacements().get(0).eventNo()).isEqualTo("EV-3");

        RequestView after = reservationService.getRequest("RQ-RP");
        assertThat(after.reservation().items()).extracting("barcode").containsExactly("NEW-1");
        assertThat(after.reservation().blocked()).isFalse();
        assertThat(after.reservation().replacements()).hasSize(1);
        assertThat(statusOf("OLD-1")).isEqualTo(ComponentStatus.RECALLED);
        assertThat(statusOf("NEW-1")).isEqualTo(ComponentStatus.RESERVED);
        assertThat(statusOf("NEW-2")).isEqualTo(ComponentStatus.AVAILABLE);

        // 换血后可正常发放替代单位
        ReservationView issued = reservationService.issue("RQ-RP");
        assertThat(issued.status()).isEqualTo(ReservationStatus.ISSUED);
        assertThat(statusOf("NEW-1")).isEqualTo(ComponentStatus.ISSUED);
    }

    @Test
    void insufficientStockKeepsReservationBlockedAndDoesNotReplacePartially() {
        // 两个被召回单位到期更早，先被预留选中；仅 1 个更晚到期的合格替代单位
        register("OLD-A", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(16)));
        register("OLD-B", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(17)));
        register("ONLY", "BATCH-X", TestClockConfig.T0.plus(Duration.ofHours(19)));
        reservationService.createReservation("RQ-SHORT", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 2, LATEST_USE);
        publishRecallNow("EV-4", "BATCH-R");

        ReplacementTaskView result = recallService.replaceForRequest("RQ-SHORT");

        assertThat(result.status()).isEqualTo(ReplacementStatus.PENDING);
        assertThat(result.unresolvedReason()).contains("需要替换 2");
        // 原预留关系保留、标记不可发放；唯一可用替代单位未被占用（不做半截替换）
        RequestView view = reservationService.getRequest("RQ-SHORT");
        assertThat(view.reservation().blocked()).isTrue();
        assertThat(view.reservation().items()).extracting("barcode")
                .containsExactlyInAnyOrder("OLD-A", "OLD-B");
        assertThat(statusOf("ONLY")).isEqualTo(ComponentStatus.AVAILABLE);
        assertThat(replacementItemRepository.findAll()).isEmpty();
        assertThatThrownBy(() -> reservationService.issue("RQ-SHORT"))
                .isInstanceOf(RecallBlockedException.class);

        // 补齐库存后重试：一次性换血成功，阻止解除；较早到期的 ONLY 被优先选用
        register("MORE", "BATCH-Y", TestClockConfig.T0.plus(Duration.ofHours(23)));
        ReplacementTaskView retried = recallService.replaceForRequest("RQ-SHORT");
        assertThat(retried.status()).isEqualTo(ReplacementStatus.REPLACED);
        assertThat(retried.replacements()).extracting("newBarcode")
                .containsExactlyInAnyOrder("ONLY", "MORE");
        assertThat(reservationService.getRequest("RQ-SHORT").reservation().blocked()).isFalse();
    }

    @Test
    void repeatedReplaceExecutionReturnsSameResult() {
        register("OLD-X", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(16)));
        register("NEW-X", "BATCH-X", TestClockConfig.T0.plus(Duration.ofHours(20)));
        reservationService.createReservation("RQ-IDEM", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        publishRecallNow("EV-5", "BATCH-R");

        ReplacementTaskView first = recallService.replaceForRequest("RQ-IDEM");
        ReplacementTaskView second = recallService.replaceForRequest("RQ-IDEM");

        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(second.status()).isEqualTo(ReplacementStatus.REPLACED);
        assertThat(second.replacements()).hasSize(1);
        // 没有重复换血：仍只占一个替代单位
        assertThat(componentRepository.findByStatusOrderByExpiresAtAscBarcodeAsc(ComponentStatus.RESERVED))
                .extracting("barcode").containsExactly("NEW-X");
        assertThat(replacementItemRepository.findAll()).hasSize(1);
    }

    @Test
    void manualUnresolvableReleasesGoodUnitsAndTerminatesReservation() {
        // 一笔预留持有 2 个单位：一个属被召回批次、一个合格；库存中没有多余替代单位
        register("BAD-1", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("GOOD-1", "BATCH-X", TestClockConfig.T0.plus(Duration.ofHours(20)));
        reservationService.createReservation("RQ-MIX", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 2, LATEST_USE);
        publishRecallNow("EV-6", "BATCH-R");

        ReplacementTaskView result = recallService.confirmUnresolvable("RQ-MIX");

        assertThat(result.status()).isEqualTo(ReplacementStatus.UNRESOLVABLE);
        RequestView view = reservationService.getRequest("RQ-MIX");
        assertThat(view.reservation().status()).isEqualTo(ReservationStatus.TERMINATED);
        // 合格单位释放回库；被召回单位保持冻结
        assertThat(statusOf("GOOD-1")).isEqualTo(ComponentStatus.AVAILABLE);
        assertThat(statusOf("BAD-1")).isEqualTo(ComponentStatus.RECALLED);
        assertThat(itemRepository.findActiveByReservationId(
                view.reservation().reservationId())).isEmpty();

        // 重复确认幂等返回已有结果
        ReplacementTaskView again = recallService.confirmUnresolvable("RQ-MIX");
        assertThat(again.status()).isEqualTo(ReplacementStatus.UNRESOLVABLE);
        assertThat(again.taskId()).isEqualTo(result.taskId());
        // 终止的预留不能再发放
        assertThat(reservationService.issue("RQ-MIX").status()).isEqualTo(ReservationStatus.TERMINATED);
    }

    @Test
    void recallBeforeEffectiveIsActivatedBySweeperAndFreezesImmediatelyWhenDue() {
        register("FUT-1", "BATCH-F", TestClockConfig.T0.plus(Duration.ofHours(20)));
        Instant future = TestClockConfig.T0.plus(Duration.ofHours(2));
        RecallView created = recallService.publishRecall("EV-7", "BATCH-F", "污染", future);

        assertThat(created.activated()).isFalse();
        // 未生效前批次仍可参与预留
        assertThat(statusOf("FUT-1")).isEqualTo(ComponentStatus.AVAILABLE);

        // 未到生效时间，激活无效果
        assertThat(recallService.activateDueRecalls()).isZero();

        clock.advance(Duration.ofHours(2));
        assertThat(recallService.activateDueRecalls()).isEqualTo(1);
        assertThat(recallService.getRecall("EV-7").activated()).isTrue();
        assertThat(statusOf("FUT-1")).isEqualTo(ComponentStatus.RECALLED);
        // 重复激活幂等
        assertThat(recallService.activateDueRecalls()).isZero();
    }

    @Test
    void issuedAndReleasedReservationsAreNotAffectedByLaterRecall() {
        // 已发放单位
        register("DONE-1", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(20)));
        reservationService.createReservation("RQ-DONE", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        reservationService.issue("RQ-DONE");
        // 到期释放单位
        register("FREE-1", "BATCH-R2", TestClockConfig.T0.plus(Duration.ofHours(20)));
        reservationService.createReservation("RQ-EXPD", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        clock.advance(Duration.ofHours(2));
        reservationService.releaseExpired();
        clock.setInstant(TestClockConfig.T0);

        RecallView recallR = publishRecallNow("EV-8A", "BATCH-R");
        RecallView recallR2 = recallService.publishRecall("EV-8B", "BATCH-R2", "原因", TestClockConfig.T0);

        // 已发放、已释放的预留不产生换血任务
        assertThat(recallR.tasks()).isEmpty();
        assertThat(recallR2.tasks()).isEmpty();
        // 已发放单位终态不变；已释放回库的单位在召回生效时被冻结
        assertThat(statusOf("DONE-1")).isEqualTo(ComponentStatus.ISSUED);
        assertThat(statusOf("FREE-1")).isEqualTo(ComponentStatus.RECALLED);
    }

    @Test
    void pendingReplacementBecomesObsoleteWhenReservationExpires() {
        register("PEND-1", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(16)));
        register("LATE-1", "BATCH-X", TestClockConfig.T0.plus(Duration.ofHours(20)));
        reservationService.createReservation("RQ-PEND", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        publishRecallNow("EV-9", "BATCH-R");
        // 先不换血，推进到预留到期后再处理：应按到期释放并把任务作废，而非换血
        clock.advance(Duration.ofHours(2));

        ReplacementTaskView result = recallService.replaceForRequest("RQ-PEND");

        assertThat(result.status()).isEqualTo(ReplacementStatus.OBSOLETE);
        RequestView view = reservationService.getRequest("RQ-PEND");
        assertThat(view.reservation().status()).isEqualTo(ReservationStatus.RELEASED);
        // 合格替代单位没有被占用；被召回单位保持冻结
        assertThat(statusOf("LATE-1")).isEqualTo(ComponentStatus.AVAILABLE);
        assertThat(statusOf("PEND-1")).isEqualTo(ComponentStatus.RECALLED);
    }

    @Test
    void recallQueryShowsScopeAffectedRequestsAndUnresolvedReason() {
        register("Q-A", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(20)));
        register("Q-B", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(21)));
        reservationService.createReservation("RQ-Q1", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        reservationService.createReservation("RQ-Q2", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        // RQ-Q2 无替代单位（两个在库单位都已被预留，剩余被召回）→ 不足
        RecallView view = publishRecallNow("EV-10", "BATCH-R");

        assertThat(view.batchNo()).isEqualTo("BATCH-R");
        assertThat(view.reason()).isEqualTo("外观异常");
        assertThat(view.tasks()).extracting("requestNo")
                .containsExactlyInAnyOrder("RQ-Q1", "RQ-Q2");

        // RQ-Q1 库存不足挂起（没有合格替代）；查询含未解决原因
        recallService.replaceForRequest("RQ-Q1");
        RecallView after = recallService.getRecall("EV-10");
        ReplacementTaskView q1 = after.tasks().stream()
                .filter(t -> t.requestNo().equals("RQ-Q1")).findFirst().orElseThrow();
        assertThat(q1.status()).isEqualTo(ReplacementStatus.PENDING);
        assertThat(q1.unresolvedReason()).isNotBlank();
    }

    @Test
    void recallFreezesReservedUnitsAtomicWithReservationStateVersion() {
        // 单批单位到期更早先被预留，替代单位更晚到期
        register("V-OLD", "BATCH-R", TestClockConfig.T0.plus(Duration.ofHours(16)));
        register("V-NEW", "BATCH-X", TestClockConfig.T0.plus(Duration.ofHours(20)));
        reservationService.createReservation("RQ-VER", AboGroup.O, RhFactor.POSITIVE,
                ComponentType.RED_CELLS, 1, LATEST_USE);
        long beforeVersion = reservationRepository.findByRequest_RequestNo("RQ-VER").orElseThrow().getStateVersion();

        publishRecallNow("EV-11", "BATCH-R");
        recallService.replaceForRequest("RQ-VER");

        long afterVersion = reservationRepository.findByRequest_RequestNo("RQ-VER").orElseThrow().getStateVersion();
        assertThat(afterVersion).isEqualTo(beforeVersion + 1);
        // 历史明细保留：旧单位 active=false，新单位 active=true
        Long reservationId = reservationRepository.findByRequest_RequestNo("RQ-VER").orElseThrow().getId();
        assertThat(itemRepository.findByReservationIdWithComponent(reservationId)).hasSize(2);
        assertThat(itemRepository.findActiveByReservationId(reservationId))
                .extracting(i -> i.getComponent().getBarcode()).containsExactly("V-NEW");
    }

    @Test
    void rejectedRecallPublicationForBlankFields() {
        assertThatThrownBy(() -> recallService.publishRecall("  ", "B", "r", clock.instant()))
                .isInstanceOf(com.chris64233.cc.bloodbank.service.BadRequestException.class);
        assertThatThrownBy(() -> recallService.publishRecall("E", " ", "r", clock.instant()))
                .isInstanceOf(com.chris64233.cc.bloodbank.service.BadRequestException.class);
        assertThatThrownBy(() -> recallService.publishRecall("E", "B", " ", clock.instant()))
                .isInstanceOf(com.chris64233.cc.bloodbank.service.BadRequestException.class);
    }
}
