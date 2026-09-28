package com.chris64233.cc.bloodbank.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台定时激活：到达生效时间的召回立即冻结批次并为受影响申请生成换血任务。
 */
@Component
public class RecallActivationSweeper {

    private static final Logger log = LoggerFactory.getLogger(RecallActivationSweeper.class);

    private final RecallService recallService;

    public RecallActivationSweeper(RecallService recallService) {
        this.recallService = recallService;
    }

    @Scheduled(fixedDelayString = "${bloodbank.recall-sweeper.fixed-delay:5000}")
    public void sweep() {
        int activated = recallService.activateDueRecalls();
        if (activated > 0) {
            log.info("到期召回激活 {} 起", activated);
        }
    }
}
