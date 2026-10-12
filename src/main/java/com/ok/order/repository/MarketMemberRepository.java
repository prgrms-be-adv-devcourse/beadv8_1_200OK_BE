package com.ok.order.repository;

import com.ok.order.domain.MarketMember;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketMemberRepository extends JpaRepository<MarketMember, Long> {
}
