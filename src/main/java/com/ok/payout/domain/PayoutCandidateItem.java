package com.ok.payout.domain;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

import static jakarta.persistence.GenerationType.IDENTITY;

@Entity
@Immutable
@Table(name = "payout_candidate_items")
@Getter
public class PayoutCandidateItem {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PayoutEventType eventType;

    @Column(nullable = false)
    private Long relId;

    @Column(nullable = false)
    private Long payerId;

    @Column(nullable = false)
    private Long payeeId;

    @Column(nullable = false)
    private Long amount;

    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime createDate;

    public static PayoutCandidateItem create(PayoutEventType eventType, Long relId,
                                             Long payerId, Long payeeId, Long amount) {
        PayoutCandidateItem item = new PayoutCandidateItem();
        item.eventType = eventType;
        item.relId = relId;
        item.payerId = payerId;
        item.payeeId = payeeId;
        item.amount = amount;
        return item;
    }
}
