package com.ok.delivery.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.delivery.controller.dto.AddressRequest;
import com.ok.delivery.controller.dto.AddressResponse;
import com.ok.delivery.service.DefaultShippingAddressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 기본 배송지는 주문서 초기값이고 주문은 일반 회원만 하므로 BUYER 권한으로 거른다
@RestController
@RequestMapping("/api/v1/shipments/addresses/default")
@PreAuthorize("hasRole('BUYER')")
@RequiredArgsConstructor
public class DefaultShippingAddressController implements DefaultShippingAddressControllerSpec {

    private final DefaultShippingAddressService defaultShippingAddressService;

    @Override
    @GetMapping
    public ResponseEntity<AddressResponse> getDefaultAddress(@CurrentMemberId Long memberId) {
        return defaultShippingAddressService.getDefaultAddress(memberId)
                .map(result -> ResponseEntity.ok(AddressResponse.from(result)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Override
    @PutMapping
    public ResponseEntity<AddressResponse> saveDefaultAddress(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody AddressRequest request
    ) {
        return ResponseEntity.ok(AddressResponse.from(
                defaultShippingAddressService.saveDefaultAddress(memberId, request.toCommand())));
    }
}
