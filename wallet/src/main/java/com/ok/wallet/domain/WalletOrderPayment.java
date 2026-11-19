package com.ok.wallet.domain;

import com.ok.wallet.WalletSchema;
import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지갑이 처리한 주문 결제 요청의 결과. 주문 하나에 한 건만 남고 바뀌지 않는다.
 * 주문 상태는 주문 모듈이 관리하고, 이 기록은 같은 결제 요청이 재전달되어도 예치금을 다시 차감하지 않기 위한 지갑의 처리 기록이다.
 * 잔액이 실제로 바뀐 것만 남는 원장으로는 실패를 기억할 수 없어 따로 둔다.
 * 처리 시각은 생성 시각(createdAt)으로 본다.
 * 기록은 {@code WalletOrderPaymentRepository.insertIfAbsent}로만 넣는다. 엔티티로 저장하면 같은 주문이 동시에 올 때 일반 조회로 걸러야 해서 중복을 놓친다(#360).
 */
@Entity
@Table(name = "wallet_order_payment", catalog = WalletSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletOrderPayment extends BaseTime {
    public static final int MAX_ORDER_SUMMARY_LENGTH = 120;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long orderId;

    @Column(nullable = false)
    private Long buyerId;

    @Column(nullable = false)
    private long amount;

    // 이용 내역에 보일 주문 요약(예: "사과 외 3건"). 이 칸이 생기기 전 기록과 만료로 먼저 남긴 기록에는 없다
    @Column(length = MAX_ORDER_SUMMARY_LENGTH)
    private String orderSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletOrderPaymentStatus status;

    public boolean isPaid() {
        return status == WalletOrderPaymentStatus.PAID;
    }

    /**
     * 주문이 보낸 요약을 저장할 값으로 바꾼다. 비었으면 null, 칸 길이를 넘으면 앞에서부터 칸 길이만큼 자른다.
     * 요약은 보여 주기에만 쓰므로 이상한 값이어도 결제를 막지 않는다. 넣기가 IGNORE라 긴 값도 DB가 잘라 넣지만,
     * 경고로만 남는 그 동작에 기대지 않고 저장할 값을 여기서 정한다.
     * 길이는 DB처럼 글자 수로 센다. 자바 문자 수로 자르면 이모지 같은 글자가 반으로 잘린다.
     */
    public static String normalizeOrderSummary(String orderSummary) {
        if (orderSummary == null || orderSummary.isBlank()) {
            return null;
        }
        String stripped = orderSummary.strip();
        if (stripped.codePointCount(0, stripped.length()) <= MAX_ORDER_SUMMARY_LENGTH) {
            return stripped;
        }
        return stripped.substring(0, stripped.offsetByCodePoints(0, MAX_ORDER_SUMMARY_LENGTH));
    }
}
