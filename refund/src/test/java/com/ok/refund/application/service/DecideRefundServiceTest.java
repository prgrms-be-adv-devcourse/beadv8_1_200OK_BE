package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.RefundDecision;
import com.ok.refund.application.port.in.RefundDecisionCommand;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.out.OrderCancellation;
import com.ok.refund.application.port.out.OrderCancellationPort;
import com.ok.refund.application.port.out.OrderCancellationResult;
import com.ok.refund.application.port.out.OrderQueryPort;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.application.port.out.WalletRefundRequest;
import com.ok.refund.application.port.out.WalletRefundRequestPort;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepStatus;
import com.ok.refund.domain.RefundStepType;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
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
    @Mock
    WalletRefundRequestPort walletRefundRequestPort;
    @Mock
    OrderCancellationPort orderCancellationPort;
    @Mock
    ProductStockReleasePort productStockReleasePort;
    @Mock
    RefundStepPersistencePort refundStepPersistencePort;

    DecideRefundService sut;

    @BeforeEach
    void setUp() {
        NoOpTransactionManager transactionManager = new NoOpTransactionManager();
        // 실패 기록은 RefundStepRunner가 같은 영속 포트로 하므로 진짜 틀을 쓴다(단계 줄이 실제로 "할 차례"로 남는지 보려고)
        RefundStepRunner refundStepRunner = new RefundStepRunner(refundPersistencePort, refundStepPersistencePort, CLOCK, transactionManager);
        sut = new DecideRefundService(orderQueryPort, refundPersistencePort, walletRefundRequestPort, orderCancellationPort,
                productStockReleasePort, refundStepPersistencePort, refundStepRunner, CLOCK, transactionManager);
    }

    // 아래 다섯 테스트는 예전 파사드 테스트에서 옮겼다. 주문 호출 여부와 승인 전 확인 순서를 decide 전체 흐름으로 본다
    @Test
    void 승인하면_환불의_주문_ID로_주문을_다시_조회해_서비스에_넘긴다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(requested()));
        given(orderQueryPort.getOrderItem(10L, 101L)).willReturn(potato("PREPARING", 2));
        givenLockedBundle(requested());
        givenOrderReflects();

        // when
        RefundResult result = sut.decide(new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.APPROVE, null));

        // then: 다시 조회한 주문 정보로 승인 구간까지 가서 처리 중이 되고, 돌려줄 돈이 있어 지갑 요청에서 멈춘다
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
        givenOrderReflects();

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2));

        // then: 돌려줄 돈이 있어 지갑 요청에서 멈추므로 처리 중이다(완료는 지갑 완료 메시지 뒤). 승인 구간이 저장한 값은 아래에서 본다
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
        givenLockedBundle(Refund.request(10L, 101L, 1, BUYER_ID, SELLER_ID, 55L, "감자", "1kg", 1_000L, null));

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
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
        then(refundPersistencePort).should(never()).update(any());
    }

    // 요청 때는 출고 전이었어도 승인 사이에 출고될 수 있다. 출고·취소된 상품은 취소로 돌려줄 수 없어 자동 거절한다.
    // "배송 중"만이 아니라 알려진 비-PREPARING 상태 8개 모두 거절되는지 확인한다
    @ParameterizedTest
    @MethodSource("출고되거나_취소된_주문_상태")
    void 승인_때_출고되거나_취소된_상품이면_사유와_함께_자동_거절한다(String orderItemStatus) {
        // given
        givenLockedBundle(requested());

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, potato(orderItemStatus, 2));

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
                "PAYMENT_PENDING", "PAYMENT_FAILED", "CANCELED");
    }

    // 주문 모듈의 버그나 새 상태일 수 있어 거절로 끝내지 않고 500으로 드러낸다. 저장한 것이 없어 요청됨 그대로 남는다
    @ParameterizedTest
    @MethodSource("모르는_주문_상태")
    void 승인_때_주문_상태를_모르면_예외를_던지고_요청됨으로_남는다(String orderItemStatus) {
        // given
        Refund refund = givenLockedBundle(requested());

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, potato(orderItemStatus, 2)))
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
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, potato("SHIPPING", 2)))
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
                new RefundableOrderItem(10L, 102L, BUYER_ID, SELLER_ID, 55L, "감자", "1kg", "SHIPPING", 10_000L, 2, bundle);

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
        assertThatThrownBy(() -> sut.approve(REFUND_ID, OTHER_SELLER_ID, potato("PREPARING", 2)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
        then(refundPersistencePort).should(never()).findByIdForUpdate(any());
    }

    @Test
    void 없는_요청을_승인하면_막는다() {
        // given
        given(refundPersistencePort.findLockTargetIds(10L, SELLER_ID, REFUND_ID, LOCK_TARGET_STATUSES)).willReturn(List.of(99L));

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2)))
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
        givenOrderReflects();

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2));

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

    // 지갑 반환 요청(#512). 승인이 커밋된 뒤 처리 중이 된 환불만, 돌려줄 돈이 있을 때만 보낸다.
    // 보내는지 여부와 넘긴 값 자체가 요구사항이라(돈이 나가는 약속) 호출과 값을 확인한다

    @Test
    void 승인해서_처리_중이_되면_돌려줄_금액으로_지갑에_반환을_요청한다() {
        // given: 감자 2개(10,000원씩)를 모두 취소해 묶음 전부 취소다. 상품 20,000원 + 낸 배송비 3,000원 = 23,000원을 돌려준다
        givenLockedBundle(requested());
        givenOrderReflects();

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2));

        // then
        assertThat(result.status()).isEqualTo("PROCESSING");
        ArgumentCaptor<WalletRefundRequest> captor = ArgumentCaptor.forClass(WalletRefundRequest.class);
        then(walletRefundRequestPort).should().requestRefund(captor.capture());
        WalletRefundRequest sent = captor.getValue();
        assertThat(sent).isNotNull();
        // 같은 Long 필드가 넷이라 서로 다른 값으로 확인한다(뒤바뀌면 다른 사람에게 돈이 간다)
        assertSoftly(softly -> {
            softly.assertThat(sent.refundId()).isEqualTo(REFUND_ID);
            softly.assertThat(sent.orderId()).isEqualTo(10L);
            softly.assertThat(sent.buyerId()).isEqualTo(BUYER_ID);
            softly.assertThat(sent.sellerId()).isEqualTo(SELLER_ID);
            softly.assertThat(sent.amount()).isEqualTo(23_000L);
        });
    }

    // 주문 취소 반영(#513). 주문이 반영한 뒤에 지갑에 알린다. 순서가 요구사항이다:
    // 지갑을 먼저 부르면 나간 반환 요청을 되돌릴 수 없어, 주문이 거절해도(출고됨) 돈이 나간다
    @Test
    void 승인하면_주문에_먼저_알린_뒤_지갑에_반환을_요청한다() {
        // given: 무료배송(기준 50,000원) 묶음에서 30,000원짜리 1개를 취소해 30,000원이 남는다. 기본 배송비 3,000원을 빼고 27,000원을 돌려준다
        givenLockedBundle(Refund.request(10L, 101L, 1, BUYER_ID, SELLER_ID, 55L, "셔츠", "기본", 30_000L, null));
        givenOrderReflects();
        BundleShipping bundle = new BundleShipping(0, 0, 3_000L, 50_000L,
                List.of(new BundleItem(101L, 30_000L, 1), new BundleItem(102L, 30_000L, 1)));

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, orderItem(101L, 30_000L, 1, bundle));

        // then
        assertThat(result.refundAmount()).isEqualTo(27_000L);
        InOrder order = inOrder(orderCancellationPort, walletRefundRequestPort);
        ArgumentCaptor<OrderCancellation> captor = ArgumentCaptor.forClass(OrderCancellation.class);
        then(orderCancellationPort).should(order).reflectCancellation(captor.capture());
        then(walletRefundRequestPort).should(order).requestRefund(any());
        OrderCancellation sent = captor.getValue();
        assertThat(sent).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sent.refundId()).isEqualTo(REFUND_ID);
            softly.assertThat(sent.orderItemId()).isEqualTo(101L);
            softly.assertThat(sent.quantity()).isEqualTo(1);
            softly.assertThat(sent.deductedShippingFee()).isEqualTo(3_000L);
        });
    }

    // 주문이 거절하면(409) 환불을 거절로 끝낸다. 돈과 재고가 이미 움직이지 않았어야 하므로 지갑·재고를 부르지 않는 것이 요구사항이다
    @Test
    void 주문이_반영을_거절하면_지갑과_재고를_부르지_않고_거절로_저장한다() {
        // given
        givenLockedBundle(requested());
        given(orderCancellationPort.reflectCancellation(any())).willReturn(OrderCancellationResult.rejected("이미 출고된 상품입니다."));

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2));

        // then
        then(walletRefundRequestPort).should(never()).requestRefund(any());
        then(productStockReleasePort).should(never()).releaseStock(any());
        // 승인 구간 저장(처리 중) → 거절 구간 저장(거절) 두 번이다
        List<Refund> updates = capturedUpdates();
        assertThat(updates).hasSize(2);
        Refund rejected = updates.get(1);
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(updates.getFirst().getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(rejected.getStatus()).isEqualTo(RefundStatus.REJECTED);
            softly.assertThat(rejected.getRejectReason()).isEqualTo(DecideRefundService.SHIPPED_REJECT_REASON);
            softly.assertThat(rejected.getActiveOrderItemId()).isNull();
            softly.assertThat(result.status()).isEqualTo("REJECTED");
            softly.assertThat(result.rejectReason()).isEqualTo(DecideRefundService.SHIPPED_REJECT_REASON);
        });
    }

    // 주문이 실패해도 승인은 이미 커밋돼 되돌릴 수 없다. 예외를 삼키면 판매자는 성공으로 알고 주문은 취소를 모르므로 그대로 올린다.
    // 실패 기록(ORDER)과 다시 시도는 #516이 맡는다
    @Test
    void 주문_반영이_실패하면_지갑에_반환을_요청하지_않는다() {
        // given
        givenLockedBundle(requested());
        givenSteps(inProgress(RefundStepType.ORDER));
        RestApiException failure = new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        willThrow(failure).given(orderCancellationPort).reflectCancellation(any());

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_API_FAILED));
        Refund updated = capturedUpdate();
        assertThat(updated.getStatus()).isEqualTo(RefundStatus.PROCESSING);
        // 주문이 취소를 모르는데 돈을 보내거나 재고를 풀면 배송할 수도 있는 상품 값이 나간다. 완료로도 바꾸지 않는다
        then(walletRefundRequestPort).should(never()).requestRefund(any());
        then(productStockReleasePort).should(never()).releaseStock(any());
        // 환불 저장은 승인 구간(처리 중) 한 번이다. 실행 틀의 실패 기록은 주문 줄에만 남는다(#574). 완료 저장은 없다
        assertThat(capturedUpdates()).extracting(Refund::getStatus)
                .containsExactly(RefundStatus.PROCESSING);
        assertLastStepFailed(RefundStepType.ORDER, "ORDER_API_FAILED");
    }

    // 돌려줄 돈이 있으면 지갑 요청까지만 하고 처리 중으로 응답한다(#544). 재고 복구와 완료는 지갑 완료 메시지를 받은 뒤라
    // 이 요청에서 재고를 부르거나 완료로 저장하면 지갑이 돈을 돌려주기도 전에 끝난 환불이 된다
    @Test
    void 돌려줄_돈이_있으면_지갑_요청까지만_하고_재고와_완료는_부르지_않는다() {
        // given
        givenLockedBundle(requested());
        givenOrderReflects();

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2));

        // then
        InOrder order = inOrder(orderCancellationPort, walletRefundRequestPort);
        then(orderCancellationPort).should(order).reflectCancellation(any());
        then(walletRefundRequestPort).should(order).requestRefund(any());
        then(productStockReleasePort).should(never()).releaseStock(any());
        // 승인 구간 저장(처리 중) 한 번뿐이다. 완료 구간 저장이 없다
        List<Refund> updates = capturedUpdates();
        assertThat(updates).hasSize(1);
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(updates.getFirst().getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(updates.getFirst().getCompletedAt()).isNull();
            softly.assertThat(result.status()).isEqualTo("PROCESSING");
            softly.assertThat(result.completedAt()).isNull();
        });
    }

    // 0원 환불(#414). 지갑을 거치지 않아 기다릴 메시지가 없으므로, 주문 반영 뒤 상품에 환불 개수만큼 재고를 되돌려 달라고 하고
    // 성공하면 같은 요청 안에서 완료로 바꾼다. 순서(주문 → 상품)와 넘긴 값이 요구사항이라 호출 순서와 값을 확인한다
    @Test
    void 돌려줄_금액이_0원이면_주문에_알린_뒤_재고를_되돌려_달라고_하고_완료로_바꾼다() {
        // given
        givenZeroAmountRefund();
        givenOrderReflects();

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, zeroAmountItem());

        // then
        InOrder order = inOrder(orderCancellationPort, productStockReleasePort);
        then(orderCancellationPort).should(order).reflectCancellation(any());
        ArgumentCaptor<StockRelease> captor = ArgumentCaptor.forClass(StockRelease.class);
        then(productStockReleasePort).should(order).releaseStock(captor.capture());
        StockRelease sent = captor.getValue();
        assertThat(sent).isNotNull();
        // 같은 Long 필드가 셋이라 서로 다른 값으로 확인한다(뒤바뀌면 다른 주문·옵션의 재고가 풀린다)
        assertSoftly(softly -> {
            softly.assertThat(sent.refundId()).isEqualTo(REFUND_ID);
            softly.assertThat(sent.orderId()).isEqualTo(10L);
            softly.assertThat(sent.productOptionId()).isEqualTo(55L);
            softly.assertThat(sent.quantity()).isEqualTo(1);
        });
        // 승인 구간 저장(처리 중) → 완료 구간 저장(완료, 진행 중 칸 비움) 두 번이다
        List<Refund> updates = capturedUpdates();
        assertThat(updates).hasSize(2);
        Refund completed = updates.get(1);
        assertSoftly(softly -> {
            softly.assertThat(updates.getFirst().getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(completed.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            softly.assertThat(completed.getActiveOrderItemId()).isNull();
            softly.assertThat(completed.getCompletedAt()).isEqualTo(NOW);
            softly.assertThat(result.status()).isEqualTo("COMPLETED");
            softly.assertThat(result.completedAt()).isEqualTo(NOW);
        });
    }

    // 승인·주문은 이미 끝나 되돌릴 수 없다. 예외를 삼키면 판매자는 성공으로 알고 재고는 묶인 채라 그대로 올린다.
    // 완료로 바꾸지 않아 처리 중으로 남는다. 돌려줄 돈이 있으면 재고 복구는 지갑 완료 메시지 뒤라 0원 환불만 해당한다.
    // 실패 기록(PRODUCT)과 다시 시도는 #516이 맡는다
    @Test
    void 돌려줄_금액이_0원일_때_재고_복구가_실패하면_예외를_그대로_올리고_완료로_바꾸지_않는다() {
        // given
        givenZeroAmountRefund();
        givenOrderReflects();
        givenSteps(inProgress(RefundStepType.ORDER), inProgress(RefundStepType.PRODUCT));
        RestApiException failure = new RestApiException(RefundErrorCode.PRODUCT_API_FAILED);
        willThrow(failure).given(productStockReleasePort).releaseStock(any());

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, zeroAmountItem()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.PRODUCT_API_FAILED));
        // 환불 저장은 승인 구간(처리 중) 한 번이다. 실행 틀의 실패 기록은 재고 줄에만 남는다(#574). 완료로 바꾸는 저장은 없다
        assertThat(capturedUpdates()).extracting(Refund::getStatus)
                .containsExactly(RefundStatus.PROCESSING);
        assertLastStepFailed(RefundStepType.PRODUCT, "PRODUCT_API_FAILED");
    }

    // 승인 버튼으로 왔어도 출고된 상품이면 자동 거절로 끝난다. 거절된 환불에 돈이 나가면 안 된다
    @Test
    void 승인_때_출고돼_자동_거절되면_지갑에도_주문에도_알리지_않는다() {
        // given
        givenLockedBundle(requested());

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, potato("SHIPPING", 2));

        // then
        assertThat(result.status()).isEqualTo("REJECTED");
        then(walletRefundRequestPort).should(never()).requestRefund(any());
        then(orderCancellationPort).should(never()).reflectCancellation(any());
        then(productStockReleasePort).should(never()).releaseStock(any());
    }

    // 출고 거절과 다른 경로다(배송비 판단 뒤 거절). 이유가 달라 따로 확인한다
    @Test
    void 승인_때_배송비가_모자라_자동_거절되면_지갑에도_주문에도_알리지_않는다() {
        // given
        givenLockedBundle(Refund.request(10L, 101L, 1, BUYER_ID, SELLER_ID, 55L, "감자", "1kg", 1_000L, null));

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, cheapItemInFreeBundle());

        // then
        assertThat(result.status()).isEqualTo("REJECTED");
        then(walletRefundRequestPort).should(never()).requestRefund(any());
        then(orderCancellationPort).should(never()).reflectCancellation(any());
        then(productStockReleasePort).should(never()).releaseStock(any());
    }

    @Test
    void 돌려줄_금액이_0원이면_지갑을_거치지_않고_주문과_재고만_부른다() {
        // given
        givenZeroAmountRefund();
        givenOrderReflects();

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, zeroAmountItem());

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("COMPLETED");
            softly.assertThat(result.deductedShippingFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(result.refundAmount()).isZero();
        });
        then(walletRefundRequestPort).should(never()).requestRefund(any());
        // 돌려줄 돈은 없어도 주문은 취소 수량과 뺀 배송비(판매자 몫)를 알아야 한다
        ArgumentCaptor<OrderCancellation> captor = ArgumentCaptor.forClass(OrderCancellation.class);
        then(orderCancellationPort).should().reflectCancellation(captor.capture());
        assertThat(captor.getValue().deductedShippingFee()).isEqualTo(3_000L);
        then(productStockReleasePort).should().releaseStock(any());
    }

    // 승인은 이미 커밋된 뒤라 되돌릴 수 없다. 예외를 삼키면 판매자는 성공으로 알고 돈은 나가지 않으므로 그대로 올린다.
    // 실패 기록(WALLET)과 다시 보내기는 #516이 맡는다
    @Test
    void 지갑_요청이_실패하면_재고를_부르지_않고_처리_중으로_남는다() {
        // given
        givenLockedBundle(requested());
        givenOrderReflects();
        givenSteps(inProgress(RefundStepType.ORDER), inProgress(RefundStepType.WALLET));
        IllegalStateException failure = new IllegalStateException("지갑 반환 요청 발행에 실패했습니다. refundId=" + REFUND_ID);
        willThrow(failure).given(walletRefundRequestPort).requestRefund(any());

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2)))
                .isSameAs(failure);
        Refund updated = capturedUpdate();
        assertThat(updated.getStatus()).isEqualTo(RefundStatus.PROCESSING);
        // 주문은 이미 취소를 기록했다(같은 refundId로 다시 보내면 주문이 성공으로 답해 #545 다시 시도가 이어 간다). 재고는 부르지 않는다
        then(orderCancellationPort).should().reflectCancellation(any());
        then(productStockReleasePort).should(never()).releaseStock(any());
        // 환불 저장은 승인 구간(처리 중) 한 번이다. 실행 틀의 실패 기록은 지갑 줄에만 남는다(#574)
        assertThat(capturedUpdates()).extracting(Refund::getStatus)
                .containsExactly(RefundStatus.PROCESSING);
        assertLastStepFailed(RefundStepType.WALLET, "IllegalStateException");
    }

    // 단계 진행 표(#574, 결정 5). 단계 줄을 언제 어떤 상태로 저장하는지가 요구사항이라(다시 시도·운영 확인의 근거) 넘긴 값을 확인한다

    @Test
    void 승인하면_같은_구간에서_주문_단계를_시도_중으로_만든다() {
        // given
        givenLockedBundle(requested());
        givenOrderReflects();

        // when
        sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2));

        // then: 첫 줄은 주문 줄이고, 커밋 뒤 바로 주문을 부르므로 할 차례가 아니라 시도 중이다
        RefundStep orderStep = capturedSavedSteps().getFirst();
        assertThat(orderStep).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(orderStep.getRefundId()).isEqualTo(REFUND_ID);
            softly.assertThat(orderStep.getType()).isEqualTo(RefundStepType.ORDER);
            softly.assertThat(orderStep.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
            softly.assertThat(orderStep.getAttemptCount()).isEqualTo(1);
            softly.assertThat(orderStep.getNextCheckAt()).isEqualTo(NOW.plusMinutes(1));
        });
        // 같은 구간: 처리 중 저장 뒤, 주문을 부르기 전에 만든다
        InOrder order = inOrder(refundPersistencePort, refundStepPersistencePort, orderCancellationPort);
        then(refundPersistencePort).should(order).update(any());
        then(refundStepPersistencePort).should(order).saveNew(argThat(step -> step.getType() == RefundStepType.ORDER));
        then(orderCancellationPort).should(order).reflectCancellation(any());
    }

    // 자동 거절은 부를 단계가 없어 표에 남기지 않는다(결정 5). 출고 거절과 배송비 모자람은 다른 경로라 둘 다 본다
    @ParameterizedTest
    @MethodSource("자동_거절되는_승인")
    void 자동_거절이면_단계를_만들지_않는다(Refund refund, RefundableOrderItem orderItem) {
        // given
        givenLockedBundle(refund);

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, orderItem);

        // then
        assertThat(result.status()).isEqualTo("REJECTED");
        then(refundStepPersistencePort).should(never()).saveNew(any());
        then(refundStepPersistencePort).should(never()).update(any());
    }

    static Stream<Arguments> 자동_거절되는_승인() {
        return Stream.of(
                Arguments.of(requested(), potato("SHIPPING", 2)),
                Arguments.of(Refund.request(10L, 101L, 1, BUYER_ID, SELLER_ID, 55L, "감자", "1kg", 1_000L, null),
                        cheapItemInFreeBundle()));
    }

    @Test
    void 주문이_거절하면_주문_단계는_거절됨이고_환불은_거절된다() {
        // given
        givenLockedBundle(requested());
        givenSteps(inProgress(RefundStepType.ORDER));
        given(orderCancellationPort.reflectCancellation(any())).willReturn(OrderCancellationResult.rejected("이미 출고된 상품입니다."));

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2));

        // then
        assertThat(result.status()).isEqualTo("REJECTED");
        RefundStep orderStep = capturedUpdatedSteps().getFirst();
        assertThat(orderStep).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(orderStep.getType()).isEqualTo(RefundStepType.ORDER);
            softly.assertThat(orderStep.getStatus()).isEqualTo(RefundStepStatus.REFUSED);
            softly.assertThat(orderStep.getLastReason()).isEqualTo("ORDER_REFUSED");
            softly.assertThat(orderStep.getNextCheckAt()).isNull();
        });
        // 다음 단계는 오지 않으므로 줄을 더 만들지 않는다
        assertThat(capturedSavedSteps()).extracting(RefundStep::getType).containsExactly(RefundStepType.ORDER);
    }

    // 실패 기록 틀이 주문 줄을 할 차례로 남긴다. 예외는 그대로 올라가 판매자 응답(503)은 예전과 같다
    @Test
    void 주문이_실패하면_주문_단계는_할_차례로_남고_예외가_올라간다() {
        // given
        givenLockedBundle(requested());
        givenSteps(inProgress(RefundStepType.ORDER));
        RestApiException failure = new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        willThrow(failure).given(orderCancellationPort).reflectCancellation(any());

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2)))
                .isSameAs(failure);
        RefundStep orderStep = capturedUpdatedSteps().getFirst();
        assertThat(orderStep).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(orderStep.getType()).isEqualTo(RefundStepType.ORDER);
            softly.assertThat(orderStep.getStatus()).isEqualTo(RefundStepStatus.READY);
            softly.assertThat(orderStep.getLastReason()).isEqualTo("ORDER_API_FAILED");
            softly.assertThat(orderStep.getNextCheckAt()).isEqualTo(NOW.plusMinutes(1));
        });
        // 주문이 실패했으니 다음 줄은 만들지 않는다
        assertThat(capturedSavedSteps()).extracting(RefundStep::getType).containsExactly(RefundStepType.ORDER);
    }

    // 먼저 기록하고 보내기: 지갑 줄이 커밋된 뒤에 발행해야 발행 실패·서버 중단 때도 "어디까지 왔나"가 남는다. 순서가 요구사항이다
    @Test
    void 주문이_성공하면_지갑_단계를_먼저_기록한_뒤_발행한다() {
        // given
        givenLockedBundle(requested());
        givenOrderReflects();
        givenSteps(inProgress(RefundStepType.ORDER), inProgress(RefundStepType.WALLET));

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2));

        // then
        assertThat(result.status()).isEqualTo("PROCESSING");
        InOrder order = inOrder(refundStepPersistencePort, walletRefundRequestPort);
        then(refundStepPersistencePort).should(order).saveNew(argThat(step -> step.getType() == RefundStepType.WALLET));
        then(walletRefundRequestPort).should(order).requestRefund(any());
        RefundStep walletStep = capturedSavedSteps().get(1);
        assertThat(walletStep).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(walletStep.getRefundId()).isEqualTo(REFUND_ID);
            softly.assertThat(walletStep.getType()).isEqualTo(RefundStepType.WALLET);
            softly.assertThat(walletStep.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
        });
        // 주문 줄은 성공, 발행 뒤 지갑 줄은 결과 대기(한도 30분)로 바뀐다
        List<RefundStep> updatedSteps = capturedUpdatedSteps();
        assertThat(updatedSteps).hasSize(2);
        assertSoftly(softly -> {
            softly.assertThat(updatedSteps.get(0).getType()).isEqualTo(RefundStepType.ORDER);
            softly.assertThat(updatedSteps.get(0).getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
            softly.assertThat(updatedSteps.get(1).getType()).isEqualTo(RefundStepType.WALLET);
            softly.assertThat(updatedSteps.get(1).getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(updatedSteps.get(1).getNextCheckAt()).isEqualTo(NOW.plusMinutes(30));
        });
    }

    // 경우 2: 지갑 줄은 발행 전에 기록됐으므로 발행이 실패하면 할 차례로 남는다. 다시 보내기는 #576이 이 줄을 보고 한다
    @Test
    void 지갑_발행이_실패하면_지갑_단계는_할_차례로_남는다() {
        // given
        givenLockedBundle(requested());
        givenOrderReflects();
        givenSteps(inProgress(RefundStepType.ORDER), inProgress(RefundStepType.WALLET));
        IllegalStateException failure = new IllegalStateException("지갑 반환 요청 발행에 실패했습니다. refundId=" + REFUND_ID);
        willThrow(failure).given(walletRefundRequestPort).requestRefund(any());

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2)))
                .isSameAs(failure);
        List<RefundStep> updatedSteps = capturedUpdatedSteps();
        assertThat(updatedSteps).hasSize(2);
        RefundStep walletStep = updatedSteps.get(1);
        assertSoftly(softly -> {
            softly.assertThat(updatedSteps.get(0).getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
            softly.assertThat(walletStep.getType()).isEqualTo(RefundStepType.WALLET);
            softly.assertThat(walletStep.getStatus()).isEqualTo(RefundStepStatus.READY);
            softly.assertThat(walletStep.getLastReason()).isEqualTo("IllegalStateException");
        });
    }

    // 경우 1: 지갑 결과 메시지가 "결과 대기" 기록보다 먼저 와 줄을 끝냈으면, 늦게 온 결과 대기가 끝난 결과를 덮어쓰면 안 된다
    @Test
    void 발행_뒤_지갑_단계가_이미_끝났으면_결과_대기로_바꾸지_않는다() {
        // given
        givenLockedBundle(requested());
        givenOrderReflects();
        givenSteps(RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.SUCCEEDED, 1, null, null, NOW));

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, potato("PREPARING", 2));

        // then: 응답은 예전처럼 처리 중이고, 끝난 지갑 줄은 저장하지 않는다
        assertThat(result.status()).isEqualTo("PROCESSING");
        then(refundStepPersistencePort).should(never()).update(any());
    }

    // 0원은 지갑 줄 없이 재고 줄을 거친다. 재고 줄 성공과 환불 완료는 같은 구간에서 저장한다(결정 5)
    @Test
    void 돌려줄_금액이_0원이면_재고_단계를_거쳐_완료한다() {
        // given
        givenZeroAmountRefund();
        givenOrderReflects();
        givenSteps(inProgress(RefundStepType.ORDER), inProgress(RefundStepType.PRODUCT));

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, zeroAmountItem());

        // then
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(capturedSavedSteps()).extracting(RefundStep::getType)
                .containsExactly(RefundStepType.ORDER, RefundStepType.PRODUCT);
        List<RefundStep> updatedSteps = capturedUpdatedSteps();
        assertThat(updatedSteps).hasSize(2);
        assertSoftly(softly -> {
            softly.assertThat(updatedSteps.get(0).getType()).isEqualTo(RefundStepType.ORDER);
            softly.assertThat(updatedSteps.get(0).getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
            softly.assertThat(updatedSteps.get(1).getType()).isEqualTo(RefundStepType.PRODUCT);
            softly.assertThat(updatedSteps.get(1).getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
        });
        // 재고 줄 성공은 완료 저장 뒤 같은 구간에서 한다
        InOrder order = inOrder(productStockReleasePort, refundPersistencePort, refundStepPersistencePort);
        then(productStockReleasePort).should(order).releaseStock(any());
        then(refundPersistencePort).should(order).update(argThat(refund -> refund.getStatus() == RefundStatus.COMPLETED));
        then(refundStepPersistencePort).should(order).update(argThat(step -> step.getType() == RefundStepType.PRODUCT));
    }

    // 저장소에 있는 단계 줄처럼 종류로 찾아 준다. 주문 성공 구간 등에서 다른 종류를 찾아도 없는 줄로 답한다.
    // 실제 저장소처럼 찾을 때마다 새 객체를 준다(같은 객체를 주면 앞 구간의 변경이 뒤 구간 판단에 섞인다)
    private void givenSteps(RefundStep... steps) {
        Map<RefundStepType, RefundStep> byType = new EnumMap<>(RefundStepType.class);
        for (RefundStep step : steps) {
            byType.put(step.getType(), step);
        }
        willAnswer(invocation -> {
            RefundStep found = byType.get(invocation.<RefundStepType>getArgument(1));
            return Optional.ofNullable(found).map(DecideRefundServiceTest::copyOf);
        }).given(refundStepPersistencePort).findByRefundIdAndType(eq(REFUND_ID), any());
    }

    private static RefundStep inProgress(RefundStepType type) {
        return RefundStep.restore((long) type.ordinal() + 1, REFUND_ID, type, RefundStepStatus.IN_PROGRESS, 1,
                NOW.plusMinutes(1), null, NOW);
    }

    private static RefundStep copyOf(RefundStep step) {
        return RefundStep.restore(step.getId(), step.getRefundId(), step.getType(), step.getStatus(), step.getAttemptCount(),
                step.getNextCheckAt(), step.getLastReason(), step.getCreatedAt());
    }

    // saveNew에 넘긴 단계 줄 전부, 부른 순서대로
    private List<RefundStep> capturedSavedSteps() {
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should(atLeastOnce()).saveNew(captor.capture());
        return captor.getAllValues();
    }

    // 실행 틀이 마지막으로 고친 단계 줄이 그 단계이고, 다시 할 차례로 돌아가 이유가 남았는지 본다
    private void assertLastStepFailed(RefundStepType type, String reason) {
        List<RefundStep> updatedSteps = capturedUpdatedSteps();
        RefundStep last = updatedSteps.get(updatedSteps.size() - 1);
        assertThat(last).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(last.getType()).isEqualTo(type);
            softly.assertThat(last.getStatus()).isEqualTo(RefundStepStatus.READY);
            softly.assertThat(last.getLastReason()).isEqualTo(reason);
        });
    }

    // 단계 줄 update에 넘긴 값 전부, 부른 순서대로
    private List<RefundStep> capturedUpdatedSteps() {
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should(atLeastOnce()).update(captor.capture());
        return captor.getAllValues();
    }

    // 무료배송(기준 30,000원) 묶음에서 3,000원 상품을 취소하면 27,000원이 남아 무료배송이 깨진다.
    // 기본 배송비 3,000원을 빼면 3,000 - 3,000 = 0원 환불이다. 받을 수는 있다(취소 금액 = 뺄 배송비)
    private void givenZeroAmountRefund() {
        givenLockedBundle(Refund.request(10L, 101L, 1, BUYER_ID, SELLER_ID, 55L, "배추", "1kg", 3_000L, null));
    }

    private static RefundableOrderItem zeroAmountItem() {
        BundleShipping bundle = new BundleShipping(0, 0, 3_000L, 30_000L,
                List.of(new BundleItem(101L, 3_000L, 1), new BundleItem(102L, 27_000L, 1)));
        return orderItem(101L, 3_000L, 1, bundle);
    }

    // 주문이 취소를 반영했다고 답하게 한다
    private void givenOrderReflects() {
        given(orderCancellationPort.reflectCancellation(any())).willReturn(OrderCancellationResult.reflected());
    }

    private static Refund requested() {
        return Refund.request(10L, 101L, 2, BUYER_ID, SELLER_ID, 55L, "감자", "1kg", 10_000L, null);
    }

    private static RefundableOrderItem potato(String status, int boughtQuantity) {
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(101L, 10_000L, boughtQuantity)));
        return new RefundableOrderItem(10L, 101L, BUYER_ID, 5L, 55L, "감자", "1kg", status, 10_000L, boughtQuantity, bundle);
    }

    // 승인은 잠글 ID를 읽고 한 건씩 잠가 이번 환불을 ID로 찾는다. 저장 전 도메인이라 같은 값에 ID를 붙여 잠금 조회 결과로 준다.
    // 서비스가 바꾸는 것은 돌려준 객체라, 바뀌지 않았는지 확인하려면 돌려받은 객체를 봐야 한다.
    // 승인이 끝나면 완료 구간(#414)이 새 트랜잭션에서 같은 환불을 다시 잠금 조회한다. 실제 저장소처럼 그때의 값으로 새 객체를 준다
    // (같은 객체를 주면 승인 구간이 저장한 객체까지 완료로 바뀌어 승인 구간의 저장 값을 확인할 수 없다)
    private Refund givenLockedBundle(Refund refund) {
        Refund locked = withId(refund, REFUND_ID);
        given(refundPersistencePort.findLockTargetIds(10L, SELLER_ID, REFUND_ID, LOCK_TARGET_STATUSES))
                .willReturn(List.of(REFUND_ID));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID))
                .willReturn(Optional.of(locked))
                .willAnswer(invocation -> Optional.of(withId(locked, REFUND_ID)));
        return locked;
    }

    // 저장소가 돌려주는 환불처럼 ID가 붙은 같은 값을 만든다. 도메인에 setter가 없어(#481) 복원 메서드를 쓴다
    private static Refund withId(Refund refund, Long id) {
        return Refund.restore(id, refund.getOrderId(), refund.getOrderItemId(), refund.getQuantity(), refund.getBuyerId(),
                refund.getSellerId(), refund.getProductOptionId(), refund.getProductName(), refund.getOptionName(),
                refund.getUnitPrice(), refund.getItemAmount(), refund.getShippingFeeAmount(), refund.getRemoteAreaFeeAmount(),
                refund.getDeductedShippingFeeAmount(), refund.getCancelReason(), refund.getStatus(), refund.getRejectReason(),
                refund.getDecidedAt(), refund.getCompletedAt(), refund.getActiveOrderItemId(),
                refund.getCreatedAt());
    }

    // 승인·거절 구간에서 update에 넘긴 환불(첫 번째 저장). 승인이 끝까지 가면 완료 구간이 한 번 더 저장한다(capturedUpdates 참고)
    private Refund capturedUpdate() {
        List<Refund> updates = capturedUpdates();
        assertThat(updates).isNotEmpty();
        return updates.getFirst();
    }

    // update에 넘긴 환불 전부, 부른 순서대로
    private List<Refund> capturedUpdates() {
        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should(atLeastOnce()).update(captor.capture());
        return captor.getAllValues();
    }

    private static RefundableOrderItem cheapItemInFreeBundle() {
        BundleShipping bundle = new BundleShipping(0, 0, 3_000L, 50_000L,
                List.of(new BundleItem(101L, 1_000L, 1), new BundleItem(102L, 49_500L, 1)));
        return orderItem(101L, 1_000L, 1, bundle);
    }

    private static RefundableOrderItem orderItem(Long orderItemId, long unitPrice, int quantity, BundleShipping bundle) {
        return new RefundableOrderItem(10L, orderItemId, BUYER_ID, SELLER_ID, 55L, "감자", "1kg", "PREPARING", unitPrice,
                quantity, bundle);
    }
}
