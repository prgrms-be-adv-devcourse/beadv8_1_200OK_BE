package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundRequestCommand;
import com.ok.refund.service.dto.RefundResult;
import com.ok.refund.service.dto.RefundableOrderItem;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeRefundOrderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 실제 DB(Testcontainers MySQL)에 저장해 마이그레이션·UNIQUE 제약이 엔티티와 맞는지 확인한다. 주문은 Fake가 대신한다
class RefundFacadeIntegrationTest extends AbstractIntegrationTest {

    private static final Long BUYER_ID = 1L;

    @Autowired
    RefundFacade sut;
    @Autowired
    RefundRepository refundRepository;
    @Autowired
    FakeRefundOrderClient orderClient;

    @AfterEach
    void tearDown() {
        refundRepository.deleteAll();
        orderClient.clear();
    }

    @Test
    void 요청하면_요청됨으로_저장된다() {
        // given
        orderClient.put(socks());

        // when
        RefundResult result = sut.request(new RefundRequestCommand(BUYER_ID, 10L, 101L, 2, "옵션을 잘못 골랐습니다."));

        // then
        Refund saved = refundRepository.findById(result.refundId()).orElseThrow();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            softly.assertThat(saved.getQuantity()).isEqualTo(2);
            softly.assertThat(saved.getItemAmount()).isEqualTo(20_000L);
            softly.assertThat(saved.getActiveOrderItemId()).isEqualTo(101L);
            softly.assertThat(saved.getCreatedAt()).isNotNull();
        });
    }

    @Test
    void 같은_주문_상품에_진행_중인_요청이_있으면_두_번째를_막는다() {
        // given
        orderClient.put(socks());
        sut.request(new RefundRequestCommand(BUYER_ID, 10L, 101L, 1, null));

        // when & then
        assertThatThrownBy(() -> sut.request(new RefundRequestCommand(BUYER_ID, 10L, 101L, 1, null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS));
        assertThat(refundRepository.count()).isEqualTo(1);
    }

    @Test
    void 주문에_없는_상품이면_막는다() {
        // when & then
        assertThatThrownBy(() -> sut.request(new RefundRequestCommand(BUYER_ID, 10L, 101L, 1, null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_ITEM_NOT_FOUND));
    }

    private static RefundableOrderItem socks() {
        return new RefundableOrderItem(10L, 101L, BUYER_ID, 5L, 55L, "양말", "흰색", "PREPARING", 10_000L, 3);
    }
}
