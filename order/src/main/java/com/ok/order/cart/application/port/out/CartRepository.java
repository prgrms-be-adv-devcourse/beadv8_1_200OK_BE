package com.ok.order.cart.application.port.out;

import com.ok.order.cart.domain.Cart;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 장바구니 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface CartRepository {

    Optional<Cart> findByMemberId(Long memberId);

    /**
     * 장바구니가 없으면 저장하고, 이미 있으면 아무것도 하지 않는다.
     * 회원가입 리스너와 API 요청이 동시에 만들어도 유니크 제약 위반 예외가 나지 않아, 트랜잭션이 롤백 전용이 되지 않는다.
     *
     * @param now 생성 시각. 엔티티를 거치지 않고 저장해 {@code BaseTime}이 채우지 않으므로 직접 받는다
     */
    void insertIfAbsent(Long memberId, LocalDateTime now);

    /**
     * 공유 잠금으로 조회한다. 일반 조회와 달리 트랜잭션의 스냅샷이 아니라 최신 커밋 데이터를 읽어,
     * 다른 트랜잭션이 방금 만든 장바구니도 보인다.
     */
    Optional<Cart> findForShareByMemberId(Long memberId);
}
