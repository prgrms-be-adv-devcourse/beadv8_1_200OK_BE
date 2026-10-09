package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.util.ArrayList;
import java.util.List;

@Entity
@Immutable
@Table(name = "payout")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payout extends BaseIdAndTime {

    @Column(nullable = false)
    private Long payeeId;

    @Column(nullable = false)
    private Long amount;

    @OneToMany(mappedBy = "payout", cascade = CascadeType.PERSIST)
    private List<PayoutItem> items = new ArrayList<>();

    public static Payout create(Long payeeId, List<PayoutCandidateItem> candidates) {
        Payout payout = new Payout();
        payout.payeeId = payeeId;
        for (PayoutCandidateItem candidate : candidates) {
            payout.items.add(new PayoutItem(payout, candidate));
        }
        payout.amount = payout.items.stream().mapToLong(PayoutItem::getAmount).sum();
        return payout;
    }
}
