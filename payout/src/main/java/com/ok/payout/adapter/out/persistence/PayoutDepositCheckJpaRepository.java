package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutDepositCheckRepository;
import com.ok.payout.domain.PayoutDepositCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 정산·지갑 대사 줄 저장소 출력 포트({@link PayoutDepositCheckRepository})의 JPA 구현 (#619).
 * 조회 메서드는 포트에 적은 이름으로 Spring Data가 만든다.
 */
public interface PayoutDepositCheckJpaRepository extends JpaRepository<PayoutDepositCheck, Long>, PayoutDepositCheckRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    <S extends PayoutDepositCheck> List<S> saveAll(Iterable<S> checks);
}
