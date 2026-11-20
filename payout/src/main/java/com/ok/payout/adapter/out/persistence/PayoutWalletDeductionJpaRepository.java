package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutWalletDeductionRepository;
import com.ok.payout.domain.PayoutWalletDeduction;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 지갑 차감 기록 저장소 출력 포트({@link PayoutWalletDeductionRepository})의 JPA 구현.
 */
public interface PayoutWalletDeductionJpaRepository
        extends JpaRepository<PayoutWalletDeduction, Long>, PayoutWalletDeductionRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    <S extends PayoutWalletDeduction> S save(S deduction);

    // 테스트 검증용 (운영 코드에서는 사용하지 않음)
    // 운영 코드는 #391부터 저장된 금액이 필요해 포트의 findByOrderId를 씀
    boolean existsByOrderId(Long orderId);
}
