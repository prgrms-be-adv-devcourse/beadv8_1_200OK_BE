package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundFailedStep;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundDecision;
import com.ok.refund.service.dto.RefundDecisionCommand;
import com.ok.refund.service.dto.RefundRequestCommand;
import com.ok.refund.service.dto.RefundableOrderItem;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeRefundOrderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

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
    RefundRepository refundRepository;
    @Autowired
    FakeRefundOrderClient orderClient;

    @AfterEach
    void tearDown() {
        refundRepository.deleteAllInBatch();
        orderClient.clear();
    }

    @Test
    void 실패하면_DB에_처리_중_그대로_단계와_이유가_저장된다() {
        // given: 승인까지 마쳐 처리 중인 환불
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(ORDER_ITEM_ID, 10_000L, 1)));
        orderClient.put(new RefundableOrderItem(ORDER_ID, ORDER_ITEM_ID, BUYER_ID, SELLER_ID, 77L, "상품", "옵션",
                RefundableOrderItem.CANCELLABLE_STATUS, 10_000L, 1, bundle));
        Long refundId = refundFacade.request(new RefundRequestCommand(BUYER_ID, ORDER_ID, ORDER_ITEM_ID, 1, null)).refundId();
        refundFacade.decide(new RefundDecisionCommand(refundId, SELLER_ID, RefundDecision.APPROVE, null));

        // when
        assertThatThrownBy(() -> refundStepRunner.run(refundId, RefundFailedStep.ORDER, () -> {
            throw new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        })).isInstanceOf(RestApiException.class);

        // then
        Refund sut = refundRepository.findById(refundId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(sut.getFailedStep()).isEqualTo(RefundFailedStep.ORDER);
            softly.assertThat(sut.getFailureCode()).isEqualTo("ORDER_API_FAILED");
        });
    }
}
