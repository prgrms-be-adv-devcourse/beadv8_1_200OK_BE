package com.ok.delivery.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.delivery.controller.dto.ShipmentDispatchRequest;
import com.ok.delivery.controller.dto.ShipmentDispatchResponse;
import com.ok.delivery.service.ShipmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
}
