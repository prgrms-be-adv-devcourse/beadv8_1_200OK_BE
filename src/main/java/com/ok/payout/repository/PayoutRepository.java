package com.ok.payout.repository;

import com.ok.payout.domain.Payout;

import org.springframework.data.repository.Repository;



public interface PayoutRepository extends Repository<Payout, Long> {

    Payout save(Payout item);



}
