package com.chris64233.cc.bloodbank.repository;

import com.chris64233.cc.bloodbank.domain.ReplacementItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReplacementItemRepository extends JpaRepository<ReplacementItem, Long> {

    @Query("""
            SELECT i FROM ReplacementItem i
            JOIN FETCH i.removedComponent rc
            LEFT JOIN FETCH i.replacementComponent
            WHERE i.task.id = :taskId
            ORDER BY i.id ASC
            """)
    List<ReplacementItem> findByTaskIdWithComponents(@Param("taskId") Long taskId);
}
