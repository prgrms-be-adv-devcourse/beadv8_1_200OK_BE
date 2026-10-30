package com.ok.delivery.adapter.in.web;

import com.ok.delivery.adapter.in.web.dto.RemoteAreaFeeRequest;
import com.ok.delivery.adapter.in.web.dto.RemoteAreaFeeResponse;
import com.ok.delivery.application.service.DeliveryRegionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// order 모듈이 HTTP로 호출하는 내부 API
@RestController
@RequestMapping("/internal/v1/delivery")
@RequiredArgsConstructor
public class DeliveryInternalController implements DeliveryInternalControllerSpec {

    private final DeliveryRegionService deliveryRegionService;

    @Override
    @GetMapping("/remote-area-fee")
    public ResponseEntity<RemoteAreaFeeResponse> getRemoteAreaFee(@Valid @ModelAttribute RemoteAreaFeeRequest request) {
        return ResponseEntity.ok(RemoteAreaFeeResponse.of(
                request.zipCode(), deliveryRegionService.resolve(request.zipCode())));
    }
}
