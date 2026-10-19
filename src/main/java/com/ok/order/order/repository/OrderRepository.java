package com.ok.order.order.repository;

import com.ok.order.order.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order> findByBuyerIdOrderByIdDesc(Long buyerId, Pageable pageable);
    Optional<Order> findByIdAndBuyerId(Long id, Long buyerId);
}
