package com.chris64233.cc.bloodbank.domain;

import com.chris64233.cc.bloodbank.repo.ReservationItemRepository;
import com.chris64233.cc.bloodbank.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 直接验证数据库层兜底：同一单位不能同时存在于两条 ALLOCATED 明细；
 * 旧明细流转为 RELEASED 后（生成列变 NULL），该单位可再次出现在新的有效明细中。
 */
class ActiveItemUniqueConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private ReservationItemRepository itemRepository;

    @Autowired
    private EntityManager entityManager;

    private Reservation newReservation(String no) {
        return new Reservation(no, BloodType.O_NEG, ComponentType.RED_CELLS, 1,
                Instant.parse("2026-09-29T08:00:00Z"), T0.plus(1, ChronoUnit.HOURS));
    }

    @Test
    void twoActiveItemsForSameUnitAreRejectedByDatabase() {
        BloodUnit unit = registerUnit("K-U1", ComponentType.RED_CELLS, BloodType.O_NEG,
                Instant.parse("2026-09-29T08:00:00Z"));
        Reservation r1 = reservationRepository.save(newReservation("K-R1"));
        Reservation r2 = reservationRepository.save(newReservation("K-R2"));

        itemRepository.saveAndFlush(new ReservationItem(r1, 1, unit));

        // 同一单位的第二条有效明细必须被生成列唯一约束拒绝
        assertThatThrownBy(() ->
                itemRepository.saveAndFlush(new ReservationItem(r2, 1, unit)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void unitCanReappearInNewActiveItemAfterOldOneIsReleased() {
        BloodUnit unit = registerUnit("K-U2", ComponentType.RED_CELLS, BloodType.O_NEG,
                Instant.parse("2026-09-29T08:00:00Z"));
        Reservation r1 = reservationRepository.save(newReservation("K-R3"));
        Reservation r2 = reservationRepository.save(newReservation("K-R4"));

        ReservationItem first = itemRepository.saveAndFlush(new ReservationItem(r1, 1, unit));
        Long firstId = first.getId();

        // 旧明细离开 ALLOCATED（模拟到期释放），生成列 active_unit_barcode 变 NULL
        int changed = itemRepository.compareAndSetStatusForReservation(
                "K-R3", ItemStatus.ALLOCATED, ItemStatus.RELEASED);
        assertThat(changed).isEqualTo(1);
        entityManager.clear();
        assertThat(itemRepository.findById(firstId).orElseThrow().getStatus())
                .isEqualTo(ItemStatus.RELEASED);

        // 同一单位再次进入新的有效明细：唯一约束放行
        assertThatCode(() ->
                itemRepository.saveAndFlush(new ReservationItem(r2, 1, unit)))
                .doesNotThrowAnyException();
    }
}
