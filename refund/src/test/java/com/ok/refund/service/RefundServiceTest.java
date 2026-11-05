package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundRequestCommand;
import com.ok.refund.service.dto.RefundResult;
import com.ok.refund.service.dto.RefundableOrderItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final Long BUYER_ID = 1L;
    private static final Long OTHER_BUYER_ID = 999L;
    private static final Long SELLER_ID = 5L;
    private static final Long OTHER_SELLER_ID = 888L;
    private static final Long REFUND_ID = 7L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T01:00:00Z"), SEOUL);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 10, 0);
    private static final List<RefundStatus> LOCK_TARGET_STATUSES = List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING);

    @Mock
    RefundRepository refundRepository;

    RefundService sut;

    @BeforeEach
    void setUp() {
        sut = new RefundService(refundRepository, CLOCK);
    }

    @Test
    void 요청하면_주문에서_받은_값으로_요청됨을_저장한다() {
        // given
        given(refundRepository.saveAndFlush(any(Refund.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        sut.request(command(2), socks("PREPARING", 3));

        // then
        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        then(refundRepository).should().saveAndFlush(captor.capture());
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
        RefundRequestCommand otherBuyer = new RefundRequestCommand(OTHER_BUYER_ID, 10L, 101L, 1, null);

        // when & then
        assertThatThrownBy(() -> sut.request(otherBuyer, socks("PREPARING", 3)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_ITEM_NOT_FOUND));
        then(refundRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 출고_전이_아니면_막는다() {
        // when & then
        assertThatThrownBy(() -> sut.request(command(1), socks("SHIPPING", 3)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_ITEM_NOT_CANCELLABLE));
        then(refundRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 산_개수보다_많으면_막는다() {
        // when & then
        assertThatThrownBy(() -> sut.request(command(4), socks("PREPARING", 3)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_QUANTITY_EXCEEDED));
        then(refundRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 산_개수만큼은_요청할_수_있다() {
        // given
        given(refundRepository.saveAndFlush(any(Refund.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        RefundResult result = sut.request(command(3), socks("PREPARING", 3));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.quantity()).isEqualTo(3);
            softly.assertThat(result.itemAmount()).isEqualTo(30_000L);
            softly.assertThat(result.status()).isEqualTo("REQUESTED");
        });
    }

    @Test
    void 진행_중_UNIQUE에_걸리면_진행_중_에러로_바꾼다() {
        // given
        given(refundRepository.saveAndFlush(any(Refund.class))).willThrow(new DataIntegrityViolationException("중복",
                new SQLException("Duplicate entry '101' for key 'refund.uk_refund_active_order_item_id'")));

        // when & then
        assertThatThrownBy(() -> sut.request(command(1), socks("PREPARING", 3)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS));
    }

    @Test
    void 다른_무결성_오류는_그대로_던진다() {
        // given
        DataIntegrityViolationException other = new DataIntegrityViolationException("다른 제약",
                new SQLException("Check constraint 'ck_refund_quantity' is violated."));
        given(refundRepository.saveAndFlush(any(Refund.class))).willThrow(other);

        // when & then
        assertThatThrownBy(() -> sut.request(command(1), socks("PREPARING", 3)))
                .isSameAs(other);
    }

    // 철회 커밋 순간 같은 상품 새 요청 둘이 겹치면 MySQL이 한쪽을 교착 상태로 되돌린다. 그 쪽도 진행 중 중복으로 본다.
    // 원인 예외 모양은 재현 때 실제로 받은 값(CannotAcquireLockException, SQLState 40001, 1213)과 같게 만든다
    @Test
    void 저장이_교착_상태로_되돌려지면_진행_중_에러로_바꾼다() {
        // given
        given(refundRepository.saveAndFlush(any(Refund.class))).willThrow(new CannotAcquireLockException("교착",
                new SQLException("Deadlock found when trying to get lock; try restarting transaction", "40001", 1213)));

        // when & then
        assertThatThrownBy(() -> sut.request(command(1), socks("PREPARING", 3)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS));
    }

    @Test
    void 잠금_대기_시간_초과는_중복이_아니라_그대로_던진다() {
        // given
        CannotAcquireLockException timeout = new CannotAcquireLockException("대기 시간 초과",
                new SQLException("Lock wait timeout exceeded; try restarting transaction", "HY000", 1205));
        given(refundRepository.saveAndFlush(any(Refund.class))).willThrow(timeout);

        // when & then
        assertThatThrownBy(() -> sut.request(command(1), socks("PREPARING", 3)))
                .isSameAs(timeout);
    }

    // 무료배송(50,000원 이상) 묶음에서 1,000원짜리만 취소하면 49,500원이 남아 기준이 깨지고, 뺄 배송비 3,000원이 취소 금액보다 크다
    @Test
    void 요청_때_배송비가_모자라면_409로_막는다() {
        // given
        given(refundRepository.saveAndFlush(any(Refund.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when & then
        assertThatThrownBy(() -> sut.request(new RefundRequestCommand(BUYER_ID, 10L, 101L, 1, null), cheapItemInFreeBundle()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.PARTIAL_CANCEL_NOT_ALLOWED));
    }

    @Test
    void 남의_상품_요청은_조회해도_없는_요청처럼_막는다() {
        // given
        given(refundRepository.findById(REFUND_ID)).willReturn(Optional.of(requested()));

        // when & then
        assertThatThrownBy(() -> sut.getSellerRefund(REFUND_ID, OTHER_SELLER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
    }

    @Test
    void 승인하면_지금_시각으로_처리_중이_되고_배송비_판단을_저장한다() {
        // given: 산 2개를 모두 취소해 묶음 전부 취소다. 낸 배송비 3,000원을 돌려준다
        Refund refund = requested();
        givenLockedBundle(refund);

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, socks("PREPARING", 2));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("PROCESSING");
            softly.assertThat(result.decidedAt()).isEqualTo(NOW);
            softly.assertThat(refund.getShippingFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(refund.getRemoteAreaFeeAmount()).isZero();
            softly.assertThat(refund.getDeductedShippingFeeAmount()).isZero();
        });
    }

    @Test
    void 승인_때_배송비가_모자라면_사유와_함께_자동_거절한다() {
        // given
        Refund refund = Refund.request(10L, 101L, 1, BUYER_ID, SELLER_ID, 55L, "양말", "흰색", 1_000L, null);
        givenLockedBundle(refund);

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, cheapItemInFreeBundle());

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("REJECTED");
            softly.assertThat(result.rejectReason()).isEqualTo(RefundErrorCode.PARTIAL_CANCEL_NOT_ALLOWED.getMessage());
            softly.assertThat(result.decidedAt()).isEqualTo(NOW);
            softly.assertThat(refund.getActiveOrderItemId()).isNull();
            softly.assertThat(refund.getDeductedShippingFeeAmount()).isZero();
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
    }

    @Test
    void 환불과_다른_주문_상품_정보로_승인하면_예외를_던지고_바꾸지_않는다() {
        // given
        Refund refund = requested();
        givenLockedBundle(refund);
        RefundableOrderItem otherItem = orderItem(102L, 10_000L, 2,
                new BundleShipping(0, 0, 3_000L, 50_000L, List.of(new BundleItem(102L, 10_000L, 2))));

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, otherItem))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("환불과 다른 주문 상품 정보로 승인할 수 없습니다. refundId=7");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 남의_상품_요청을_승인하면_없는_요청처럼_막고_바꾸지_않는다() {
        // given
        Refund refund = requested();
        given(refundRepository.findLockTargetIds(10L, OTHER_SELLER_ID, REFUND_ID, LOCK_TARGET_STATUSES)).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, OTHER_SELLER_ID, socks("PREPARING", 2)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 없는_요청을_승인하면_막는다() {
        // given
        given(refundRepository.findLockTargetIds(10L, SELLER_ID, REFUND_ID, LOCK_TARGET_STATUSES)).willReturn(List.of(99L));

        // when & then
        assertThatThrownBy(() -> sut.approve(REFUND_ID, SELLER_ID, socks("PREPARING", 2)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
        then(refundRepository).should(never()).findByIdForUpdate(any());
    }

    // 이번 환불 행만 잠그면 같은 묶음의 동시 승인이 서로를 못 본다(#411). 묶음의 진행 중 행을 ID 오름차순으로 한 건씩 잠그는 것이
    // 요구사항이라(순서가 다르면 교착 상태) 호출 순서를 검증한다
    @Test
    void 승인하면_같은_묶음의_진행_중_환불을_ID_순서로_한_건씩_잠근다() {
        // given
        Refund refund = requested();
        ReflectionTestUtils.setField(refund, "id", REFUND_ID);
        Refund other = Refund.request(10L, 102L, 1, BUYER_ID, SELLER_ID, 56L, "모자", "검정", 5_000L, null);
        ReflectionTestUtils.setField(other, "id", 3L);
        given(refundRepository.findLockTargetIds(10L, SELLER_ID, REFUND_ID, LOCK_TARGET_STATUSES))
                .willReturn(List.of(3L, REFUND_ID));
        given(refundRepository.findByIdForUpdate(3L)).willReturn(Optional.of(other));
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when
        RefundResult result = sut.approve(REFUND_ID, SELLER_ID, socks("PREPARING", 2));

        // then
        assertThat(result.refundId()).isEqualTo(REFUND_ID);
        InOrder lockOrder = inOrder(refundRepository);
        lockOrder.verify(refundRepository).findByIdForUpdate(3L);
        lockOrder.verify(refundRepository).findByIdForUpdate(REFUND_ID);
    }

    @Test
    void 거절하면_사유를_엔티티에_넘긴다() {
        // given
        Refund refund = requested();
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when
        RefundResult result = sut.reject(REFUND_ID, SELLER_ID, "이미 포장을 마쳤습니다.");

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("REJECTED");
            softly.assertThat(result.rejectReason()).isEqualTo("이미 포장을 마쳤습니다.");
            softly.assertThat(result.decidedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 남의_상품_요청을_거절하면_없는_요청처럼_막고_바꾸지_않는다() {
        // given
        Refund refund = requested();
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when & then
        assertThatThrownBy(() -> sut.reject(REFUND_ID, OTHER_SELLER_ID, "사유"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 철회하면_지금_시각으로_철회가_된다() {
        // given
        Refund refund = requested();
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when
        RefundResult result = sut.withdraw(REFUND_ID, BUYER_ID);

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("WITHDRAWN");
            softly.assertThat(result.decidedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 남의_요청을_철회하면_없는_요청처럼_막고_바꾸지_않는다() {
        // given
        Refund refund = requested();
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when & then
        assertThatThrownBy(() -> sut.withdraw(REFUND_ID, OTHER_BUYER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 없는_요청을_철회하면_막는다() {
        // given
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.withdraw(REFUND_ID, BUYER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
    }

    private static Refund requested() {
        return Refund.request(10L, 101L, 2, BUYER_ID, SELLER_ID, 55L, "양말", "흰색", 10_000L, null);
    }

    private static RefundRequestCommand command(int quantity) {
        return new RefundRequestCommand(BUYER_ID, 10L, 101L, quantity, "옵션을 잘못 골랐습니다.");
    }

    private static RefundableOrderItem socks(String status, int boughtQuantity) {
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(101L, 10_000L, boughtQuantity)));
        return new RefundableOrderItem(10L, 101L, BUYER_ID, 5L, 55L, "양말", "흰색", status, 10_000L, boughtQuantity, bundle);
    }

    // 승인은 잠글 ID를 읽고 한 건씩 잠가 이번 환불을 ID로 찾는다. 저장 전 엔티티라 ID를 직접 넣는다
    private void givenLockedBundle(Refund refund) {
        ReflectionTestUtils.setField(refund, "id", REFUND_ID);
        given(refundRepository.findLockTargetIds(10L, SELLER_ID, REFUND_ID, LOCK_TARGET_STATUSES))
                .willReturn(List.of(REFUND_ID));
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));
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
