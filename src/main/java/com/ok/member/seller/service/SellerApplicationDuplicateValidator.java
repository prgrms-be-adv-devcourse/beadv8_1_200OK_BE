package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.domain.SellerApplicationStatus;
import com.ok.member.seller.exception.SellerErrorCode;
import com.ok.member.seller.repository.SellerApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 판매자 신청의 중복 규칙 담당.
 * 거절되지 않은(진행 중·승인) 신청은 회원당 1건, 사업자등록번호당 1건만 허용한다.
 */
@Component
@RequiredArgsConstructor
public class SellerApplicationDuplicateValidator {

    /** 마이그레이션의 unique 제약 이름 -> 에러코드 */
    private static final Map<String, SellerErrorCode> UNIQUE_CONSTRAINTS = Map.of(
            "uk_seller_application_active_member_id", SellerErrorCode.SELLER_APPLICATION_IN_PROGRESS,
            "uk_seller_application_active_business_number", SellerErrorCode.DUPLICATE_BUSINESS_NUMBER
    );

    private final SellerApplicationRepository sellerApplicationRepository;

    /**
     * 저장 전에 같은 회원의 진행 중·승인 신청과, 같은 사업자등록번호를 쓰는 신청이 있는지 확인한다.
     *
     * @throws RestApiException 심사 대기·심사 중인 신청이 있으면 {@code SELLER_APPLICATION_IN_PROGRESS},
     *                          이미 승인됐으면 {@code ALREADY_SELLER},
     *                          진행 중·승인 신청이 같은 사업자등록번호를 쓰면 {@code DUPLICATE_BUSINESS_NUMBER}
     */
    public void validate(Long memberId, String businessNumber) {
        Set<SellerApplicationStatus> activeStatuses = SellerApplicationStatus.activeStatuses();

        sellerApplicationRepository.findFirstByMemberIdAndStatusIn(memberId, activeStatuses)
                .ifPresent(application -> {
                    throw new RestApiException(application.isApproved()
                            ? SellerErrorCode.ALREADY_SELLER
                            : SellerErrorCode.SELLER_APPLICATION_IN_PROGRESS);
                });

        if (sellerApplicationRepository.existsByBusinessNumberAndStatusIn(businessNumber, activeStatuses)) {
            throw new RestApiException(SellerErrorCode.DUPLICATE_BUSINESS_NUMBER);
        }
    }

    /**
     * validate 통과 후 동시 요청(같은 회원의 중복 클릭, 같은 사업자등록번호의 동시 신청)으로 중복이 생기면
     * unique 제약 이름으로 에러를 찾아 변환한다. unique 제약이 아닌 무결성 위반은 원래 예외를 그대로 반환한다.
     *
     * <p>회원 제약 위반은 진행 중 신청 오류로 바꾼다. 동시에 들어온 두 신청은 둘 다 심사 대기라 승인된 경우가 없다.
     */
    public RuntimeException toDuplicateException(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());

        return UNIQUE_CONSTRAINTS.entrySet().stream()
                .filter(entry -> message.contains(entry.getKey()))
                .<RuntimeException>map(entry -> new RestApiException(entry.getValue()))
                .findFirst()
                .orElse(e);
    }

}
