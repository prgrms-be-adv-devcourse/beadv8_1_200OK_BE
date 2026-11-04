package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundDecision;
import com.ok.refund.service.dto.RefundDecisionCommand;
import com.ok.refund.service.dto.RefundRequestCommand;
import com.ok.refund.service.dto.RefundResult;
import com.ok.refund.service.dto.RefundableOrderItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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

    @Test
    void 승인하면_지금_시각으로_처리_중이_된다() {
        // given
        Refund refund = requested();
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when
        RefundResult result = sut.decide(new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.APPROVE, null));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("PROCESSING");
            softly.assertThat(result.decidedAt()).isEqualTo(NOW);
            softly.assertThat(refund.getStatus()).isEqualTo(RefundStatus.PROCESSING);
        });
    }

    @Test
    void 거절하면_사유를_엔티티에_넘긴다() {
        // given
        Refund refund = requested();
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when
        RefundResult result = sut.decide(
                new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.REJECT, "이미 포장을 마쳤습니다."));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("REJECTED");
            softly.assertThat(result.rejectReason()).isEqualTo("이미 포장을 마쳤습니다.");
            softly.assertThat(result.decidedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 남의_상품_요청을_결정하면_없는_요청처럼_막고_바꾸지_않는다() {
        // given
        Refund refund = requested();
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when & then
        assertThatThrownBy(() -> sut.decide(
                new RefundDecisionCommand(REFUND_ID, OTHER_SELLER_ID, RefundDecision.APPROVE, null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 없는_요청을_결정하면_막는다() {
        // given
        given(refundRepository.findByIdForUpdate(REFUND_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.decide(
                new RefundDecisionCommand(REFUND_ID, SELLER_ID, RefundDecision.APPROVE, null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
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
        return new RefundableOrderItem(10L, 101L, BUYER_ID, 5L, 55L, "양말", "흰색", status, 10_000L, boughtQuantity);
    }
}
