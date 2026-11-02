package com.ok.delivery.adapter.in.web;

import com.ok.delivery.adapter.in.web.dto.ShippingFeeQuoteRequest;
import com.ok.delivery.adapter.in.web.dto.ShippingFeeQuoteResponse;
import com.ok.delivery.application.port.in.ShippingFeeQuoteUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 견적은 저장하지 않는 계산이지만, 판매자 목록을 본문으로 받아야 해서 POST로 받는다
@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
public class ShipmentFeeQuoteController implements ShipmentFeeQuoteControllerSpec {

    private final ShippingFeeQuoteUseCase shippingFeeQuoteUseCase;

    @Override
    @PostMapping("/fee-quotes")
    public ResponseEntity<ShippingFeeQuoteResponse> quote(@Valid @RequestBody ShippingFeeQuoteRequest request) {
        return ResponseEntity.ok(ShippingFeeQuoteResponse.from(shippingFeeQuoteUseCase.quote(request.toQuery())));
    }
}
