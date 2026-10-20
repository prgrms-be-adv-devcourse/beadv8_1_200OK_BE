package com.ok.delivery.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.delivery.controller.dto.AddressChangeDecisionRequest;
import com.ok.delivery.controller.dto.AddressChangeRequestResponse;
import com.ok.delivery.controller.dto.ShipmentDispatchRequest;
import com.ok.delivery.controller.dto.ShipmentDispatchResponse;
import com.ok.delivery.service.ShipmentAddressChangeService;
import com.ok.delivery.service.ShipmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller/shipments")
@PreAuthorize("hasRole('SELLER')")
@RequiredArgsConstructor
public class SellerShipmentController implements SellerShipmentControllerSpec {

    private final ShipmentService shipmentService;
    private final ShipmentAddressChangeService shipmentAddressChangeService;

    @Override
    @PostMapping("/{shipmentId}/dispatch")
    public ResponseEntity<ShipmentDispatchResponse> dispatch(
            @CurrentMemberId Long sellerId,
            @PathVariable Long shipmentId,
            @Valid @RequestBody ShipmentDispatchRequest request
    ) {
        return ResponseEntity.ok(ShipmentDispatchResponse.from(
                shipmentService.dispatch(request.toCommand(shipmentId, sellerId))));
    }

    @Override
    @PatchMapping("/{shipmentId}/address-change-requests/{requestId}")
    public ResponseEntity<AddressChangeRequestResponse> decideAddressChange(
            @CurrentMemberId Long sellerId,
            @PathVariable Long shipmentId,
            @PathVariable Long requestId,
            @Valid @RequestBody AddressChangeDecisionRequest request
    ) {
        return ResponseEntity.ok(AddressChangeRequestResponse.from(
                shipmentAddressChangeService.decide(request.toCommand(shipmentId, requestId, sellerId))));
    }
}
