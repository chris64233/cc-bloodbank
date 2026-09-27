package com.chris64233.cc.bloodbank.web;

import com.chris64233.cc.bloodbank.service.RequestView;
import com.chris64233.cc.bloodbank.service.ReservationService;
import com.chris64233.cc.bloodbank.service.ReservationView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** 提交临床申请并原子预留；相同申请号重放返回原分配 */
    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationView create(@Valid @RequestBody CreateReservationRequest request) {
        return reservationService.createReservation(request.requestNo(), request.patientAbo(),
                request.patientRh(), request.componentType(), request.unitCount(), request.latestUseTime());
    }

    /** 截止前确认发放；重复发放幂等 */
    @PostMapping("/reservations/{requestNo}/issue")
    public ReservationView issue(@PathVariable String requestNo) {
        return reservationService.issue(requestNo);
    }

    /** 申请及预留明细查询 */
    @GetMapping("/requests/{requestNo}")
    public RequestView getRequest(@PathVariable String requestNo) {
        return reservationService.getRequest(requestNo);
    }

    /** 手动触发到期预留释放（后台另有定时任务），返回释放笔数 */
    @PostMapping("/reservations/release-expired")
    public Map<String, Integer> releaseExpired() {
        return Map.of("released", reservationService.releaseExpired());
    }
}
