package com.ok.common.event;

import java.time.LocalDateTime;
import java.util.Objects;

public record SellerApprovedEvent(
        Long memberId,
        LocalDateTime approvedAt
) implements DomainEvent {
    public SellerApprovedEvent {
        Objects.requireNonNull(memberId, "memberId는 필수입니다.");
        Objects.requireNonNull(approvedAt, "approvedAt은 필수입니다.");
    }
}
