package com.ok.payout.application.service;

import com.ok.payout.application.port.out.PayoutOutboxRepository;
import com.ok.payout.domain.PayoutOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// outbox 한 줄의 발송 결과를 저장 (#309)
// 줄마다 짧은 별도 트랜잭션(REQUIRES_NEW)으로 바로 커밋: 뒤 줄이 실패해도 앞 줄의 "보냄"이 롤백되지 않게
// PayoutService와 빈을 나눈 이유는 PayoutSellerDraftService와 같음 (같은 클래스 안 호출은 @Transactional이 무시됨)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayoutOutboxService {

    private final PayoutOutboxRepository payoutOutboxRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(Long outboxId) {
        get(outboxId).markSent(LocalDateTime.now(clock));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long outboxId, String error) {
        get(outboxId).recordFailure(error);
    }

    private PayoutOutbox get(Long outboxId) {
        return payoutOutboxRepository.findById(outboxId)
                .orElseThrow(() -> new IllegalStateException("정산 outbox 줄이 없습니다. outboxId=" + outboxId));
    }
}
