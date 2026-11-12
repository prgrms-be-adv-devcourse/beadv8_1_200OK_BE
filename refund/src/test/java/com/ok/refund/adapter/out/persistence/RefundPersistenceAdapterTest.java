package com.ok.refund.adapter.out.persistence;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.Refund;
import com.ok.refund.exception.RefundErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

// 진행 중 중복·교착 상태를 에러 코드로 바꾸는 규칙은 서비스에 있다가 #481에서 이 어댑터로 옮겼다(RefundServiceTest에서 옮긴 테스트).
// 실제 DB에서 만들기 어려운 예외 모양(교착, 잠금 대기 시간 초과, 다른 제약)을 그대로 넣어 갈래만 확인한다
@ExtendWith(MockitoExtension.class)
class RefundPersistenceAdapterTest {

    @Mock
    RefundJpaRepository refundJpaRepository;
    @Mock
    EntityManager entityManager;

    RefundPersistenceAdapter sut;

    @BeforeEach
    void setUp() {
        sut = new RefundPersistenceAdapter(refundJpaRepository, entityManager);
    }

    @Test
    void 진행_중_UNIQUE에_걸리면_진행_중_에러로_바꾼다() {
        // given
        given(refundJpaRepository.saveAndFlush(any(RefundJpaEntity.class))).willThrow(new DataIntegrityViolationException("중복",
                new SQLException("Duplicate entry '101' for key 'refund.uk_refund_active_order_item_id'")));

        // when & then
        assertThatThrownBy(() -> sut.saveNew(requested()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS));
    }

    @Test
    void 다른_무결성_오류는_그대로_던진다() {
        // given
        DataIntegrityViolationException other = new DataIntegrityViolationException("다른 제약",
                new SQLException("Check constraint 'ck_refund_quantity' is violated."));
        given(refundJpaRepository.saveAndFlush(any(RefundJpaEntity.class))).willThrow(other);

        // when & then
        assertThatThrownBy(() -> sut.saveNew(requested()))
                .isSameAs(other);
    }

    // 철회 커밋 순간 같은 상품 새 요청 둘이 겹치면 MySQL이 한쪽을 교착 상태로 되돌린다. 그 쪽도 진행 중 중복으로 본다.
    // 원인 예외 모양은 재현 때 실제로 받은 값(CannotAcquireLockException, SQLState 40001, 1213)과 같게 만든다
    @Test
    void 저장이_교착_상태로_되돌려지면_진행_중_에러로_바꾼다() {
        // given
        given(refundJpaRepository.saveAndFlush(any(RefundJpaEntity.class))).willThrow(new CannotAcquireLockException("교착",
                new SQLException("Deadlock found when trying to get lock; try restarting transaction", "40001", 1213)));

        // when & then
        assertThatThrownBy(() -> sut.saveNew(requested()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS));
    }

    @Test
    void 잠금_대기_시간_초과는_중복이_아니라_그대로_던진다() {
        // given
        CannotAcquireLockException timeout = new CannotAcquireLockException("대기 시간 초과",
                new SQLException("Lock wait timeout exceeded; try restarting transaction", "HY000", 1205));
        given(refundJpaRepository.saveAndFlush(any(RefundJpaEntity.class))).willThrow(timeout);

        // when & then
        assertThatThrownBy(() -> sut.saveNew(requested()))
                .isSameAs(timeout);
    }

    // ID가 없으면 어느 행에 쓸지 모른다. 새 환불을 update로 저장하려 한 부르는 쪽 버그라 표준 예외로 드러낸다
    @Test
    void 저장하지_않은_환불을_update하면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> sut.update(requested()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("저장하지 않은 환불은 update할 수 없습니다. saveNew로 저장해야 합니다.");
    }

    private static Refund requested() {
        return Refund.request(10L, 101L, 1, 1L, 5L, 55L, "감자", "1kg", 10_000L, null);
    }
}
