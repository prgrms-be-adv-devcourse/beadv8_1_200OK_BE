package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundFailedStep;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.service.dto.RefundDecision;
import com.ok.refund.service.dto.RefundDecisionCommand;
import com.ok.refund.service.dto.RefundResult;
import com.ok.refund.service.dto.ShipmentReserveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ok.refund.fixture.RefundFixture.BUYER_ID;
import static com.ok.refund.fixture.RefundFixture.NOW;
import static com.ok.refund.fixture.RefundFixture.ORDER_ID;
import static com.ok.refund.fixture.RefundFixture.ORDER_ITEM_ID;
import static com.ok.refund.fixture.RefundFixture.PRODUCT_OPTION_ID;
import static com.ok.refund.fixture.RefundFixture.SELLER_ID;
import static com.ok.refund.fixture.RefundFixture.requested;
import static com.ok.refund.fixture.RefundFixture.socks;
import static com.ok.refund.fixture.RefundFixture.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class RefundFacadeTest {

    private static final Long REFUND_ID = 7L;

    @Mock
    RefundService refundService;
    @Mock
    RefundOrderClient orderClient;
    @Mock
    RefundDeliveryClient deliveryClient;
    @Mock
    RefundProductClient productClient;
    @Mock
    RefundWalletClient walletClient;

    RefundFacade sut;

    @BeforeEach
    void setUp() {
        sut = new RefundFacade(refundService, orderClient, deliveryClient, productClient, walletClient);
    }

    private static RefundResult requestedResult() {
        return RefundResult.from(withId(requested(1), REFUND_ID));
    }

    private static RefundResult processingResult() {
        Refund refund = withId(requested(1), REFUND_ID);
        refund.startProcessing(0L, 0L, NOW);
        return RefundResult.from(refund);
    }

    private static RefundDecisionCommand approve() {
        return new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.APPROVE, null);
    }

    // 승인까지 오는 공통 준비: 요청됨 확인 → 배송 건 있음 → 주문 조회 → 처리 중
    private void givenApprovalStarts() {
        given(refundService.getForDecision(REFUND_ID, SELLER_ID)).willReturn(requestedResult());
        given(deliveryClient.hasShipment(ORDER_ITEM_ID)).willReturn(true);
        given(orderClient.getOrderItem(ORDER_ID, ORDER_ITEM_ID)).willReturn(socks());
        given(refundService.startApproval(REFUND_ID, SELLER_ID, socks())).willReturn(processingResult());
    }

    @Test
    void 승인하면_배송_잠금_지갑_주문_상품_잠금_해제_순서로_부르고_완료한다() {
        // given
        givenApprovalStarts();
        given(deliveryClient.reserveCancel(REFUND_ID, ORDER_ITEM_ID, 1)).willReturn(ShipmentReserveResult.RESERVED);

        // when
        sut.decide(approve());

        // then: 순서 자체가 요구사항이다. 잠금이 맨 처음, 잠금 해제가 맨 마지막이어야 돈과 상품이 함께 나가지 않는다
        InOrder order = inOrder(refundService, deliveryClient, walletClient, orderClient, productClient);
        order.verify(deliveryClient).hasShipment(ORDER_ITEM_ID);
        order.verify(refundService).startApproval(REFUND_ID, SELLER_ID, socks());
        order.verify(deliveryClient).reserveCancel(REFUND_ID, ORDER_ITEM_ID, 1);
        order.verify(walletClient).refund(REFUND_ID, ORDER_ID, BUYER_ID, SELLER_ID, 10_000L);
        order.verify(orderClient).applyRefund(REFUND_ID, ORDER_ITEM_ID, 1);
        order.verify(productClient).releaseStock(REFUND_ID, ORDER_ID, PRODUCT_OPTION_ID, 1);
        order.verify(deliveryClient).confirmCancel(REFUND_ID);
        order.verify(refundService).complete(REFUND_ID);
    }

    @Test
    void 배송_건이_아직_없으면_처리_중으로_바꾸지_않고_409() {
        // given
        given(refundService.getForDecision(REFUND_ID, SELLER_ID)).willReturn(requestedResult());
        given(deliveryClient.hasShipment(ORDER_ITEM_ID)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> sut.decide(approve()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.SHIPMENT_NOT_READY));
        then(refundService).should(never()).startApproval(any(), any(), any());
    }

    @Test
    void 배송_잠금이_거절되면_승인_불가로_끝내고_돈을_돌려주지_않는다() {
        // given
        givenApprovalStarts();
        given(deliveryClient.reserveCancel(REFUND_ID, ORDER_ITEM_ID, 1)).willReturn(ShipmentReserveResult.REJECTED);

        // when
        sut.decide(approve());

        // then
        then(refundService).should().markNotApprovable(REFUND_ID);
        then(walletClient).should(never()).refund(any(), any(), any(), any(), anyLong());
        then(orderClient).should(never()).applyRefund(any(), any(), anyInt());
        then(productClient).should(never()).releaseStock(any(), any(), any(), anyInt());
        then(deliveryClient).should(never()).confirmCancel(any());
    }

    @Test
    void 지갑_호출이_실패하면_실패_단계를_기록하고_뒤_단계는_부르지_않는다() {
        // given
        givenApprovalStarts();
        given(deliveryClient.reserveCancel(REFUND_ID, ORDER_ITEM_ID, 1)).willReturn(ShipmentReserveResult.RESERVED);
        willThrow(new RestApiException(RefundErrorCode.WALLET_API_FAILED))
                .given(walletClient).refund(REFUND_ID, ORDER_ID, BUYER_ID, SELLER_ID, 10_000L);

        // when & then
        assertThatThrownBy(() -> sut.decide(approve()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.WALLET_API_FAILED));
        then(refundService).should().recordFailure(REFUND_ID, RefundFailedStep.WALLET, "WALLET_API_FAILED");
        then(orderClient).should(never()).applyRefund(any(), any(), anyInt());
        then(productClient).should(never()).releaseStock(any(), any(), any(), anyInt());
        then(deliveryClient).should(never()).confirmCancel(any());
        then(refundService).should(never()).complete(any());
    }

    @Test
    void 배송_잠금_호출이_실패하면_배송_잠금_단계를_기록한다() {
        // given
        givenApprovalStarts();
        willThrow(new RestApiException(RefundErrorCode.DELIVERY_API_FAILED))
                .given(deliveryClient).reserveCancel(REFUND_ID, ORDER_ITEM_ID, 1);

        // when & then
        assertThatThrownBy(() -> sut.decide(approve()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.DELIVERY_API_FAILED));
        then(refundService).should().recordFailure(REFUND_ID, RefundFailedStep.SHIPMENT_RESERVE, "DELIVERY_API_FAILED");
        then(walletClient).should(never()).refund(any(), any(), any(), any(), anyLong());
    }

    @Test
    void 실패_기록이_실패해도_원래_예외를_던진다() {
        // given
        givenApprovalStarts();
        given(deliveryClient.reserveCancel(REFUND_ID, ORDER_ITEM_ID, 1)).willReturn(ShipmentReserveResult.RESERVED);
        willThrow(new RestApiException(RefundErrorCode.ORDER_API_FAILED))
                .given(orderClient).applyRefund(REFUND_ID, ORDER_ITEM_ID, 1);
        willThrow(new IllegalStateException("DB 연결 실패"))
                .given(refundService).recordFailure(REFUND_ID, RefundFailedStep.ORDER, "ORDER_API_FAILED");

        // when & then
        assertThatThrownBy(() -> sut.decide(approve()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_API_FAILED));
    }

    @Test
    void 거절이면_다른_모듈을_부르지_않는다() {
        // given
        RefundDecisionCommand reject = new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.REJECT, "이미 포장함");

        // when
        sut.decide(reject);

        // then
        then(refundService).should().reject(REFUND_ID, SELLER_ID, "이미 포장함");
        then(deliveryClient).should(never()).hasShipment(any());
        then(orderClient).should(never()).getOrderItem(any(), any());
    }
}
