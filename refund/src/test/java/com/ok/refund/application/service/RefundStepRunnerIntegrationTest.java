package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundFailedStep;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.adapter.out.persistence.RefundJpaRepository;
import com.ok.refund.application.port.in.RefundDecision;
import com.ok.refund.application.port.in.RefundDecisionCommand;
import com.ok.refund.application.port.in.RefundRequestCommand;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeRefundOrderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 기록이 Facade처럼 트랜잭션 밖에서 불려도 따로 커밋되어 DB에 남는지, 새 칸이 엔티티와 맞게 저장되는지 실제 DB로 확인한다
class RefundStepRunnerIntegrationTest extends AbstractIntegrationTest {

    private static final Long BUYER_ID = 1L;
    private static final Long SELLER_ID = 7L;
    private static final Long ORDER_ID = 60L;
    private static final Long ORDER_ITEM_ID = 601L;

    @Autowired
    RefundStepRunner refundStepRunner;
    @Autowired
    RefundFacade refundFacade;
    @Autowired
    RefundService refundService;
    @Autowired
    RefundJpaRepository refundRepository;
    @Autowired
    FakeRefundOrderClient orderClient;
    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        refundRepository.deleteAllInBatch();
        orderClient.clear();
    }

    @Test
    void 실패하면_DB에_처리_중_그대로_단계와_이유가_저장된다() {
        // given
        Long refundId = processingRefundId();

        // when
        assertThatThrownBy(() -> refundStepRunner.run(refundId, RefundFailedStep.ORDER, () -> {
            throw new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        })).isInstanceOfSatisfying(RestApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_API_FAILED));

        // then
        Refund sut = refundRepository.findById(refundId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(sut.getFailedStep()).isEqualTo(RefundFailedStep.ORDER);
            softly.assertThat(sut.getFailureCode()).isEqualTo("ORDER_API_FAILED");
        });
    }

    // 칸 길이가 엔티티보다 짧으면 운영에서 기록이 "Data too long"으로 실패하고, 그 실패는 원래 예외에 붙어 응답에 드러나지 않는다.
    // 그래서 모든 단계 이름과 엔티티가 허용하는 가장 긴 이유(100자)가 실제 칸에 들어가는지 본다
    @ParameterizedTest
    @MethodSource("모든_실패_단계")
    void 모든_단계와_가장_긴_이유가_DB에_저장된다(RefundFailedStep step) {
        // given
        Long refundId = processingRefundId();
        String longestFailureCode = "E".repeat(100);

        // when
        refundService.recordFailure(refundId, step, longestFailureCode);

        // then
        Refund sut = refundRepository.findById(refundId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getFailedStep()).isEqualTo(step);
            softly.assertThat(sut.getFailureCode()).isEqualTo(longestFailureCode);
        });
    }

    static Stream<RefundFailedStep> 모든_실패_단계() {
        return Stream.of(RefundFailedStep.values());
    }

    // 바깥 트랜잭션 안에서 부르면 기록이 거기에 합류해 원래 예외로 롤백될 때 함께 사라진다. 다른 모듈을 부르기 전에 막아야 한다
    @Test
    void 트랜잭션_안에서_부르면_다른_모듈을_부르기_전에_막는다() {
        // given
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        AtomicBoolean called = new AtomicBoolean(false);

        // when & then
        assertThatThrownBy(() -> outer.executeWithoutResult(status ->
                refundStepRunner.run(1L, RefundFailedStep.ORDER, () -> {
                    called.set(true);
                    return null;
                })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("환불 단계 실행은 트랜잭션 밖에서 불러야 합니다. 실패 기록이 바깥 트랜잭션과 함께 롤백됩니다.");
        assertThat(called).isFalse();
    }

    // 요청한 뒤 판매자가 승인해 처리 중이 된 환불. 실패 기록은 처리 중에만 남길 수 있다
    private Long processingRefundId() {
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(ORDER_ITEM_ID, 10_000L, 1)));
        orderClient.put(new RefundableOrderItem(ORDER_ID, ORDER_ITEM_ID, BUYER_ID, SELLER_ID, 77L, "상품", "옵션",
                RefundableOrderItem.CANCELLABLE_STATUS, 10_000L, 1, bundle));
        Long refundId = refundFacade.request(new RefundRequestCommand(BUYER_ID, ORDER_ID, ORDER_ITEM_ID, 1, null)).refundId();
        refundFacade.decide(new RefundDecisionCommand(refundId, SELLER_ID, RefundDecision.APPROVE, null));
        return refundId;
    }
}
