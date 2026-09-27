package com.chris64233.cc.bloodbank.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台定时清理：到期未发放的预留释放全部单位。
 */
@Component
public class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final ReservationService reservationService;

    public ReservationExpirySweeper(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @Scheduled(fixedDelayString = "${bloodbank.sweeper.fixed-delay:60000}")
    public void sweep() {
        int released = reservationService.releaseExpired();
        if (released > 0) {
            log.info("到期预留释放 {} 笔", released);
        }
    }
}
