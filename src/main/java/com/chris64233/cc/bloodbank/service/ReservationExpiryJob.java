package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.repo.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * 定时扫描到期未发放的预留并释放。
 *
 * <p>扫描只取申请号；每笔释放通过跨 Bean 调用 {@link ReservationService#releaseIfExpired}
 * 在独立事务中完成，单笔失败不影响其他到期预留。
 */
@Component
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final Clock clock;

    public ReservationExpiryJob(ReservationRepository reservationRepository,
                                ReservationService reservationService,
                                Clock clock) {
        this.reservationRepository = reservationRepository;
        this.reservationService = reservationService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${bloodbank.reservation.expiry-scan-delay-ms:30000}",
            initialDelayString = "${bloodbank.reservation.expiry-scan-initial-delay-ms:5000}")
    public int releaseExpiredReservations() {
        List<String> expired = reservationRepository.findExpiredRequestNos(
                ReservationStatus.RESERVED, clock.instant());
        int released = 0;
        for (String requestNo : expired) {
            try {
                if (reservationService.releaseIfExpired(requestNo)) {
                    released++;
                }
            } catch (RuntimeException ex) {
                // 单笔失败不阻断其余到期预留的释放，下一轮扫描会重试
                log.warn("释放到期预留 {} 失败", requestNo, ex);
            }
        }
        if (released > 0) {
            log.info("到期扫描完成，释放预留 {} 笔", released);
        }
        return released;
    }
}
