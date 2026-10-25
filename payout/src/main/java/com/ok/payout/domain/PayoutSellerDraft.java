package com.ok.payout.domain;

import com.ok.payout.PayoutSchema;
import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.util.ArrayList;
import java.util.List;

import static jakarta.persistence.GenerationType.IDENTITY;

// 판매자 1명의 임시 정산 (판매 대금 + 배송비 합계). 검증을 통과하면 commitStep에서 payout으로 확정
// 시스템 몫(수수료)은 여러 판매자분을 합치므로 여기 두지 않고 PayoutSystemDraftItem에 항목만 저장
@Entity
@Immutable
@Table(name = "payout_seller_draft", catalog = PayoutSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutSellerDraft extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long snapshotId;

    @Column(nullable = false)
    private Long sellerId;

    // 판매자 몫 합계 (원)
    @Column(nullable = false)
    private Long amount;

    @OneToMany(mappedBy = "sellerDraft", cascade = CascadeType.PERSIST)
    private List<PayoutSellerDraftItem> items = new ArrayList<>();

    // 판매자 1명의 판매자 몫 정산후보로 임시 정산을 만든다 (합계는 항목 금액의 합)
    public static PayoutSellerDraft create(Long snapshotId, Long sellerId, List<PayoutCandidateItem> candidates) {
        PayoutSellerDraft draft = new PayoutSellerDraft();
        draft.snapshotId = snapshotId;
        draft.sellerId = sellerId;
        for (PayoutCandidateItem candidate : candidates) {
            draft.items.add(new PayoutSellerDraftItem(draft, candidate));
        }
        draft.amount = draft.items.stream().mapToLong(PayoutSellerDraftItem::getAmount).sum();
        return draft;
    }
}
