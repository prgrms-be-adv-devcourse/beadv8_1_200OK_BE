package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.CancelledQuantity;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundRequestCommand;
import com.ok.refund.service.dto.RefundResult;
import com.ok.refund.service.dto.RefundableOrderItem;
import com.ok.refund.service.dto.SellerGroupItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.ok.refund.fixture.RefundFixture.BUYER_ID;
import static com.ok.refund.fixture.RefundFixture.HAT_ORDER_ITEM_ID;
import static com.ok.refund.fixture.RefundFixture.NOW;
import static com.ok.refund.fixture.RefundFixture.ORDER_ID;
import static com.ok.refund.fixture.RefundFixture.ORDER_ITEM_ID;
import static com.ok.refund.fixture.RefundFixture.SELLER_ID;
import static com.ok.refund.fixture.RefundFixture.hat;
import static com.ok.refund.fixture.RefundFixture.requested;
import static com.ok.refund.fixture.RefundFixture.requestedHat;
import static com.ok.refund.fixture.RefundFixture.socks;
import static com.ok.refund.fixture.RefundFixture.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final Long REFUND_ID = 7L;
    private static final Long OTHER_MEMBER_ID = 999L;

    @Mock
    RefundRepository refundRepository;

    RefundService sut;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));
        sut = new RefundService(refundRepository, clock);
        // 단위 테스트에는 스프링 트랜잭션이 없으므로, 승인이 확인하는 격리 수준을 실제 트랜잭션처럼 지정해 둔다
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
    }

    @Test
    void 승인이_READ_COMMITTED가_아닌_트랜잭션에서_불리면_바로_실패한다() {
        // given: 기본 격리 수준 트랜잭션에 합류한 경우. 스프링은 기본 격리 수준이면 값을 null로 둔다
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);

        // when & then
        assertThatThrownBy(() -> sut.startApproval(REFUND_ID, SELLER_ID, socks()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("환불 승인은 READ COMMITTED 트랜잭션에서만 실행해야 합니다.");
        then(refundRepository).should(never()).findIdsByOrderIdAndSellerId(any(), any());
    }

    // 요청

    @Test
    void 남의_주문_상품이면_없는_것으로_응답한다() {
        // given
        RefundRequestCommand command = new RefundRequestCommand(OTHER_MEMBER_ID, ORDER_ID, ORDER_ITEM_ID, 1);

        // when & then
        assertThatThrownBy(() -> sut.request(command, socks()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_ITEM_NOT_FOUND));
        then(refundRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 출고_전이_아니면_요청할_수_없다() {
        // given
        RefundRequestCommand command = new RefundRequestCommand(BUYER_ID, ORDER_ID, ORDER_ITEM_ID, 1);

        // when & then
        assertThatThrownBy(() -> sut.request(command, socks("SHIPPING")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_ITEM_NOT_CANCELLABLE));
    }

    @Test
    void 완료된_개수를_빼고_남은_개수보다_많으면_요청할_수_없다() {
        // given: 양말 3개 중 2개 완료, 이번에 2개
        RefundRequestCommand command = new RefundRequestCommand(BUYER_ID, ORDER_ID, ORDER_ITEM_ID, 2);
        given(refundRepository.sumQuantityByOrderItemIdAndStatus(ORDER_ITEM_ID, RefundStatus.COMPLETED)).willReturn(2L);

        // when & then
        assertThatThrownBy(() -> sut.request(command, socks()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_QUANTITY_EXCEEDED));
    }

    @Test
    void 남은_개수만큼은_요청할_수_있고_주문에서_받은_값으로_저장한다() {
        // given: 양말 3개 중 2개 완료, 이번에 1개
        RefundRequestCommand command = new RefundRequestCommand(BUYER_ID, ORDER_ID, ORDER_ITEM_ID, 1);
        given(refundRepository.sumQuantityByOrderItemIdAndStatus(ORDER_ITEM_ID, RefundStatus.COMPLETED)).willReturn(2L);
        given(refundRepository.saveAndFlush(any(Refund.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        RefundResult result = sut.request(command, socks());

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("REQUESTED");
            softly.assertThat(result.sellerId()).isEqualTo(SELLER_ID);
            softly.assertThat(result.productOptionId()).isEqualTo(55L);
            softly.assertThat(result.productName()).isEqualTo("양말");
            softly.assertThat(result.itemAmount()).isEqualTo(10_000L);
            softly.assertThat(result.shippingFeeAmount()).isZero();
        });
    }

    @Test
    void 진행_중인_요청이_있어_유니크_제약에_걸리면_409() {
        // given
        RefundRequestCommand command = new RefundRequestCommand(BUYER_ID, ORDER_ID, ORDER_ITEM_ID, 1);
        given(refundRepository.saveAndFlush(any(Refund.class)))
                .willThrow(new DataIntegrityViolationException("Duplicate entry for key 'refund.uk_refund_active_order_item_id'"));

        // when & then
        assertThatThrownBy(() -> sut.request(command, socks()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS));
    }

    @Test
    void 다른_제약_위반은_그대로_던진다() {
        // given
        RefundRequestCommand command = new RefundRequestCommand(BUYER_ID, ORDER_ID, ORDER_ITEM_ID, 1);
        given(refundRepository.saveAndFlush(any(Refund.class)))
                .willThrow(new DataIntegrityViolationException("Check constraint 'ck_refund_quantity' is violated."));

        // when & then
        assertThatThrownBy(() -> sut.request(command, socks()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("Check constraint 'ck_refund_quantity' is violated.");
    }

    // 철회·거절

    @Test
    void 남의_요청은_철회할_수_없다() {
        // given
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(withId(requested(1), REFUND_ID)));

        // when & then
        assertThatThrownBy(() -> sut.withdraw(REFUND_ID, OTHER_MEMBER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
    }

    @Test
    void 다른_판매자의_요청은_거절할_수_없다() {
        // given
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(withId(requested(1), REFUND_ID)));

        // when & then
        assertThatThrownBy(() -> sut.reject(REFUND_ID, OTHER_MEMBER_ID, "사유"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
    }

    // 승인

    @Test
    void 승인_전_확인에서_요청됨이_아니면_409() {
        // given
        Refund withdrawn = withId(requested(1), REFUND_ID);
        withdrawn.withdraw(NOW);
        given(refundRepository.findById(REFUND_ID)).willReturn(Optional.of(withdrawn));

        // when & then
        assertThatThrownBy(() -> sut.getForDecision(REFUND_ID, SELLER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
    }

    @Test
    void 승인_전_확인에서_다른_판매자의_요청이면_404() {
        // given
        given(refundRepository.findById(REFUND_ID)).willReturn(Optional.of(withId(requested(1), REFUND_ID)));

        // when & then
        assertThatThrownBy(() -> sut.getForDecision(REFUND_ID, OTHER_MEMBER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
    }

    @Test
    void 묶음을_전부_취소하는_승인이면_배송비와_추가_운임을_저장한다() {
        // given: 양말 3개 처리 중·완료, 이번에 모자 1개
        Refund target = withId(requestedHat(), REFUND_ID);
        given(refundRepository.findIdsByOrderIdAndSellerId(ORDER_ID, SELLER_ID)).willReturn(List.of(REFUND_ID));
        given(refundRepository.findAllByIdInForUpdate(List.of(REFUND_ID))).willReturn(List.of(target));
        given(refundRepository.sumQuantityByOrderItem(ORDER_ID, SELLER_ID, List.of(RefundStatus.PROCESSING, RefundStatus.COMPLETED)))
                .willReturn(List.of(new CancelledQuantity(ORDER_ITEM_ID, 3L)));

        // when
        RefundResult result = sut.startApproval(REFUND_ID, SELLER_ID, hat());

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("PROCESSING");
            softly.assertThat(result.shippingFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(result.remoteAreaFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(result.refundAmount()).isEqualTo(5_000L + 6_000L);
        });
    }

    @Test
    void 일부만_취소하는_승인이면_배송비를_저장하지_않는다() {
        // given
        Refund target = withId(requested(1), REFUND_ID);
        given(refundRepository.findIdsByOrderIdAndSellerId(ORDER_ID, SELLER_ID)).willReturn(List.of(REFUND_ID));
        given(refundRepository.findAllByIdInForUpdate(List.of(REFUND_ID))).willReturn(List.of(target));
        given(refundRepository.sumQuantityByOrderItem(any(), any(), anyCollection())).willReturn(List.of());

        // when
        RefundResult result = sut.startApproval(REFUND_ID, SELLER_ID, socks());

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("PROCESSING");
            softly.assertThat(result.shippingFeeAmount()).isZero();
            softly.assertThat(result.remoteAreaFeeAmount()).isZero();
            softly.assertThat(result.refundAmount()).isEqualTo(10_000L);
        });
    }

    @Test
    void 주문_응답의_묶음_수량과_상품_수량이_다르면_승인하지_않고_요청됨으로_둔다() {
        // given: 양말 상품 수량은 3인데 묶음 목록에는 2로 온 주문 응답(주문 모듈 응답 버그)
        Refund target = withId(requested(1), REFUND_ID);
        RefundableOrderItem socks = socks();
        RefundableOrderItem mismatched = new RefundableOrderItem(socks.orderId(), socks.orderItemId(), socks.buyerId(),
                socks.sellerId(), socks.productOptionId(), socks.productName(), socks.optionName(), socks.status(),
                socks.unitPrice(), socks.quantity(), socks.shippingFee(), socks.remoteAreaFee(),
                List.of(new SellerGroupItem(ORDER_ITEM_ID, 2), new SellerGroupItem(HAT_ORDER_ITEM_ID, 1)));
        given(refundRepository.findIdsByOrderIdAndSellerId(ORDER_ID, SELLER_ID)).willReturn(List.of(REFUND_ID));
        given(refundRepository.findAllByIdInForUpdate(List.of(REFUND_ID))).willReturn(List.of(target));

        // when & then: 어느 수량을 믿을지 몰라 배송비를 판단하지 않는다
        assertThatThrownBy(() -> sut.startApproval(REFUND_ID, SELLER_ID, mismatched))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("주문 응답의 묶음 수량과 상품 수량이 다릅니다. orderItemId=" + ORDER_ITEM_ID);
        assertThat(target.getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 잠그기_전에_철회됐으면_승인할_수_없다() {
        // given
        Refund target = withId(requested(1), REFUND_ID);
        target.withdraw(NOW);
        given(refundRepository.findIdsByOrderIdAndSellerId(ORDER_ID, SELLER_ID)).willReturn(List.of(REFUND_ID));
        given(refundRepository.findAllByIdInForUpdate(List.of(REFUND_ID))).willReturn(List.of(target));

        // when & then
        assertThatThrownBy(() -> sut.startApproval(REFUND_ID, SELLER_ID, socks()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
        then(refundRepository).should(never()).sumQuantityByOrderItem(any(), any(), anyList());
    }

    @Test
    void 묶음에_없는_요청이면_승인할_수_없다() {
        // given
        given(refundRepository.findIdsByOrderIdAndSellerId(ORDER_ID, SELLER_ID)).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> sut.startApproval(REFUND_ID, SELLER_ID, socks()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
    }
}
