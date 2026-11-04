package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutItemRepository;
import com.ok.payout.domain.PayoutItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 정산 항목 저장소 출력 포트({@link PayoutItemRepository})의 JPA 구현.
 * 테스트가 findAll() 등 JpaRepository 기본 메서드로 결과를 확인한다.
 */
public interface PayoutItemJpaRepository extends JpaRepository<PayoutItem, Long>, PayoutItemRepository {
}
