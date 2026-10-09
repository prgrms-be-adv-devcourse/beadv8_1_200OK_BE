package com.ok.payout.repository;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface PayoutCandidateItemRepository extends Repository<PayoutCandidateItem, Long> {

    boolean existsByOrderItemIdAndEventType(Long orderItemId, PayoutEventType eventType);

    PayoutCandidateItem save(PayoutCandidateItem item);

    List<PayoutCandidateItem> findAll();

    Optional<PayoutCandidateItem> findById(Long id);

    List<PayoutCandidateItem> findAllByOrderItemId(Long orderItemId);
}