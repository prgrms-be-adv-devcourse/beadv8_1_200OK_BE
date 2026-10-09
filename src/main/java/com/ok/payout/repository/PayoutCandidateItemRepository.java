package com.ok.payout.repository;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayoutCandidateItemRepository extends JpaRepository<PayoutCandidateItem, Long> {

    boolean existsByOrderItemIdAndEventType(Long orderItemId, PayoutEventType eventType);
}