package com.ok.delivery.adapter.in.event.dto;

import com.ok.delivery.application.dto.AddressCommand;
import com.ok.delivery.application.dto.SellerShipmentCommand;
import com.ok.delivery.application.dto.ShipmentCreateCommand;
import com.ok.delivery.application.dto.ShipmentItemCommand;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 주문이 Kafka로 보내는 결제 완료 메시지를 배송이 읽는 모양. 필드 이름·타입은 주문과의 약속이다.
 * 주문 클래스나 {@code common.event}를 쓰지 않고 같은 JSON 모양의 배송 DTO로 읽는다
 * 빠진 필드를 0으로 읽어 버리지 않도록 숫자는 감싼 타입으로 받고, 값 검사는 {@link #toCommand()}가 서비스 입력으로 바꾸며 한다.
 */
public record OrderPaidMessage(
        Long orderId,
        Long buyerId,
        LocalDateTime paidAt,
        ShippingAddress shippingAddress,
        List<SellerOrder> sellerOrders
) {

    public record ShippingAddress(
            String recipientName,
            String recipientPhone,
            String postalCode,
            String address,
            String addressDetail,
            String deliveryNote
    ) {
    }

    public record SellerOrder(
            Long sellerId,
            Long baseShippingFee,
            Long freeShippingThreshold,
            Long regionSurcharge,
            List<Item> items
    ) {
    }

    public record Item(
            Long orderItemId,
            String productName,
            String optionName,
            Integer quantity,
            Long unitPrice
    ) {
    }

    /**
     * 값을 검사하고 shipment 생성 입력으로 바꾼다.
     * 다시 받아도 같은 값이라 결과가 같으므로, 틀린 값은 표준 예외로 알려 오류 처리기가 다시 시도 없이 DLT로 보내게 한다 (exception.md).
     *
     * @throws IllegalArgumentException 필수 값이 비었거나, 금액·수량이 범위를 벗어났거나, 판매자·주문 상품 ID가 겹치면
     */
    public ShipmentCreateCommand toCommand() {
        require(orderId != null, "orderId는 필수입니다.");
        require(buyerId != null, "buyerId는 필수입니다.");
        require(paidAt != null, "paidAt은 필수입니다.");
        require(shippingAddress != null, "shippingAddress는 필수입니다.");
        require(sellerOrders != null && !sellerOrders.isEmpty(), "sellerOrders는 1개 이상이어야 합니다.");
        Set<Long> sellerIds = new HashSet<>();
        Set<Long> orderItemIds = new HashSet<>();
        List<SellerShipmentCommand> sellers = sellerOrders.stream()
                .map(seller -> toSeller(seller, sellerIds, orderItemIds))
                .toList();
        return new ShipmentCreateCommand(orderId, buyerId, paidAt, toAddress(shippingAddress), sellers);
    }

    private AddressCommand toAddress(ShippingAddress address) {
        requireText(address.recipientName(), "recipientName");
        requireText(address.recipientPhone(), "recipientPhone");
        requireText(address.postalCode(), "postalCode");
        requireText(address.address(), "address");
        requireText(address.addressDetail(), "addressDetail");
        return new AddressCommand(
                address.recipientName(),
                address.recipientPhone(),
                address.postalCode(),
                address.address(),
                address.addressDetail(),
                address.deliveryNote());
    }

    private SellerShipmentCommand toSeller(SellerOrder seller, Set<Long> sellerIds, Set<Long> orderItemIds) {
        require(seller != null && seller.sellerId() != null, "sellerId는 필수입니다.");
        require(sellerIds.add(seller.sellerId()), "sellerId가 중복되었습니다: " + seller.sellerId());
        requireNotNegative(seller.baseShippingFee(), "baseShippingFee");

        if (seller.freeShippingThreshold() != null) {
            requireNotNegative(seller.freeShippingThreshold(), "freeShippingThreshold");
        }
        requireNotNegative(seller.regionSurcharge(), "regionSurcharge");
        require(seller.items() != null && !seller.items().isEmpty(), "items는 1개 이상이어야 합니다. sellerId=" + seller.sellerId());
        List<ShipmentItemCommand> items = seller.items().stream()
                .map(item -> toItem(item, orderItemIds))
                .toList();
        return new SellerShipmentCommand(
                seller.sellerId(),
                seller.baseShippingFee(),
                seller.freeShippingThreshold(),
                seller.regionSurcharge(),
                items);
    }

    private ShipmentItemCommand toItem(Item item, Set<Long> orderItemIds) {
        require(item != null && item.orderItemId() != null, "orderItemId는 필수입니다.");
        require(orderItemIds.add(item.orderItemId()), "orderItemId가 중복되었습니다: " + item.orderItemId());
        requireText(item.productName(), "productName");
        require(item.quantity() != null && item.quantity() >= 1, "quantity는 1 이상이어야 합니다. orderItemId=" + item.orderItemId());
        requireNotNegative(item.unitPrice(), "unitPrice");
        return new ShipmentItemCommand(
                item.orderItemId(),
                item.productName(),
                item.optionName(),
                item.quantity(),
                item.unitPrice());
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message + " orderId=" + orderId);
        }
    }

    private void requireText(String value, String name) {
        require(value != null && !value.isBlank(), name + "는 필수입니다.");
    }

    private void requireNotNegative(Long amount, String name) {
        require(amount != null && amount >= 0, name + "는 0 이상이어야 합니다.");
    }
}
