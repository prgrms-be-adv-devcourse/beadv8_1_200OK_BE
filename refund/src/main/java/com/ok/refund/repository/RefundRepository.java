package com.ok.refund.repository;

import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Page<Refund> findAllByBuyerId(Long buyerId, Pageable pageable);

    Page<Refund> findAllBySellerId(Long sellerId, Pageable pageable);

    Page<Refund> findAllBySellerIdAndStatus(Long sellerId, RefundStatus status, Pageable pageable);
}
