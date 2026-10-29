package com.ok.testsupport;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.service.RefundWalletClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 지갑 모듈 내부 API 대신 구매자 환불 요청을 기록하는 Fake.
 * {@link #failNext}로 다음 호출을 503으로 실패시켜, 승인 뒤 중간 실패를 통합 테스트로 확인한다.
 * 테스트마다 {@link #clear}로 비운다. 동시 요청 테스트가 있어 동시성 컬렉션을 쓴다.
 */
@Component
@Primary
public class FakeRefundWalletClient implements RefundWalletClient {

    private final List<Refunded> refunded = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failNext = new AtomicBoolean(false);

    public List<Refunded> refunded() {
        return List.copyOf(refunded);
    }

    /** 돌려준 금액 합. 나눠서 취소한 합이 결제 금액과 같은지 확인할 때 쓴다 */
    public long totalAmount() {
        long total = 0L;
        for (Refunded each : refunded) {
            total += each.amount();
        }
        return total;
    }

    public void failNext() {
        failNext.set(true);
    }

    public void clear() {
        refunded.clear();
        failNext.set(false);
    }

    @Override
    public void refund(Long refundId, Long orderId, Long buyerId, Long sellerId, long amount) {
        if (failNext.compareAndSet(true, false)) {
            throw new RestApiException(RefundErrorCode.WALLET_API_FAILED);
        }
        refunded.add(new Refunded(refundId, orderId, buyerId, amount));
    }

    public record Refunded(Long refundId, Long orderId, Long buyerId, long amount) {
    }
}
