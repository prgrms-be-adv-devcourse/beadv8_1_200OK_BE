package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutSystemDraftItemRepository;
import com.ok.payout.domain.PayoutSystemDraftItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 시스템 몫(수수료) draft 항목 저장소 출력 포트({@link PayoutSystemDraftItemRepository})의 JPA 구현.
 */
public interface PayoutSystemDraftItemJpaRepository
        extends JpaRepository<PayoutSystemDraftItem, Long>, PayoutSystemDraftItemRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    <S extends PayoutSystemDraftItem> List<S> saveAll(Iterable<S> items);
}
