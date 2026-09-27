package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.api.dto.RegisterUnitRequest;
import com.chris64233.cc.bloodbank.api.error.BusinessConflictException;
import com.chris64233.cc.bloodbank.api.error.ResourceNotFoundException;
import com.chris64233.cc.bloodbank.domain.BloodUnit;
import com.chris64233.cc.bloodbank.domain.UnitStatus;
import com.chris64233.cc.bloodbank.repo.BloodUnitRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class BloodUnitService {

    private final BloodUnitRepository unitRepository;

    public BloodUnitService(BloodUnitRepository unitRepository) {
        this.unitRepository = unitRepository;
    }

    @Transactional
    public BloodUnit register(RegisterUnitRequest request) {
        if (!request.expiresAt().isAfter(request.collectedAt())) {
            throw new IllegalArgumentException("失效时间必须晚于采集时间");
        }
        if (unitRepository.existsById(request.barcode())) {
            throw new BusinessConflictException("BARCODE_EXISTS", "条码已存在: " + request.barcode());
        }
        BloodUnit unit = new BloodUnit(
                request.barcode(),
                request.componentType(),
                request.bloodType(),
                request.collectedAt(),
                request.expiresAt());
        try {
            unitRepository.saveAndFlush(unit);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessConflictException("BARCODE_EXISTS", "条码已存在: " + request.barcode());
        }
        return unit;
    }

    @Transactional(readOnly = true)
    public List<BloodUnit> list(Optional<UnitStatus> status) {
        return status
                .map(unitRepository::findByStatusOrderByExpiresAtAscBarcodeAsc)
                .orElseGet(unitRepository::findAllByOrderByBarcodeAsc);
    }

    @Transactional(readOnly = true)
    public BloodUnit get(String barcode) {
        return unitRepository.findById(barcode)
                .orElseThrow(() -> new ResourceNotFoundException("血液单位不存在: " + barcode));
    }
}
