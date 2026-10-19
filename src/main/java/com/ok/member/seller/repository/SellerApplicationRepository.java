package com.ok.member.seller.repository;

import com.ok.member.seller.domain.SellerApplication;
import com.ok.member.seller.domain.SellerApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SellerApplicationRepository extends JpaRepository<SellerApplication, Long> {

    // 같은 일시에 들어온 신청도 순서가 정해지도록 id로 한 번 더 정렬한다
    List<SellerApplication> findAllByMemberIdOrderByAppliedAtDescIdDesc(Long memberId);

    Optional<SellerApplication> findByIdAndMemberId(Long id, Long memberId);

    Optional<SellerApplication> findFirstByMemberIdAndStatusIn(
            Long memberId, Collection<SellerApplicationStatus> statuses
    );

    boolean existsByBusinessNumberAndStatusIn(String businessNumber, Collection<SellerApplicationStatus> statuses);

    boolean existsByMemberIdAndStatus(Long memberId, SellerApplicationStatus status);

}
