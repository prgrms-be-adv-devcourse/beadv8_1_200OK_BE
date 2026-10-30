package com.ok.order.order.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.cart.service.CartService;
import com.ok.order.cart.service.dto.SelectedCartItem;
import com.ok.order.order.client.DeliveryApiClient;
import com.ok.order.order.client.ProductApiClient;
import com.ok.order.order.client.WalletApiClient;
import com.ok.order.order.client.dto.ProductOptionInfoResponse;
import com.ok.order.order.client.dto.SellerShippingFeeResponse;
import com.ok.order.order.client.dto.StockAllocationItemRequest;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItem;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.SellerShippingFee;
import com.ok.order.order.exception.OrderErrorCode;
import com.ok.order.order.service.dto.CheckoutResult;
import com.ok.order.order.service.dto.CreateOrderCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderFacade {

    private final CartService cartService;
    private final OrderService orderService;
    private final ProductApiClient productApiClient;
    private final DeliveryApiClient deliveryApiClient;
    private final WalletApiClient walletApiClient;

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "uk_orders_buyer_id_idempotency_key";

    // 주문서 조회: 배송지를 고르기 전이라 우편번호 없이 계산한다 (도서산간 운임은 주문 생성 때 더해진다)
    public CheckoutResult getCheckout(Long memberId) {
        return CheckoutResult.from(buildOrder(memberId, null));
    }

    // 주문 생성
    public Long createOrder(CreateOrderCommand command) {
        validateIdempotencyKey(command.idempotencyKey());

        // 같은 키로 이미 만든 주문이 있으면(중복 클릭·재시도) 외부 API를 다시 부르지 않고 그 주문을 돌려준다
        Optional<Long> existingOrderId = orderService.findIdByIdempotencyKey(command.memberId(), command.idempotencyKey());
        if (existingOrderId.isPresent()) {
            log.info("같은 멱등키로 이미 생성된 주문, 기존 주문 반환. orderId={}", existingOrderId.get());
            return existingOrderId.get();
        }

        // 장바구니 + 최신 상품 정보 + 배송지 기준 배송비·도서산간 운임으로 주문 만들기
        Order order = buildOrder(command.memberId(), command.zipCode());
        order.assignIdempotencyKey(command.idempotencyKey());
        order.registerShippingAddress(command.recipientName(), command.recipientPhone(), command.zipCode(),
                command.address(), command.addressDetail(), command.deliveryNote());
        order.agreeThirdPartyProvision(command.thirdPartyProvisionAgreed());

        // 사용자가 본 금액과 다르면 중단
        if (order.getTotalAmount() != command.expectedTotalAmount()) {
            throw new RestApiException(OrderErrorCode.ORDER_AMOUNT_CHANGED);
        }

        // 잔액 확인
        long balance = walletApiClient.getBalance(command.memberId()).balance();
        if (balance < order.getTotalAmount()) {
            throw new RestApiException(OrderErrorCode.INSUFFICIENT_BALANCE);
        }

        // 주문 저장
        Long orderId;
        try {
            orderId = orderService.create(order);
        } catch (DataIntegrityViolationException e) {
            // 멱등키가 아닌 제약 위반은 주문을 만드는 우리 코드의 버그라 그대로 던져 500으로 드러낸다
            if (!isIdempotencyKeyViolation(e)) {
                throw e;
            }
            // 같은 키의 요청이 거의 동시에 와서 위 조회를 둘 다 통과한 경우, 유니크 제약이 나중 요청을 막는다.
            // 재고 선점과 이벤트 발행은 먼저 저장한 요청이 하므로 여기서는 그 주문만 돌려준다.
            // 먼저 저장된 주문이 재고 선점 실패로 그 사이 삭제됐으면 키가 풀린 상태라 다시 시도하도록 알린다
            Long savedOrderId = orderService.findIdByIdempotencyKey(command.memberId(), command.idempotencyKey())
                    .orElseThrow(() -> new RestApiException(OrderErrorCode.IDEMPOTENCY_KEY_CONFLICT));
            log.info("같은 멱등키의 동시 요청, 먼저 저장된 주문 반환. orderId={}", savedOrderId);
            return savedOrderId;
        }
        log.info("주문 생성 완료, orderId={}", orderId);

        // 재고 선점 + 이벤트 발행 (실패하면 되돌리기)
        try {
            productApiClient.allocateStock(orderId, toStockItems(order));
            orderService.publishCreated(orderId);
        } catch (RuntimeException e) {
            cancelCreatedOrder(orderId);
            throw e;
        }

        return orderId;
    }

    // 헤더 값이라 DTO Bean Validation을 거치지 않으므로 여기서 확인 (컬럼 길이 64)
    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new RestApiException(OrderErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }

    // 제약 이름은 DB 오류 메시지에만 담겨 있어 메시지로 구분한다 (ShipmentService, MemberDuplicateValidator와 같은 방식)
    private boolean isIdempotencyKeyViolation(DataIntegrityViolationException e) {
        return String.valueOf(e.getMostSpecificCause().getMessage()).contains(IDEMPOTENCY_KEY_CONSTRAINT);
    }

    // 장바구니 선택 상품 + 상품 모듈의 최신 정보로 주문 객체 만들기 (저장 X)
    // zipCode: 배송지 우편번호, 주문서 조회처럼 아직 모르면 null
    private Order buildOrder(Long memberId, String zipCode) {
        List<SelectedCartItem> cartItems = cartService.getSelectedCartItems(memberId);
        if (cartItems.isEmpty()) {
            throw new RestApiException(OrderErrorCode.EMPTY_ORDER_ITEMS);
        }

        // 각 상품별 옵션 ID 추출
        List<Long> optionIds = new ArrayList<>();
        for (SelectedCartItem item : cartItems) {
            optionIds.add(item.productOptionId());
        }

        // 상품 옵션 ID로 상품 모듈에서 최신 정보 조회
        Map<Long, ProductOptionInfoResponse> optionMap = new HashMap<>();
        for (ProductOptionInfoResponse option : productApiClient.getOptions(optionIds)) {
            optionMap.put(option.optionId(), option);
        }

        List<OrderLine> lines = new ArrayList<>();
        for (SelectedCartItem item : cartItems) {
            lines.add(toOrderLine(memberId, item, optionMap.get(item.productOptionId())));
        }

        Order order = Order.create(memberId, lines);
        applyShippingFees(order, zipCode);
        return order;
    }

    // 판매자 묶음마다 배송비·도서산간 운임 반영
    // 판매자 배송 설정과 지역 데이터는 배송 모듈이 가지므로 계산도 배송 모듈에 맡긴다 (주문서와 결제 금액이 같은 규칙을 쓰게)
    // 판매자가 여럿이어도 한 번에 묻는다
    private void applyShippingFees(Order order, String zipCode) {
        Map<Long, SellerShippingFeeResponse> feeBySeller = new HashMap<>();
        for (SellerShippingFeeResponse fee : deliveryApiClient.getShippingFees(zipCode, order.getProductAmountsBySeller())) {
            feeBySeller.put(fee.sellerId(), fee);
        }

        for (Long sellerId : order.getProductAmountsBySeller().keySet()) {
            SellerShippingFeeResponse fee = feeBySeller.get(sellerId);
            // 응답에 빠진 판매자를 그냥 두면 배송비 0원으로 결제되므로 주문을 막는다
            if (fee == null) {
                throw new RestApiException(OrderErrorCode.DELIVERY_API_FAILED);
            }
            order.applyShippingFee(sellerId, new SellerShippingFee(
                    fee.baseShippingFee(), fee.freeShippingThreshold(), fee.shippingFee(), fee.regionSurcharge()));
        }
    }

    // 장바구니 한 줄 + 상품 정보 -> 주문서 한 줄 + 구매 가능 여부 검사
    private OrderLine toOrderLine(Long memberId, SelectedCartItem item, ProductOptionInfoResponse option) {
        if (option == null) throw new RestApiException(OrderErrorCode.PRODUCT_OPTION_NOT_FOUND);
        if (!option.purchasable()) throw new RestApiException(OrderErrorCode.PRODUCT_NOT_ON_SALE);
        if (option.availableStock() < item.quantity()) throw new RestApiException(OrderErrorCode.OUT_OF_STOCK);
        if (option.sellerId().equals(memberId)) throw new RestApiException(OrderErrorCode.CANNOT_ORDER_OWN_PRODUCT);

        return new OrderLine(
                option.productId(),
                option.optionId(),
                option.sellerId(),
                option.productName(),
                option.optionName(),
                option.salePrice(),
                item.quantity()
        );
    }

    // 주문 상품 -> 재고 선점 요청 항목 추출
    private List<StockAllocationItemRequest> toStockItems(Order order) {
        List<StockAllocationItemRequest> items = new ArrayList<>();
        for (OrderItem orderItem : order.getAllItems()) {
            items.add(new StockAllocationItemRequest(orderItem.getProductOptionId(), orderItem.getQuantity()));
        }
        return items;
    }

    // 재고 선점 해제 + 주문 삭제
    private void cancelCreatedOrder(Long orderId) {
        // 재고 선점 해제
        try {
            productApiClient.releaseStock(orderId);
        } catch(RuntimeException e) {
            log.error("재고 선점 해제 실패, orderId={}", orderId, e);
        }
        // 주문 삭제
        try {
            orderService.delete(orderId);
        } catch(RuntimeException e) {
            log.error("주문 삭제 실패, orderId={}", orderId, e);
        }
    }

    // 결제 실패 : 주문 상태 변경 후 선점한 재고 해제 (장바구니는 유지)
    public void failPayment(Long orderId, String reason) {
        log.info("[ORDER_PAYMENT_FAILED] orderId={}, reason={}", orderId, reason);

        boolean shouldReleaseStock = orderService.failPayment(orderId);
        if (shouldReleaseStock) {
            productApiClient.releaseStock(orderId);
        }
    }
}
