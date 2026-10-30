package com.ok.delivery.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.delivery.adapter.in.web.dto.ShipmentDetailResponse;
import com.ok.delivery.adapter.in.web.dto.ShipmentPageRequest;
import com.ok.delivery.adapter.in.web.dto.ShipmentPageResponse;
import com.ok.delivery.application.service.ShipmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 주문은 일반 회원만 하므로 배송 조회도 BUYER 권한으로 거른다. 본인 shipment인지는 서비스가 구매자 ID로 확인한다
@RestController
@RequestMapping("/api/v1/shipments")
@PreAuthorize("hasRole('BUYER')")
@RequiredArgsConstructor
public class ShipmentController implements ShipmentControllerSpec {

    private final ShipmentService shipmentService;

    @Override
    @GetMapping
    public ResponseEntity<ShipmentPageResponse> getMyShipments(
            @CurrentMemberId Long buyerId,
            @Valid @ModelAttribute ShipmentPageRequest request
    ) {
        return ResponseEntity.ok(ShipmentPageResponse.from(shipmentService.getBuyerShipments(buyerId, request.page())));
    }

    @Override
    @GetMapping("/{shipmentId}")
    public ResponseEntity<ShipmentDetailResponse> getMyShipment(
            @CurrentMemberId Long buyerId,
            @PathVariable Long shipmentId
    ) {
        return ResponseEntity.ok(ShipmentDetailResponse.from(shipmentService.getBuyerShipment(buyerId, shipmentId)));
    }
}
