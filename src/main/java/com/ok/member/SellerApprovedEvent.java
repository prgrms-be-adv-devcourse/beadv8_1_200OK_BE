package com.ok.member;

import java.time.LocalDateTime;

public record SellerApprovedEvent (
        Long memberId,
        LocalDateTime approvedAt
) {
}
