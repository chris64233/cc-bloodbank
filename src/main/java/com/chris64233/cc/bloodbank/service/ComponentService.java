package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.BloodComponent;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ComponentService {

    private final BloodComponentRepository componentRepository;

    public ComponentService(BloodComponentRepository componentRepository) {
        this.componentRepository = componentRepository;
    }

    @Transactional
    public ComponentView register(String barcode, String batchNo, ComponentType componentType, AboGroup aboGroup,
                                  RhFactor rhFactor, java.time.Instant collectedAt, java.time.Instant expiresAt) {
        if (componentRepository.findByBarcode(barcode).isPresent()) {
            throw new RequestConflictException("条码 " + barcode + " 已存在");
        }
        if (batchNo == null || batchNo.isBlank()) {
            throw new BadRequestException("批次号不能为空");
        }
        if (!expiresAt.isAfter(collectedAt)) {
            throw new BadRequestException("失效时间必须晚于采集时间");
        }
        BloodComponent saved = componentRepository.save(
                new BloodComponent(barcode, batchNo, componentType, aboGroup, rhFactor, collectedAt, expiresAt));
        return ComponentView.from(saved);
    }

    @Transactional(readOnly = true)
    public ComponentView getByBarcode(String barcode) {
        return componentRepository.findByBarcode(barcode)
                .map(ComponentView::from)
                .orElseThrow(() -> new NotFoundException("条码 " + barcode + " 不存在"));
    }

    @Transactional(readOnly = true)
    public List<ComponentView> inventory(ComponentStatus status) {
        if (status == null) {
            return componentRepository.findAll().stream()
                    .sorted((a, b) -> a.getBarcode().compareTo(b.getBarcode()))
                    .map(ComponentView::from)
                    .toList();
        }
        return componentRepository.findByStatusOrderByExpiresAtAscBarcodeAsc(status).stream()
                .map(ComponentView::from)
                .toList();
    }
}
