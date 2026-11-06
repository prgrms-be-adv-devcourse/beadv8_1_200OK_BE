package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.RefundDecision;
import com.ok.refund.application.port.in.RefundDecisionCommand;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.out.OrderQueryPort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

// 승인 구간(주문 정보를 받은 뒤의 approve)은 패키지 안 메서드로 직접 부르고, 주문 호출 여부가 걸린 흐름은 decide로 부른다.
// 주문 호출 여부는 설계 요구사항(승인만 주문을 다시 조회하고, 거절·끝난 요청은 주문에 묶이지 않음)이라 호출을 검증한다
@ExtendWith(MockitoExtension.class)
class DecideRefundServiceTest {

    private static final Long BUYER_ID = 1L;
    private static final Long SELLER_ID = 5L;
    private static final Long OTHER_SELLER_ID = 888L;
    private static final Long REFUND_ID = 7L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T01:00:00Z"), SEOUL);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 10, 0);
    private static final List<RefundStatus> LOCK_TARGET_STATUSES = List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING);

    @Mock
    OrderQueryPort orderQueryPort;
    @Mock
    RefundPersistencePort refundPersistencePort;

    DecideRefundService sut;

    @BeforeEach
    void setUp() {
        sut = new DecideRefundService(orderQueryPort, refundPersistencePort, CLOCK, new NoOpTransactionManager());
    }

    // 아래 다섯 테스트는 예전 파사드 테스트에서 옮겼다. 주문 호출 여부와 승인 전 확인 순서를 decide 전체 흐름으로 본다
    @Test
    void 승인하면_환불의_주문_ID로_주문을_다시_조회해_서비스에_넘긴다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(requested()));
        given(orderQueryPort.getOrderItem(10L, 101L)).willReturn(socks("PREPARING", 2));
        givenLockedBundle(requested());

        // when
        RefundResult result = sut.decide(new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.APPROVE, null));

        // then: 다시 조회한 주문 정보로 승인 구간까지 가서 처리 중이 된다
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.refundId()).isEqualTo(REFUND_ID);
            softly.assertThat(result.status()).isEqualTo("PROCESSING");
        });
    }

    @Test
    void 거절은_주문을_부르지_않는다() {
        // given
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(requested()));

        // when
        RefundResult result = sut.decide(new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.REJECT, "사유"));

        // then
        assertThat(result.status()).isEqualTo("REJECTED");
        then(orderQueryPort).should(never()).getOrderItem(any(), any());
    }

    @Test
    void 이미_끝난_요청을_승인하면_주문을_부르지_않고_막는다() {
        // given
        Refund approved = requested();
        approved.approve(NOW, RefundShippingFee.none());
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(approved));

        // when & then
        assertThatThrownBy(() -> sut.decide(new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.APPROVE, null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
        then(orderQueryPort).should(never()).getOrderItem(any(), any());
    }

    @Test
    void 남의_요청을_승인하면_주문을_부르지_않고_막는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(requested()));

        // when & then
        assertThatThrownBy(() -> sut.decide(new RefundDecisionCommand(REFUND_ID, OTHER_SELLER_ID, RefundDecision.APPROVE, null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
        then(orderQueryPort).should(never()).getOrderItem(any(), any());
    }

    @Test
    void 승인_때_주문_호출이_실패하면_승인하지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(requested()));
        given(orderQueryPort.getOrderItem(10L, 101L)).willThrow(new RestApiException(RefundErrorCode.ORDER_API_FAILED));

        // when & then
        assertThatThrownBy(() -> sut.decide(new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.APPROVE, null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_API_FAILED));
        // 승인 구간에 들어가지 않았다는 것: 묶음 잠금도, 저장도 하지 않는다
        then(refundPersistencePort).should(never()).findLockTargetIds(any(), any(), any(), any());
        then(refundPersistencePort).should(never()).update(any());
    }

    @Test
    void 남의_상품_요청은_조회해도_없는_요청처럼_막는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(requested()));

        // when & then
        assertThatThrownBy(() -> sut.decide(new RefundDecisionCommand(REFUND_ID, OTHER_SELLER_ID, RefundDecision.APPROVE, null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
    }

    @Test
    void 승인하면_지금_시각으로_처리_중이_되고_배송비_판단을_저장한다() {
        // given: 산 2개를 모두 취소해 묶음 전부 취소다. 낸 배송비 3,000원을 돌려준다
        givenLockedBundle(requested());

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, socks("PREPARING", 2));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("PROCESSING");
            softly.assertThat(result.decidedAt()).isEqualTo(NOW);
        });
        // 명시적 저장(#481): update를 부르지 않으면 바꾼 상태가 DB에 남지 않으므로, 호출과 넘긴 값 자체가 요구사항이다
        Refund updated = capturedUpdate();
        assertSoftly(softly -> {
            softly.assertThat(updated.getId()).isEqualTo(REFUND_ID);
            softly.assertThat(updated.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(updated.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(updated.getShippingFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(updated.getRemoteAreaFeeAmount()).isZero();
            softly.assertThat(updated.getDeductedShippingFeeAmount()).isZero();
            softly.assertThat(updated.getActiveOrderItemId()).isEqualTo(101L);
        });
    }

    @Test
    void 승인_때_배송비가_모자라면_사유와_함께_자동_거절한다() {
        // given
        givenLockedBundle(Refund.request(10L, 101L, 1, BUYER_ID, SELLER_ID, 55L, "양말", "흰색", 1_000L, null));

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, cheapItemInFreeBundle());

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("REJECTED");
            softly.assertThat(result.rejectReason()).isEqualTo(RefundErrorCode.PARTIAL_CANCEL_NOT_ALLOWED.getMessage());
            softly.assertThat(result.decidedAt()).isEqualTo(NOW);
        });
        // 자동 거절도 상태를 바꾸므로 update로 저장해야 한다. 진행 중 칸을 비워 저장해야 같은 상품을 다시 요청할 수 있다
        Refund updated = capturedUpdate();
        assertSoftly(softly -> {
            softly.assertThat(updated.getStatus()).isEqualTo(RefundStatus.REJECTED);
            softly.assertThat(updated.getRejectReason()).isEqualTo(RefundErrorCode.PARTIAL_CANCEL_NOT_ALLOWED.getMessage());
            softly.assertThat(updated.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(updated.getActiveOrderItemId()).isNull();
            softly.assertThat(updated.getDeductedShippingFeeAmount()).isZero();
        });
    }

    // 이미 처리 중인 요청은 자기 개수가 집계에 들어가 배송비 판단이 깨지므로, 판단 전에 상태로 막아야 한다
    @Test
    void 요청됨이_아니면_배송비를_판단하기_전에_막는다() {
        // given
        Refund refund = requested();
        refund.approve(NOW, RefundShippingFee.none());
        givenLockedBundle(refund);

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, socks("PREPARING", 2)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
        then(refundPersistencePort).should(never()).update(any());
    }

    // 요청 때는 출고 전이었어도 승인 사이에 출고될 수 있다. 출고·취소된 상품은 취소로 돌려줄 수 없어 자동 거절한다.
    // "배송 중"만이 아니라 알려진 비-PREPARING 상태 7개 모두 거절되는지 확인한다
    @ParameterizedTest
    @MethodSource("출고되거나_취소된_주문_상태")
    void 승인_때_출고되거나_취소된_상품이면_사유와_함께_자동_거절한다(String orderItemStatus) {
        // given
        givenLockedBundle(requested());

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, socks(orderItemStatus, 2));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("REJECTED");
            softly.assertThat(result.rejectReason()).isEqualTo(DecideRefundService.SHIPPED_REJECT_REASON);
            softly.assertThat(result.decidedAt()).isEqualTo(NOW);
        });
        // 출고 자동 거절은 배송비 판단보다 먼저 끝나는 별도 경로라, 이 경로에서도 update로 저장하는지 따로 확인한다
        Refund updated = capturedUpdate();
        assertSoftly(softly -> {
            softly.assertThat(updated.getStatus()).isEqualTo(RefundStatus.REJECTED);
            softly.assertThat(updated.getRejectReason()).isEqualTo(DecideRefundService.SHIPPED_REJECT_REASON);
            softly.assertThat(updated.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(updated.getActiveOrderItemId()).isNull();
            softly.assertThat(updated.getShippingFeeAmount()).isZero();
            softly.assertThat(updated.getRemoteAreaFeeAmount()).isZero();
            softly.assertThat(updated.getDeductedShippingFeeAmount()).isZero();
        });
        // 거절될 환불에는 배송비 판단(집계 쿼리)을 할 이유가 없고, 판단이 예외를 내면 거절 대신 오류가 남는다
        then(refundPersistencePort).should(never()).sumCancelledByOrderItem(any(), any(), any());
    }

    static Stream<String> 출고되거나_취소된_주문_상태() {
        return Stream.of("SHIPPING", "DELIVERED", "PURCHASE_CONFIRMED", "CANCEL_REQUESTED", "REFUNDED",
                "PAYMENT_PENDING", "PAYMENT_FAILED");
    }

    // 주문 모듈의 버그나 새 상태일 수 있어 거절로 끝내지 않고 500으로 드러낸다. 저장한 것이 없어 요청됨 그대로 남는다
    @ParameterizedTest
    @MethodSource("모르는_주문_상태")
    void 승인_때_주문_상태를_모르면_예외를_던지고_요청됨으로_남는다(String orderItemStatus) {
        // given
        Refund refund = givenLockedBundle(requested());

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, socks(orderItemStatus, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("알 수 없는 주문 상품 상태라 승인할 수 없습니다. refundId=7, status=" + orderItemStatus);
        assertThat(refund).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            softly.assertThat(refund.getDecidedAt()).isNull();
        });
        then(refundPersistencePort).should(never()).update(any());
    }

    static Stream<String> 모르는_주문_상태() {
        return Stream.of(null, "UNKNOWN");
    }

    // 이미 끝난 요청은 출고 여부와 상관없이 처리할 수 없다는 것이 진짜 이유라, 판매자에게 상태 충돌을 먼저 알린다
    @Test
    void 철회됐고_출고도_됐으면_상태_충돌이_먼저다() {
        // given
        Refund refund = requested();
        refund.withdraw(NOW);
        givenLockedBundle(refund);

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, socks("SHIPPING", 2)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
    }

    @Test
    void 환불과_다른_주문_상품_정보로_승인하면_예외를_던지고_바꾸지_않는다() {
        // given
        Refund refund = givenLockedBundle(requested());
        RefundableOrderItem otherItem = orderItem(102L, 10_000L, 2,
                new BundleShipping(0, 0, 3_000L, 50_000L, List.of(new BundleItem(102L, 10_000L, 2))));

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, otherItem))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("환불과 다른 주문 상품 정보로 승인할 수 없습니다. refundId=7");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        then(refundPersistencePort).should(never()).update(any());
    }

    // 다른 주문 상품 정보는 출고 상태여도 먼저 막아야 한다. 순서가 바뀌면 엉뚱한 상품의 출고 상태로 이 환불이 거절된다
    @Test
    void 다른_주문_상품_정보가_출고_상태여도_다른_상품_예외가_먼저이고_거절하지_않는다() {
        // given
        Refund refund = givenLockedBundle(requested());
        BundleShipping bundle = new BundleShipping(0, 0, 3_000L, 50_000L, List.of(new BundleItem(102L, 10_000L, 2)));
        RefundableOrderItem shippedOtherItem =
                new RefundableOrderItem(10L, 102L, BUYER_ID, SELLER_ID, 55L, "양말", "흰색", "SHIPPING", 10_000L, 2, bundle);

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, shippedOtherItem))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("환불과 다른 주문 상품 정보로 승인할 수 없습니다. refundId=7");
        assertThat(refund).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            softly.assertThat(refund.getDecidedAt()).isNull();
        });
    }

    // 남의 요청은 판매자 ID로 찾는 잠글 목록에 들어오지 않는다. 잠그지도 않아야 남의 행을 건드리지 않는다
    @Test
    void 남의_상품_요청을_승인하면_없는_요청처럼_막고_잠그지도_않는다() {
        // given
        given(refundPersistencePort.findLockTargetIds(10L, OTHER_SELLER_ID, REFUND_ID, LOCK_TARGET_STATUSES)).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, OTHER_SELLER_ID, socks("PREPARING", 2)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
        then(refundPersistencePort).should(never()).findByIdForUpdate(any());
    }

    @Test
    void 없는_요청을_승인하면_막는다() {
        // given
        given(refundPersistencePort.findLockTargetIds(10L, SELLER_ID, REFUND_ID, LOCK_TARGET_STATUSES)).willReturn(List.of(99L));

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, socks("PREPARING", 2)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
        then(refundPersistencePort).should(never()).findByIdForUpdate(any());
    }

    // 이번 환불 행만 잠그면 같은 묶음의 동시 승인이 서로를 못 본다(#411). 묶음의 진행 중 행을 ID 오름차순으로 한 건씩 잠그는 것이
    // 요구사항이라(순서가 다르면 교착 상태) 호출 순서를 검증한다
    @Test
    void 승인하면_같은_묶음의_진행_중_환불을_ID_순서로_한_건씩_잠근다() {
        // given
        Refund refund = withId(requested(), REFUND_ID);
        Refund other = withId(Refund.request(10L, 102L, 1, BUYER_ID, SELLER_ID, 56L, "모자", "검정", 5_000L, null), 3L);
        given(refundPersistencePort.findLockTargetIds(10L, SELLER_ID, REFUND_ID, LOCK_TARGET_STATUSES))
                .willReturn(List.of(3L, REFUND_ID));
        given(refundPersistencePort.findByIdForUpdate(3L)).willReturn(Optional.of(other));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, socks("PREPARING", 2));

        // then
        assertThat(result.refundId()).isEqualTo(REFUND_ID);
        InOrder lockOrder = inOrder(refundPersistencePort);
        lockOrder.verify(refundPersistencePort).findByIdForUpdate(3L);
        lockOrder.verify(refundPersistencePort).findByIdForUpdate(REFUND_ID);
    }

    @Test
    void 거절하면_사유를_도메인에_넘기고_저장한다() {
        // given
        Refund refund = requested();
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when
        RefundResult result = sut.decide(new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.REJECT, "이미 포장을 마쳤습니다."));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("REJECTED");
            softly.assertThat(result.rejectReason()).isEqualTo("이미 포장을 마쳤습니다.");
            softly.assertThat(result.decidedAt()).isEqualTo(NOW);
        });
        // 명시적 저장(#481): update 호출과 넘긴 값 자체가 요구사항이다
        Refund updated = capturedUpdate();
        assertSoftly(softly -> {
            softly.assertThat(updated.getStatus()).isEqualTo(RefundStatus.REJECTED);
            softly.assertThat(updated.getRejectReason()).isEqualTo("이미 포장을 마쳤습니다.");
            softly.assertThat(updated.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(updated.getActiveOrderItemId()).isNull();
        });
    }

    @Test
    void 남의_상품_요청을_거절하면_없는_요청처럼_막고_바꾸지_않는다() {
        // given
        Refund refund = requested();
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when & then
        assertThatThrownBy(() -> sut.decide(new RefundDecisionCommand(REFUND_ID, OTHER_SELLER_ID, RefundDecision.REJECT, "사유")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        then(refundPersistencePort).should(never()).update(any());
    }

    private static Refund requested() {
        return Refund.request(10L, 101L, 2, BUYER_ID, SELLER_ID, 55L, "양말", "흰색", 10_000L, null);
    }

    private static RefundableOrderItem socks(String status, int boughtQuantity) {
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(101L, 10_000L, boughtQuantity)));
        return new RefundableOrderItem(10L, 101L, BUYER_ID, 5L, 55L, "양말", "흰색", status, 10_000L, boughtQuantity, bundle);
    }

    // 승인은 잠글 ID를 읽고 한 건씩 잠가 이번 환불을 ID로 찾는다. 저장 전 도메인이라 같은 값에 ID를 붙여 잠금 조회 결과로 준다.
    // 서비스가 바꾸는 것은 돌려준 객체라, 바뀌지 않았는지 확인하려면 돌려받은 객체를 봐야 한다
    private Refund givenLockedBundle(Refund refund) {
        Refund locked = withId(refund, REFUND_ID);
        given(refundPersistencePort.findLockTargetIds(10L, SELLER_ID, REFUND_ID, LOCK_TARGET_STATUSES))
                .willReturn(List.of(REFUND_ID));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(locked));
        return locked;
    }

    // 저장소가 돌려주는 환불처럼 ID가 붙은 같은 값을 만든다. 도메인에 setter가 없어(#481) 복원 메서드를 쓴다
    private static Refund withId(Refund refund, Long id) {
        return Refund.restore(id, refund.getOrderId(), refund.getOrderItemId(), refund.getQuantity(), refund.getBuyerId(),
                refund.getSellerId(), refund.getProductOptionId(), refund.getProductName(), refund.getOptionName(),
                refund.getUnitPrice(), refund.getItemAmount(), refund.getShippingFeeAmount(), refund.getRemoteAreaFeeAmount(),
                refund.getDeductedShippingFeeAmount(), refund.getCancelReason(), refund.getStatus(), refund.getRejectReason(),
                refund.getDecidedAt(), refund.getFailedStep(), refund.getFailureCode(), refund.getActiveOrderItemId(),
                refund.getCreatedAt());
    }

    // update에 넘긴 환불. 정확히 한 번 불러야 한다(두 번이면 상태 변경 경로가 꼬인 것이다)
    private Refund capturedUpdate() {
        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(captor.capture());
        Refund updated = captor.getValue();
        assertThat(updated).isNotNull();
        return updated;
    }

    private static RefundableOrderItem cheapItemInFreeBundle() {
        BundleShipping bundle = new BundleShipping(0, 0, 3_000L, 50_000L,
                List.of(new BundleItem(101L, 1_000L, 1), new BundleItem(102L, 49_500L, 1)));
        return orderItem(101L, 1_000L, 1, bundle);
    }

    private static RefundableOrderItem orderItem(Long orderItemId, long unitPrice, int quantity, BundleShipping bundle) {
        return new RefundableOrderItem(10L, orderItemId, BUYER_ID, SELLER_ID, 55L, "양말", "흰색", "PREPARING", unitPrice,
                quantity, bundle);
    }
}
