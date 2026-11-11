package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.RefundRequestCommand;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.out.OrderQueryPort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.CancelledQuantity;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundShippingFeePolicy;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class RequestRefundServiceTest {

    private static final Long BUYER_ID = 1L;
    private static final Long OTHER_BUYER_ID = 999L;
    private static final Long SELLER_ID = 5L;

    @Mock
    OrderQueryPort orderQueryPort;
    @Mock
    RefundPersistencePort refundPersistencePort;

    RequestRefundService sut;

    @BeforeEach
    void setUp() {
        sut = new RequestRefundService(orderQueryPort, refundPersistencePort, new NoOpTransactionManager());
    }

    @Test
    void 요청하면_주문에서_받은_값으로_요청됨을_저장한다() {
        // given
        givenOrderItem(socks("PREPARING", 3));
        given(refundPersistencePort.saveNew(any(Refund.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        sut.request(command(2));

        // then
        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().saveNew(captor.capture());
        Refund saved = captor.getValue();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getQuantity()).isEqualTo(2);
            softly.assertThat(saved.getSellerId()).isEqualTo(5L);
            softly.assertThat(saved.getProductOptionId()).isEqualTo(55L);
            softly.assertThat(saved.getUnitPrice()).isEqualTo(10_000L);
            softly.assertThat(saved.getItemAmount()).isEqualTo(20_000L);
            softly.assertThat(saved.getCancelReason()).isEqualTo("옵션을 잘못 골랐습니다.");
        });
    }

    @Test
    void 남의_주문이면_없는_주문처럼_막는다() {
        // given
        givenOrderItem(socks("PREPARING", 3));
        RefundRequestCommand otherBuyer = new RefundRequestCommand(OTHER_BUYER_ID, 10L, 101L, 1, null);

        // when & then
        assertThatThrownBy(() -> sut.request(otherBuyer))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_ITEM_NOT_FOUND));
        then(refundPersistencePort).should(never()).saveNew(any());
    }

    @Test
    void 출고_전이_아니면_막는다() {
        // given
        givenOrderItem(socks("SHIPPING", 3));

        // when & then
        assertThatThrownBy(() -> sut.request(command(1)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_ITEM_NOT_CANCELLABLE));
        then(refundPersistencePort).should(never()).saveNew(any());
    }

    @Test
    void 산_개수보다_많으면_막는다() {
        // given
        givenOrderItem(socks("PREPARING", 3));

        // when & then
        assertThatThrownBy(() -> sut.request(command(4)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_QUANTITY_EXCEEDED));
        then(refundPersistencePort).should(never()).saveNew(any());
    }

    @Test
    void 산_개수만큼은_요청할_수_있다() {
        // given
        givenOrderItem(socks("PREPARING", 3));
        given(refundPersistencePort.saveNew(any(Refund.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        RefundResult result = sut.request(command(3));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.quantity()).isEqualTo(3);
            softly.assertThat(result.itemAmount()).isEqualTo(30_000L);
            softly.assertThat(result.status()).isEqualTo("REQUESTED");
        });
    }

    // 완료(#414)되면 진행 중 칸이 비어 같은 상품에 다시 요청할 수 있다. 그때는 산 개수에서 이미 취소된 개수를 뺀 만큼만 받는다.
    // 막지 않으면 배송비 정책이 "취소 개수 합이 주문 개수를 넘는다"를 버그로 보고 500을 던진다
    @Test
    void 이미_취소된_개수를_빼고_남은_개수보다_많으면_막는다() {
        // given: 양말 3개 중 1개는 이미 환불(완료)됐다
        givenOrderItem(socks("PREPARING", 3));
        given(refundPersistencePort.saveNew(any(Refund.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(refundPersistencePort.sumCancelledByOrderItem(10L, 5L, RefundShippingFeePolicy.CANCELLING_STATUSES))
                .willReturn(List.of(new CancelledQuantity(101L, 1, 0)));

        // when & then: 남은 2개보다 많은 3개
        assertThatThrownBy(() -> sut.request(command(3)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_QUANTITY_EXCEEDED));
    }

    @Test
    void 이미_취소된_개수를_빼고_남은_개수만큼은_요청할_수_있다() {
        // given: 양말 3개 중 1개는 이미 환불(완료)됐다
        givenOrderItem(socks("PREPARING", 3));
        given(refundPersistencePort.saveNew(any(Refund.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(refundPersistencePort.sumCancelledByOrderItem(10L, 5L, RefundShippingFeePolicy.CANCELLING_STATUSES))
                .willReturn(List.of(new CancelledQuantity(101L, 1, 0)));

        // when
        RefundResult result = sut.request(command(2));

        // then
        assertThat(result.quantity()).isEqualTo(2);
    }

    // 진행 중 중복(UNIQUE)·교착 상태를 에러 코드로 바꾸는 일은 영속 어댑터로 옮겼다(RefundPersistenceAdapterTest).
    // 서비스는 포트가 던진 에러를 그대로 돌려줘야 한다
    @Test
    void 저장소가_진행_중_에러를_던지면_그대로_돌려준다() {
        // given
        givenOrderItem(socks("PREPARING", 3));
        given(refundPersistencePort.saveNew(any(Refund.class)))
                .willThrow(new RestApiException(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS));

        // when & then
        assertThatThrownBy(() -> sut.request(command(1)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS));
    }

    // 무료배송(50,000원 이상) 묶음에서 1,000원짜리만 취소하면 49,500원이 남아 기준이 깨지고, 뺄 배송비 3,000원이 취소 금액보다 크다
    @Test
    void 요청_때_배송비가_모자라면_409로_막는다() {
        // given
        givenOrderItem(cheapItemInFreeBundle());
        given(refundPersistencePort.saveNew(any(Refund.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when & then
        assertThatThrownBy(() -> sut.request(new RefundRequestCommand(BUYER_ID, 10L, 101L, 1, null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.PARTIAL_CANCEL_NOT_ALLOWED));
    }

    // 서비스가 주문을 직접 조회하므로, 명령의 주문 ID·주문 상품 ID(10, 101)로 조회했을 때 돌려줄 주문 상품을 정한다
    private void givenOrderItem(RefundableOrderItem orderItem) {
        given(orderQueryPort.getOrderItem(10L, 101L)).willReturn(orderItem);
    }

    private static RefundRequestCommand command(int quantity) {
        return new RefundRequestCommand(BUYER_ID, 10L, 101L, quantity, "옵션을 잘못 골랐습니다.");
    }

    private static RefundableOrderItem socks(String status, int boughtQuantity) {
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(101L, 10_000L, boughtQuantity)));
        return new RefundableOrderItem(10L, 101L, BUYER_ID, 5L, 55L, "양말", "흰색", status, 10_000L, boughtQuantity, bundle);
    }

    private static RefundableOrderItem cheapItemInFreeBundle() {
        BundleShipping bundle = new BundleShipping(0, 0, 3_000L, 50_000L,
                List.of(new BundleItem(101L, 1_000L, 1), new BundleItem(102L, 49_500L, 1)));
        return new RefundableOrderItem(10L, 101L, BUYER_ID, SELLER_ID, 55L, "양말", "흰색", "PREPARING", 1_000L, 1, bundle);
    }
}
