package com.chris64233.cc.bloodbank.api;

import com.chris64233.cc.bloodbank.api.dto.ReservationRequest;
import com.chris64233.cc.bloodbank.api.dto.ReservationView;
import com.chris64233.cc.bloodbank.service.ReservationOutcome;
import com.chris64233.cc.bloodbank.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 临床用血预留：申请预留、确认发放、预留明细查询。
 */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /**
     * 提交申请并原子预留。首次成功返回 201；相同申请号与内容重放返回原分配（200）；内容不同返回 409。
     */
    @PostMapping
    public ResponseEntity<ReservationView> reserve(@Valid @RequestBody ReservationRequest request) {
        ReservationOutcome outcome = reservationService.reserve(request);
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ReservationView.from(outcome.reservation()));
    }

    /** 预留明细查询 */
    @GetMapping("/{requestNo}")
    public ReservationView get(@PathVariable String requestNo) {
        return ReservationView.from(reservationService.getRequiredWithItems(requestNo));
    }

    @GetMapping
    public List<ReservationView> list() {
        return reservationService.listAll().stream().map(ReservationView::from).toList();
    }

    /** 确认发放：单位进入终态 ISSUED，不可恢复；重复发放幂等 */
    @PostMapping("/{requestNo}/issue")
    public ReservationView issue(@PathVariable String requestNo) {
        return ReservationView.from(reservationService.issue(requestNo));
    }

    /**
     * 手动触发到期释放（通常由定时任务执行）：到期未发放则释放全部单位，返回最新预留。
     */
    @PostMapping("/{requestNo}/expire")
    public ReservationView expire(@PathVariable String requestNo) {
        reservationService.releaseIfExpired(requestNo);
        return ReservationView.from(reservationService.getRequiredWithItems(requestNo));
    }
}
