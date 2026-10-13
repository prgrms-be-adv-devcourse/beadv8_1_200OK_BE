package com.ok.payout.repository;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PayoutCandidateItemRepository extends Repository<PayoutCandidateItem, Long> {

    boolean existsByOrderItemIdAndEventType(Long orderItemId, PayoutEventType eventType);

    PayoutCandidateItem save(PayoutCandidateItem item);

    Optional<PayoutCandidateItem> findById(Long id);

    List<PayoutCandidateItem> findAllByOrderItemId(Long orderItemId);

    // 구매 확정 중복 확인용 (평소 이벤트만 보려면 adjustmentId = 0)
    boolean existsByOrderItemIdAndEventTypeInAndAdjustmentId(Long orderItemId, Collection<PayoutEventType> eventTypes,
                                                             Long adjustmentId);

    List<PayoutCandidateItem> saveAll(Iterable<PayoutCandidateItem> items);
}