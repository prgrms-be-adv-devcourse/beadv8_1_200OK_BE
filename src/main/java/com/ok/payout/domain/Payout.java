package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.util.ArrayList;
import java.util.List;

import static jakarta.persistence.GenerationType.IDENTITY;

@Entity
@Immutable
@Table(name = "payout")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payout extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    // 수취인 회원 ID (판매자). 시스템 몫이면 회원 수취인이 없으므로 null
    private Long payeeId;

    @Column(nullable = false)
    private Long amount;

    // 시스템(플랫폼) 몫 정산 여부 (판매 수수료면 true)
    @Column(name = "is_system", nullable = false)
    private boolean system;

    @OneToMany(mappedBy = "payout", cascade = CascadeType.PERSIST)
    private List<PayoutItem> items = new ArrayList<>();

    // 판매자 몫 정산 (판매 대금·배송비)
    public static Payout forSeller(Long sellerId, List<PayoutCandidateItem> candidates) {
        return create(sellerId, false, candidates);
    }

    // 시스템 몫 정산 (판매 수수료, 회차 내 전체 판매자분을 1건으로 합침)
    public static Payout forSystem(List<PayoutCandidateItem> candidates) {
        return create(null, true, candidates);
    }

    // 수취인·시스템 여부 조합은 위 두 메서드로만 만들게 해서 잘못된 조합(DB CHECK 위반)을 막음
    private static Payout create(Long payeeId, boolean system, List<PayoutCandidateItem> candidates) {
        Payout payout = new Payout();
        payout.payeeId = payeeId;
        payout.system = system;
        for (PayoutCandidateItem candidate : candidates) {
            payout.items.add(new PayoutItem(payout, candidate));
        }
        payout.amount = payout.items.stream().mapToLong(PayoutItem::getAmount).sum();
        return payout;
    }
}
