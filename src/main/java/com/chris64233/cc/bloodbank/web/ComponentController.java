package com.chris64233.cc.bloodbank.web;

import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.service.ComponentService;
import com.chris64233.cc.bloodbank.service.ComponentView;
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

@RestController
@RequestMapping("/api/components")
public class ComponentController {

    private final ComponentService componentService;

    public ComponentController(ComponentService componentService) {
        this.componentService = componentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentView register(@Valid @RequestBody CreateComponentRequest request) {
        return componentService.register(request.barcode(), request.componentType(), request.aboGroup(),
                request.rhFactor(), request.collectedAt(), request.expiresAt());
    }

    @GetMapping("/{barcode}")
    public ComponentView getByBarcode(@PathVariable String barcode) {
        return componentService.getByBarcode(barcode);
    }

    /** 库存状态查询：可按状态过滤，默认返回全部 */
    @GetMapping
    public List<ComponentView> inventory(@RequestParam(required = false) ComponentStatus status) {
        return componentService.inventory(status);
    }
}
