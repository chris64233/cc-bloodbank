package com.chris64233.cc.bloodbank.web;

import com.chris64233.cc.bloodbank.service.RecallDetailView;
import com.chris64233.cc.bloodbank.service.RecallService;
import com.chris64233.cc.bloodbank.service.RecallView;
import com.chris64233.cc.bloodbank.service.ReplacementService;
import com.chris64233.cc.bloodbank.service.ReplacementTaskView;
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

/**
 * 批次召回与召回后换血接口。
 */
@RestController
@RequestMapping("/api/recalls")
public class RecallController {

    private final RecallService recallService;
    private final ReplacementService replacementService;

    public RecallController(RecallService recallService, ReplacementService replacementService) {
        this.recallService = recallService;
        this.replacementService = replacementService;
    }

    /** 发布批次召回；相同事件号重放幂等，同号异内容 409 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecallView publish(@Valid @RequestBody CreateRecallRequest request) {
        return recallService.publish(request.eventNo(), request.batchNo(), request.reason(),
                request.effectiveAt());
    }

    /** 手动激活已到生效时间的召回（后台另有定时任务），返回本次激活数 */
    @PostMapping("/activate-due")
    public Map<String, Integer> activateDue() {
        return Map.of("activated", recallService.activateDue());
    }

    /** 召回影响查询：召回范围、受影响申请、换血前后明细、未解决原因 */
    @GetMapping("/{eventNo}")
    public RecallDetailView getRecall(@PathVariable String eventNo) {
        return recallService.getRecall(eventNo);
    }

    /** 按任务 id 执行（或重试）换血；终态任务重复执行返回已有结果 */
    @PostMapping("/tasks/{taskId}/execute")
    public ReplacementTaskView executeTask(@PathVariable Long taskId) {
        return replacementService.executeTask(taskId);
    }

    /** 按召回事件号 + 申请号执行（或重试）换血 */
    @PostMapping("/{eventNo}/requests/{requestNo}/replace")
    public ReplacementTaskView executeForRequest(@PathVariable String eventNo,
                                                 @PathVariable String requestNo) {
        return replacementService.executeForRequest(eventNo, requestNo);
    }

    /** 人工确认无法换血：释放仍占用的合格单位并终止预留 */
    @PostMapping("/{eventNo}/requests/{requestNo}/terminate")
    public ReservationView terminate(@PathVariable String eventNo,
                                     @PathVariable String requestNo,
                                     @RequestBody(required = false) TerminateRequest body) {
        String reason = body == null ? null : body.reason();
        // eventNo 参与定位语义：终止操作要求该申请确有该召回下的未完成任务，由服务层校验
        return replacementService.terminateUnresolvable(requestNo, eventNo, reason);
    }
}
