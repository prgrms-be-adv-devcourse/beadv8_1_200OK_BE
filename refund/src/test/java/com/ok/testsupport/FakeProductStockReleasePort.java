package com.ok.testsupport;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.exception.RefundErrorCode;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 상품 모듈 재고 일부 해제 API(#363) 대신 되돌린 값을 메모리에 남기는 Fake.
 * 통합 테스트는 웹 서버 없이(MOCK) 돌아 HTTP로 부를 수 없어 대신 쓴다.
 * 상품 API처럼 같은 refundId로 다시 오면 또 풀지 않는다(처음 값을 그대로 둔다).
 * {@code AbstractIntegrationTest}의 {@code @ComponentScan}이 주워 가고 {@code @Primary}로 실제 어댑터를 대신한다.
 * 테스트마다 {@link #clear}로 비운다. 동시 요청 테스트에서도 쓰도록 동시성 컬렉션을 쓴다.
 */
@Component
@Primary
public class FakeProductStockReleasePort implements ProductStockReleasePort {

    private final Map<Long, StockRelease> released = new ConcurrentHashMap<>();
    // 호출 횟수. 실제 상품 API처럼 두 번째부터는 다시 풀지 않는 것과 별개로, "서비스가 두 번 불렀는지"를 확인하려고 센다
    private final Map<Long, AtomicInteger> callCounts = new ConcurrentHashMap<>();
    private volatile boolean failing;
    // 앞으로 실패할 호출 수. 다시 시도 간격(Kafka 오류 처리기)과 상관없이 시도 횟수를 정확히 맞추려고 둔다
    private final AtomicInteger remainingFailures = new AtomicInteger();

    /** 이 환불로 재고 복구를 부른 횟수(실패한 호출은 세지 않는다). 같은 완료 메시지가 두 번 와도 한 번만 불러야 하는 것을 확인할 때 쓴다 */
    public int releaseCallCountOf(Long refundId) {
        AtomicInteger count = callCounts.get(refundId);
        return count == null ? 0 : count.get();
    }

    /** 실제 어댑터가 상품 서버 꺼짐·타임아웃·오류 응답을 바꾸는 것처럼, 이후 호출이 {@code PRODUCT_API_FAILED}를 던지게 한다 */
    public void fail() {
        failing = true;
    }

    /** 다음 {@code count}번의 호출만 {@code PRODUCT_API_FAILED}를 던지고 그 뒤 호출은 성공하게 한다 */
    public void failNextCalls(int count) {
        remainingFailures.set(count);
    }

    public void clear() {
        released.clear();
        callCounts.clear();
        failing = false;
        remainingFailures.set(0);
    }

    /** 이 환불로 되돌린 재고. 되돌리지 않았으면 비어 있다 */
    public Optional<StockRelease> releasedOf(Long refundId) {
        return Optional.ofNullable(released.get(refundId));
    }

    @Override
    public void releaseStock(StockRelease release) {
        if (failing || remainingFailures.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
            throw new RestApiException(RefundErrorCode.PRODUCT_API_FAILED);
        }
        callCounts.computeIfAbsent(release.refundId(), id -> new AtomicInteger()).incrementAndGet();
        released.putIfAbsent(release.refundId(), release);
    }
}
