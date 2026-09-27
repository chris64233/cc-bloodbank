package com.chris64233.cc.bloodbank.support;

import com.chris64233.cc.bloodbank.api.dto.RegisterUnitRequest;
import com.chris64233.cc.bloodbank.api.dto.ReservationRequest;
import com.chris64233.cc.bloodbank.domain.BloodType;
import com.chris64233.cc.bloodbank.domain.BloodUnit;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.repo.BloodUnitRepository;
import com.chris64233.cc.bloodbank.repo.ReservationItemRepository;
import com.chris64233.cc.bloodbank.repo.ReservationRepository;
import com.chris64233.cc.bloodbank.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;

/**
 * 集成测试基类：以 {@link MutableClock} 替换系统 Clock，提供造数与清理辅助。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
public abstract class AbstractIntegrationTest {

    protected static final Instant T0 = TestClockConfiguration.INITIAL_TIME;

    @Autowired
    protected MutableClock clock;
    @Autowired
    protected BloodUnitRepository unitRepository;
    @Autowired
    protected ReservationRepository reservationRepository;
    @Autowired
    protected ReservationItemRepository reservationItemRepository;
    @Autowired
    protected ReservationService reservationService;
    @Autowired
    protected com.chris64233.cc.bloodbank.service.BloodUnitService bloodUnitService;

    @BeforeEach
    void cleanDatabase() {
        // 明细同时外键引用预留与单位，删除顺序：明细 → 预留 → 单位
        reservationItemRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        unitRepository.deleteAllInBatch();
        clock.setInstant(T0);
    }

    protected BloodUnit registerUnit(String barcode, ComponentType component, BloodType bloodType,
                                     Instant expiresAt) {
        return bloodUnitService.register(new RegisterUnitRequest(
                barcode, component, bloodType, expiresAt.minus(Duration.ofDays(30)), expiresAt));
    }

    protected static ReservationRequest request(String requestNo, BloodType patient,
                                                ComponentType component, int count, Instant latestUseAt) {
        return new ReservationRequest(requestNo, patient, component, count, latestUseAt, null);
    }

    protected static ReservationRequest request(String requestNo, BloodType patient,
                                                ComponentType component, int count,
                                                Instant latestUseAt, Instant expireAt) {
        return new ReservationRequest(requestNo, patient, component, count, latestUseAt, expireAt);
    }
}
