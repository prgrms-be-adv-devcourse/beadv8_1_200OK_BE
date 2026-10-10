package com.ok.payout.repository;

import com.ok.payout.domain.Payout;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface PayoutRepository extends Repository<Payout, Long> {

    Payout save(Payout item);

    List<Payout> saveAll(Iterable<Payout> items);

    // 가장 최근 정산 1건
    Optional<Payout> findFirstByOrderByIdDesc();

}
