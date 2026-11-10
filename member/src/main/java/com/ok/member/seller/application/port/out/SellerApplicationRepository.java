package com.ok.member.seller.application.port.out;

import com.ok.member.seller.domain.SellerApplication;
import com.ok.member.seller.domain.SellerApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 판매자 신청 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * Spring Data에서 물려받던 메서드도 서비스가 쓰는 것만 같은 시그니처로 둔다. 구현체가 Spring Data 프록시라 동작은 그대로다.
 */
public interface SellerApplicationRepository {

    Optional<SellerApplication> findById(Long id);

    // 같은 일시에 들어온 신청도 순서가 정해지도록 id로 한 번 더 정렬한다
    List<SellerApplication> findAllByMemberIdOrderByAppliedAtDescIdDesc(Long memberId);

    Optional<SellerApplication> findByIdAndMemberId(Long id, Long memberId);

    Optional<SellerApplication> findFirstByMemberIdAndStatusIn(
            Long memberId, Collection<SellerApplicationStatus> statuses
    );

    boolean existsByBusinessNumberAndStatusIn(String businessNumber, Collection<SellerApplicationStatus> statuses);

    boolean existsByMemberIdAndStatus(Long memberId, SellerApplicationStatus status);

    // 상태로 거르지 않는 목록
    Page<SellerApplication> findAll(Pageable pageable);

    Page<SellerApplication> findAllByStatus(SellerApplicationStatus status, Pageable pageable);

    <S extends SellerApplication> S saveAndFlush(S application);

    // 낙관적 락(@Version) 충돌을 커밋 전에 드러내 에러 코드로 바꾸려고 쓴다
    void flush();

}
