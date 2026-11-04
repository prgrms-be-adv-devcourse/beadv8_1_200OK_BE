package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutItem;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface PayoutItemRepository extends Repository<PayoutItem, Long> {

    List<PayoutItem> findAll();
}
