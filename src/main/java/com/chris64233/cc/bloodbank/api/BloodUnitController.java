package com.chris64233.cc.bloodbank.api;

import com.chris64233.cc.bloodbank.api.dto.RegisterUnitRequest;
import com.chris64233.cc.bloodbank.api.dto.UnitView;
import com.chris64233.cc.bloodbank.domain.UnitStatus;
import com.chris64233.cc.bloodbank.service.BloodUnitService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * 血液成分单位登记与库存状态查询。
 */
@RestController
@RequestMapping("/api/units")
public class BloodUnitController {

    private final BloodUnitService unitService;

    public BloodUnitController(BloodUnitService unitService) {
        this.unitService = unitService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UnitView register(@Valid @RequestBody RegisterUnitRequest request) {
        return UnitView.from(unitService.register(request));
    }

    /** 库存状态查询，可按状态过滤：AVAILABLE / RESERVED / ISSUED */
    @GetMapping
    public List<UnitView> list(@RequestParam(name = "status", required = false) UnitStatus status) {
        return unitService.list(Optional.ofNullable(status)).stream().map(UnitView::from).toList();
    }

    @GetMapping("/{barcode}")
    public UnitView get(@PathVariable String barcode) {
        return UnitView.from(unitService.get(barcode));
    }
}
