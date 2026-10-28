package com.ok.order.order.domain;

import com.ok.order.OrderSchema;
import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.order.order.exception.OrderErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Entity
@Table(name = "orders", catalog = OrderSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 구매자 ID
    @Column(nullable = false)
    private Long buyerId;

    // 주문 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    // 수령인 이름
    @Column(nullable = false, length = 50)
    private String recipientName;

    // 수령인 연락처, 하이픈 포함 형식(000-0000-0000)
    @Column(nullable = false, length = 20)
    private String recipientPhone;

    // 우편번호 (숫자 5자리)
    @Column(nullable = false, length = 5)
    private String zipCode;

    // 기본 주소(도로명 또는 지번)
    @Column(nullable = false, length = 200)
    private String address;

    // 상세 주소(동, 호수 등)
    @Column(length = 100)
    private String addressDetail;

    // 상품 금액 합계
    @Column(nullable = false)
    private long totalProductAmount;

    // 배송비 합계
    @Column(nullable = false)
    private long totalShippingFee;

    // 도서산간 추가 운임 합계
    @Column(nullable = false)
    private long totalRemoteAreaFee;

    // 최종 결제 금액
    @Column(nullable = false)
    private long totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderSellerGroup> sellerGroups = new  ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderThirdPartyConsent> thirdPartyConsents = new  ArrayList<>();

    // 주문서 한 줄(OrderLine)으로 주문 생성
    public static Order create(Long buyerId, List<OrderLine> lines) {
        // 주문할 상품이 없으면 주문 생성 불가
        if (lines.isEmpty()) {
            throw new RestApiException(OrderErrorCode.EMPTY_ORDER_ITEMS);
        }

        // 빈 주문 만들기 -> 처음 상태는 결제 대기
        Order order = new Order();
        order.buyerId = buyerId;
        order.status = OrderStatus.PAYMENT_PENDING;

        // 판매자별로 나누기
        Map<Long, List<OrderLine>> linesBySeller = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            Long sellerId = line.sellerId();

            // 만약 처음 나온 판매자면 빈 목록 생성
            if (!linesBySeller.containsKey(sellerId)) {
                linesBySeller.put(sellerId, new ArrayList<>());
            }

            // 해당 판매자 목록에 상품 추가
            linesBySeller.get(sellerId).add(line);
        }

        // 판매자별 주문 그룹 생성
        for (Long sellerId : linesBySeller.keySet()) {
            List<OrderLine> sellerLines = linesBySeller.get(sellerId);

            OrderSellerGroup group = OrderSellerGroup.create(order, sellerId, sellerLines);

            order.sellerGroups.add(group);
        }

        // 금액 합계 계산
        order.calculateAmounts();

        return order;
    }

    // 배송지 등록
    public void registerShippingAddress(
            String recipientName,
            String recipientPhone,
            String zipCode,
            String address,
            String addressDetail
    ) {
        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.zipCode = zipCode;
        this.address = address;
        this.addressDetail = addressDetail;
    }

    // 판매자에게 주문 정보 제공 동의
    public void agreeThirdPartyProvision(boolean agreed) {
        // 동의하지 않으면 주문 불가
        if (!agreed) {
            throw new RestApiException(OrderErrorCode.THIRD_PARTY_CONSENT_REQUIRED);
        }

        for (OrderSellerGroup group : sellerGroups) {
            OrderThirdPartyConsent consent = OrderThirdPartyConsent.create(this, group.getSellerId());
            thirdPartyConsents.add(consent);
        }
    }

    private void calculateAmounts() {
        long productAmount = 0;
        long shippingFee = 0;
        long remoteAreaFee = 0;

        for (OrderSellerGroup group : sellerGroups) {
            productAmount += group.productAmount();
            shippingFee += group.getShippingFee();
            remoteAreaFee += group.getRemoteAreaFee();
        }

        this.totalProductAmount = productAmount;
        this.totalShippingFee = shippingFee;
        this.totalRemoteAreaFee = remoteAreaFee;
        this.totalAmount = productAmount + shippingFee + remoteAreaFee;
    }

    // 도서산간 추가 운임 적용
    public void applyRemoteAreaFee(long remoteAreaFeePerShipment) {
        for (OrderSellerGroup group : sellerGroups) {
            group.applyRemoteAreaFee(remoteAreaFeePerShipment);
        }
        calculateAmounts();
    }

    // 모든 묶음의 주문 상품을 한 목록으로 모으기
    public List<OrderItem> getAllItems() {
        List<OrderItem> allItems = new ArrayList<>();
        for (OrderSellerGroup group : sellerGroups) {
            allItems.addAll(group.getItems());
        }
        return allItems;
    }

    // 결제 대기 상태인지
    public boolean isPaymentPending() {
        return status == OrderStatus.PAYMENT_PENDING;
    }

    // 결제 실패 상태인지
    public boolean isPaymentFailed() {
        return status == OrderStatus.PAYMENT_FAILED;
    }

    // 결제 완료
    public void markPaid() {
        this.status = OrderStatus.PAID;
        for (OrderItem item : getAllItems()) {
            item.markPreparing();
        }
    }

    // 결제 실패 -> 주문과 상품 모두 결제 실패
    public void markPaymentFailed() {
        this.status = OrderStatus.PAYMENT_FAILED;
        for (OrderItem item : getAllItems()) {
            item.markPaymentFailed();
        }
    }

    // 판매자 묶음의 상품들을 '배송 중'으로 변경
    public int markShipping(Long sellerId) {
        for (OrderSellerGroup group : sellerGroups) {
            if (group.getSellerId().equals(sellerId)) {
                return group.markShipping();
            }
        }
        return 0;
    }

    // 판매자 묶음의 상품들을 '배송 완료'로 변경
    public int markDelivered(Long sellerId, LocalDateTime deliveredAt) {
        for (OrderSellerGroup group : sellerGroups) {
            if (group.getSellerId().equals(sellerId)) {
                return group.markDelivered(deliveredAt);
            }
        }
        return 0;
    }

    // 주문 안에서 주문 상품 하나 찾기
    public Optional<OrderItem> findItem(Long orderItemId) {
        for (OrderItem item : getAllItems()) {
            if (orderItemId.equals(item.getId())) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }


    // 자동 구매확정: 기준 시각 이전에 배송 완료된 상품을 확정하고, 확정된 상품을 돌려줌
    public List<OrderItem> confirmPurchasesDeliveredBefore(LocalDateTime deadline, LocalDateTime now) {
        List<OrderItem> confirmed = new ArrayList<>();
        for (OrderItem item : getAllItems()) {
            if (item.isDeliveredBefore(deadline) && item.confirmPurchase(now)) {
                confirmed.add(item);
            }
        }
        return confirmed;
    }
}