package com.ok.payout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

// 초과 청구 근거 8개 (#391 실패 상세 글과 같은 값, #570부터 값별로 나눔)
// 칸은 9개: "이미 지급"을 금액(settledAmount)과 주문상품(settledItems) 두 칸으로 나눔
// 금액 검사가 실패 상세 글을 만들 때와 관리자 목록의 "막힌 순간"·"지금" 근거가 이 값 하나로 만들어져 서로 어긋나지 않음
// 막힌 주문 집계 표(PayoutBlockedOrder)에 칸으로 들어감. 칸이 모두 NULL이면 JPA가 이 값 자체를 null로 읽음(근거 없음)
// 목록 값은 쉼표로 이은 글로 저장함: 화면에 보여 주기만 하고 DB에서 목록 안을 찾을 일이 없어 표를 따로 두지 않음
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutOverchargeEvidence {

    private static final String DELIMITER = ",";

    // 결제(지갑 차감) 금액
    @Column(name = "evidence_deducted_amount")
    private Long deductedAmount;

    // 정산후보 합계 (조정 제외 #552, 스냅샷 밖 대기 중 후보 포함)
    @Column(name = "evidence_candidate_total")
    private Long candidateTotal;

    // 후보 합계 - 결제 금액. "지금" 값은 초과가 풀렸으면 0이나 음수일 수 있음
    @Column(name = "evidence_overflow_amount")
    private Long overflowAmount;

    // 넘게 만든 주문상품: 주문상품 ID, 배송비면 "배송비", 누적합이 결제 금액을 넘지 않으면 "판별 불가"
    @Column(name = "evidence_first_overflow_item", length = 30)
    private String firstOverflowItem;

    // 이미 지급(payout_item 있음)한 후보 금액 합
    @Column(name = "evidence_settled_amount")
    private Long settledAmount;

    // 이미 지급한 주문상품 ID들 (쉼표로 이음, 배송비는 "배송비")
    @Column(name = "evidence_settled_items", columnDefinition = "TEXT")
    private String settledItems;

    // 한 주문상품 합계만으로 결제 금액보다 큰 주문상품 (배송비 제외)
    @Column(name = "evidence_alone_over_items", columnDefinition = "TEXT")
    private String aloneOverItems;

    // 그 주문 정산후보의 서로 다른 판매자 수 (여럿이면 어느 판매자 금액이 틀렸는지 따로 봐야 함)
    @Column(name = "evidence_seller_count")
    private Integer sellerCount;

    // 차감 기록과 다른 금액으로 온 지갑 이벤트의 받은 금액들
    @Column(name = "evidence_conflict_amounts", columnDefinition = "TEXT")
    private String conflictAmounts;

    public static PayoutOverchargeEvidence of(long deductedAmount, long candidateTotal, String firstOverflowItem,
                                              long settledAmount, List<String> settledItems,
                                              List<String> aloneOverItems, int sellerCount,
                                              List<Long> conflictAmounts) {
        if (firstOverflowItem == null || settledItems == null || aloneOverItems == null || conflictAmounts == null) {
            throw new IllegalArgumentException("초과 청구 근거의 주문상품·목록 값은 null일 수 없습니다.");
        }
        PayoutOverchargeEvidence evidence = new PayoutOverchargeEvidence();
        evidence.deductedAmount = deductedAmount;
        evidence.candidateTotal = candidateTotal;
        evidence.overflowAmount = candidateTotal - deductedAmount;
        evidence.firstOverflowItem = firstOverflowItem;
        evidence.settledAmount = settledAmount;
        evidence.settledItems = String.join(DELIMITER, settledItems);
        evidence.aloneOverItems = String.join(DELIMITER, aloneOverItems);
        evidence.sellerCount = sellerCount;
        List<String> amounts = new ArrayList<>();
        for (Long amount : conflictAmounts) {
            amounts.add(String.valueOf(amount));
        }
        evidence.conflictAmounts = String.join(DELIMITER, amounts);
        return evidence;
    }

    public List<String> settledItemList() {
        return split(settledItems);
    }

    public List<String> aloneOverItemList() {
        return split(aloneOverItems);
    }

    public List<Long> conflictAmountList() {
        List<Long> amounts = new ArrayList<>();
        for (String amount : split(conflictAmounts)) {
            amounts.add(Long.valueOf(amount));
        }
        return amounts;
    }

    // 빈 글은 빈 목록 ("".split은 [""]를 돌려줘 따로 봄)
    private static List<String> split(String joined) {
        List<String> values = new ArrayList<>();
        if (joined == null || joined.isEmpty()) {
            return values;
        }
        for (String value : joined.split(DELIMITER)) {
            values.add(value);
        }
        return values;
    }
}
