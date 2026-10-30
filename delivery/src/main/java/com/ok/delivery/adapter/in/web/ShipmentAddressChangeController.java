package com.ok.delivery.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.delivery.adapter.in.web.dto.AddressChangeRequestResponse;
import com.ok.delivery.adapter.in.web.dto.AddressRequest;
import com.ok.delivery.application.dto.AddressChangeRequestCommand;
import com.ok.delivery.application.service.ShipmentAddressChangeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 주문은 일반 회원만 하므로 배송지 변경 요청도 BUYER 권한으로 거른다. 본인 shipment인지는 서비스가 구매자 ID로 확인한다
@RestController
@RequestMapping("/api/v1/shipments/{shipmentId}/address-change-requests")
@PreAuthorize("hasRole('BUYER')")
@RequiredArgsConstructor
public class ShipmentAddressChangeController implements ShipmentAddressChangeControllerSpec {

    private final ShipmentAddressChangeService shipmentAddressChangeService;

    @Override
    @PostMapping
    public ResponseEntity<AddressChangeRequestResponse> request(
            @CurrentMemberId Long buyerId,
            @PathVariable Long shipmentId,
            @Valid @RequestBody AddressRequest request
    ) {
        AddressChangeRequestCommand command = new AddressChangeRequestCommand(shipmentId, buyerId, request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AddressChangeRequestResponse.from(shipmentAddressChangeService.request(command)));
    }
}
