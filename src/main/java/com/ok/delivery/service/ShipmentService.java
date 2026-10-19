package com.ok.delivery.service;

import com.ok.delivery.domain.Address;
import com.ok.delivery.domain.DeliveryDatePolicy;
import com.ok.delivery.domain.DeliveryDateRange;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentItem;
import com.ok.delivery.repository.ShipmentRepository;
import com.ok.delivery.service.dto.RegionResult;
import com.ok.delivery.service.dto.SellerShipmentCommand;
import com.ok.delivery.service.dto.ShipmentCreateCommand;
import com.ok.delivery.service.dto.ShipmentItemCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final DeliveryRegionService deliveryRegionService;
    private final DeliveryDatePolicy deliveryDatePolicy;
    private final SellerCutoffTimeReader sellerCutoffTimeReader;

    /**
     * 결제 완료된 주문으로 판매자별 shipment를 {@code PREPARING} 상태로 만든다.
     * 배송비 금액은 결제에 쓰인 값을 그대로 저장하고, 지역 그룹과 도착 예정일은 배송지 우편번호로 판정한다.
     * 이미 shipment가 있는 주문이면 만들지 않는다(이벤트 재발행 대비).
     *
     * @throws com.ok.common.exception.RestApiException 배송지 검증 규칙은 {@link Address#of}를 따른다
     * @throws ArithmeticException 금액이 {@code int} 범위를 넘으면
     */
    @Transactional
    public void create(ShipmentCreateCommand command) {
        // 판매자 묶음 전체를 한 트랜잭션에 저장하므로 주문 단위로 확인하면 된다.
        // 동시에 두 번 처리되는 드문 경우는 (order_id, seller_id) 유니크 제약이 막고, 실패한 쪽은 재처리 때 이 확인에서 무시된다
        if (shipmentRepository.existsByOrderId(command.orderId())) {
            log.info("이미 처리된 결제 완료 이벤트, 무시. orderId={}", command.orderId());
            return;
        }
        Address address = command.address().toAddress();
        RegionResult region = deliveryRegionService.resolve(address.getPostalCode());
        List<Shipment> shipments = command.sellers().stream()
                .map(seller -> toShipment(command, address, region, seller))
                .toList();
        shipmentRepository.saveAll(shipments);
    }

    private Shipment toShipment(ShipmentCreateCommand command, Address address, RegionResult region, SellerShipmentCommand seller) {
        int regionSurcharge = Math.toIntExact(seller.regionSurcharge());
        // 결제 금액과 맞추려고 이벤트 값을 저장한다. 다르면 주문 생성 뒤 우편번호 데이터가 바뀐 것이라 확인할 수 있게 남긴다
        if (regionSurcharge != region.surcharge()) {
            log.warn("결제된 추가 운임과 지역 판정 운임이 다름, 결제 값으로 저장. orderId={}, sellerId={}, postalCode={}, paid={}, resolved={}",
                    command.orderId(), seller.sellerId(), address.getPostalCode(), regionSurcharge, region.surcharge());
        }
        LocalDate shipDate = deliveryDatePolicy.shipDate(command.paidAt(), sellerCutoffTimeReader.cutoffTime(seller.sellerId()));
        DeliveryDateRange arrival = deliveryDatePolicy.arrivalRange(shipDate, region.extraDeliveryDays());
        return Shipment.builder()
                .orderId(command.orderId())
                .sellerId(seller.sellerId())
                .address(address)
                .regionGroup(region.regionGroup())
                .baseShippingFee(Math.toIntExact(seller.baseShippingFee()))
                .regionSurcharge(regionSurcharge)
                .freeShippingThreshold(Math.toIntExact(seller.freeShippingThreshold()))
                .estimatedDeliveryStart(arrival.start())
                .estimatedDeliveryEnd(arrival.end())
                .items(seller.items().stream().map(ShipmentService::toItem).toList())
                .build();
    }

    private static ShipmentItem toItem(ShipmentItemCommand item) {
        return ShipmentItem.create(
                item.orderItemId(),
                item.productName(),
                item.optionName(),
                item.quantity(),
                Math.toIntExact(item.unitPrice()));
    }
}
