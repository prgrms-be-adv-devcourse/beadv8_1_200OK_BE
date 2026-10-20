package com.ok.delivery.controller;

import com.ok.delivery.controller.dto.RemoteAreaFeeRequest;
import com.ok.delivery.controller.dto.RemoteAreaFeeResponse;
import com.ok.delivery.service.DeliveryRegionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// order 모듈이 HTTP로 호출하는 내부 API
// TODO(#204): 서비스 간 인증을 적용하고 @Profile을 제거한다. 상품 내부 API와 같이 인증 전까지 운영에는 등록하지 않는다.
@Profile({"dev", "test"})
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
