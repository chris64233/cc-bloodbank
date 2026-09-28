package com.chris64233.cc.bloodbank.web;

import com.chris64233.cc.bloodbank.service.RecallService;
import com.chris64233.cc.bloodbank.service.RecallView;
import com.chris64233.cc.bloodbank.service.ReplacementTaskView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 批次召回与召回换血接口。
 */
@RestController
@RequestMapping("/api/recalls")
public class RecallController {

    private final RecallService recallService;

    public RecallController(RecallService recallService) {
        this.recallService = recallService;
    }

    /** 发布召回（同事件号幂等、异内容冲突）；已到生效时间立即激活 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecallView publish(@Valid @RequestBody CreateRecallRequest request) {
        return recallService.publishRecall(request.eventNo(), request.batchNo(),
                request.reason(), request.effectiveAt());
    }

    /** 召回范围、受影响申请与换血任务查询 */
    @GetMapping("/{eventNo}")
    public RecallView getRecall(@PathVariable String eventNo) {
        return recallService.getRecall(eventNo);
    }

    /** 全部召回概览 */
    @GetMapping
    public List<RecallView> list() {
        return recallService.listRecalls();
    }

    /** 手动激活已到生效时间的召回（后台另有定时任务），返回本次激活数 */
    @PostMapping("/activate-due")
    public Map<String, Integer> activateDue() {
        return Map.of("activated", recallService.activateDueRecalls());
    }

    /** 为一笔申请执行换血（幂等：重复执行返回已有结果） */
    @PostMapping("/replacements/{requestNo}")
    public ReplacementTaskView replace(@PathVariable String requestNo) {
        return recallService.replaceForRequest(requestNo);
    }

    /** 人工确认无法换血：释放合格单位并终止预留 */
    @PostMapping("/replacements/{requestNo}/unresolvable")
    public ReplacementTaskView confirmUnresolvable(@PathVariable String requestNo) {
        return recallService.confirmUnresolvable(requestNo);
    }
}
