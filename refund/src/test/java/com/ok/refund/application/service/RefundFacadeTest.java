package com.ok.refund.application.service;

import com.ok.refund.application.port.out.OrderQueryPort;
import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.application.port.in.RefundDecision;
import com.ok.refund.application.port.in.RefundDecisionCommand;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.out.RefundableOrderItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// 주문 호출 여부는 설계 요구사항(승인만 주문을 다시 조회하고, 거절·끝난 요청은 주문에 묶이지 않음)이라 호출을 검증한다
@ExtendWith(MockitoExtension.class)
class RefundFacadeTest {

    private static final Long REFUND_ID = 7L;
    private static final Long SELLER_ID = 5L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 10, 0);

    @Mock
    OrderQueryPort orderQueryPort;
    @Mock
    RefundService refundService;
    @InjectMocks
    RefundFacade sut;

    @Test
    void 승인하면_환불의_주문_ID로_주문을_다시_조회해_서비스에_넘긴다() {
        // given
        RefundResult requested = RefundResult.from(requested());
        RefundableOrderItem orderItem = orderItem();
        RefundResult approved = RefundResult.from(approved());
        given(refundService.getSellerRefund(REFUND_ID, SELLER_ID)).willReturn(requested);
        given(orderQueryPort.getOrderItem(10L, 101L)).willReturn(orderItem);
        given(refundService.approve(REFUND_ID, SELLER_ID, orderItem)).willReturn(approved);

        // when
        RefundResult result = sut.decide(new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.APPROVE, null));

        // then
        assertThat(result).isSameAs(approved);
    }

    @Test
    void 거절은_주문을_부르지_않는다() {
        // given
        RefundResult rejected = RefundResult.from(requested());
        given(refundService.reject(REFUND_ID, SELLER_ID, "사유")).willReturn(rejected);

        // when
        RefundResult result = sut.decide(new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.REJECT, "사유"));

        // then
        assertThat(result).isSameAs(rejected);
        then(orderQueryPort).should(never()).getOrderItem(any(), any());
    }

    @Test
    void 이미_끝난_요청을_승인하면_주문을_부르지_않고_막는다() {
        // given
        given(refundService.getSellerRefund(REFUND_ID, SELLER_ID)).willReturn(RefundResult.from(approved()));

        // when & then
        assertThatThrownBy(() -> sut.decide(new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.APPROVE, null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
        then(orderQueryPort).should(never()).getOrderItem(any(), any());
    }

    @Test
    void 남의_요청을_승인하면_주문을_부르지_않고_막는다() {
        // given
        given(refundService.getSellerRefund(REFUND_ID, SELLER_ID))
                .willThrow(new RestApiException(RefundErrorCode.REFUND_NOT_FOUND));

        // when & then
        assertThatThrownBy(() -> sut.decide(new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.APPROVE, null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
        then(orderQueryPort).should(never()).getOrderItem(any(), any());
    }

    @Test
    void 승인_때_주문_호출이_실패하면_승인하지_않는다() {
        // given
        given(refundService.getSellerRefund(REFUND_ID, SELLER_ID)).willReturn(RefundResult.from(requested()));
        given(orderQueryPort.getOrderItem(10L, 101L)).willThrow(new RestApiException(RefundErrorCode.ORDER_API_FAILED));

        // when & then
        assertThatThrownBy(() -> sut.decide(new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.APPROVE, null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_API_FAILED));
        then(refundService).should(never()).approve(any(), any(), any());
    }

    private static Refund requested() {
        return Refund.request(10L, 101L, 1, 1L, SELLER_ID, 55L, "양말", "흰색", 10_000L, null);
    }

    private static Refund approved() {
        Refund refund = requested();
        refund.approve(NOW, RefundShippingFee.none());
        return refund;
    }

    private static RefundableOrderItem orderItem() {
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L, List.of(new BundleItem(101L, 10_000L, 3)));
        return new RefundableOrderItem(10L, 101L, 1L, SELLER_ID, 55L, "양말", "흰색", "PREPARING", 10_000L, 3, bundle);
    }
}
