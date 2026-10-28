package com.ok.member.seller.repository;

import com.ok.member.seller.domain.SellerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SellerProfileRepository extends JpaRepository<SellerProfile, Long> {

    Optional<SellerProfile> findByMemberId(Long memberId);

    boolean existsByMemberId(Long memberId);

}
