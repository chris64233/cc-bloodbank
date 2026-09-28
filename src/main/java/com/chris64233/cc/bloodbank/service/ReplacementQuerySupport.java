package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.ReplacementItem;
import com.chris64233.cc.bloodbank.domain.ReplacementTask;
import com.chris64233.cc.bloodbank.repository.ReplacementItemRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementTaskRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 换血任务查询装配：召回影响查询与申请查询共用，统一"换血前后明细 + 未解决原因"的展示口径。
 */
@Component
public class ReplacementQuerySupport {

    private final ReplacementTaskRepository taskRepository;
    private final ReplacementItemRepository itemRepository;

    public ReplacementQuerySupport(ReplacementTaskRepository taskRepository,
                                   ReplacementItemRepository itemRepository) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public List<ReplacementTaskView> taskViewsForRecall(String eventNo) {
        return taskRepository.findByRecall_EventNoOrderByIdAsc(eventNo).stream()
                .map(this::viewOf)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReplacementTaskView> taskViewsForReservation(Long reservationId) {
        return taskRepository.findByReservation_IdOrderByIdAsc(reservationId).stream()
                .map(this::viewOf)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReplacementTaskView viewOf(ReplacementTask task) {
        List<ReplacementItemView> items = itemRepository.findByTaskIdWithComponents(task.getId()).stream()
                .map(this::itemView)
                .toList();
        return new ReplacementTaskView(task.getId(), task.getRecall().getEventNo(),
                task.getReservation().getRequest().getRequestNo(), task.getStatus(),
                task.getCreatedAt(), task.getCompletedAt(), task.getUnresolvedReason(), items);
    }

    private ReplacementItemView itemView(ReplacementItem i) {
        ReservationItemView removed = ReservationItemView.from(i.getRemovedComponent());
        if (i.getReplacementComponent() == null) {
            return ReplacementItemView.unresolved(removed);
        }
        return ReplacementItemView.replaced(removed, ReservationItemView.from(i.getReplacementComponent()));
    }
}
