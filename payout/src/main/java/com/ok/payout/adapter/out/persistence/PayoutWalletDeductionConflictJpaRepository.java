package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutWalletDeductionConflictRepository;
import com.ok.payout.domain.PayoutWalletDeductionConflict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 지갑 차감 금액 충돌 기록 저장소 출력 포트({@link PayoutWalletDeductionConflictRepository})의 JPA 구현.
 */
public interface PayoutWalletDeductionConflictJpaRepository
        extends JpaRepository<PayoutWalletDeductionConflict, Long>, PayoutWalletDeductionConflictRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    <S extends PayoutWalletDeductionConflict> S save(S conflict);

    // 테스트 검증용 (운영 코드에서는 사용하지 않음): 주문의 충돌 기록 확인
    List<PayoutWalletDeductionConflict> findAllByOrderId(Long orderId);
}
