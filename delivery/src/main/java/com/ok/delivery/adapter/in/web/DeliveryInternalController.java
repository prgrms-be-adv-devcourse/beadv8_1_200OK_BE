package com.ok.delivery.adapter.in.web;

import com.ok.delivery.adapter.in.web.dto.ShippingFeeQuoteRequest;
import com.ok.delivery.adapter.in.web.dto.ShippingFeesResponse;
import com.ok.delivery.application.port.in.ShippingFeesUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// order 모듈이 HTTP로 호출하는 내부 API
@RestController
@RequestMapping("/internal/v1/delivery")
@RequiredArgsConstructor
public class DeliveryInternalController implements DeliveryInternalControllerSpec {

    private final ShippingFeesUseCase shippingFeesUseCase;

    @Override
    // 요청 형식과 검증이 견적 API와 같아 요청 DTO를 함께 쓴다. 규칙이 갈라지면 그때 나눈다
    @PostMapping("/shipping-fees")
    public ResponseEntity<ShippingFeesResponse> getShippingFees(@Valid @RequestBody ShippingFeeQuoteRequest request) {
        return ResponseEntity.ok(ShippingFeesResponse.from(shippingFeesUseCase.calculate(request.toQuery())));
    }
}
