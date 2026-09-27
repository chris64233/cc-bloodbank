package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.Reservation;

/**
 * 预留结果：{@code created=false} 表示相同申请号与内容的重放，返回的是原分配。
 */
public record ReservationOutcome(Reservation reservation, boolean created) {
}
