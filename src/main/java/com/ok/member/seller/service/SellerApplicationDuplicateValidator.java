package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.domain.SellerApplicationStatus;
import com.ok.member.seller.exception.SellerErrorCode;
import com.ok.member.seller.repository.SellerApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SellerApplicationDuplicateValidator {

    /**
     * 마이그레이션의 unique 제약 이름 -> 에러코드. 위에서부터 확인하는 우선순위 목록이다.
     * MySQL은 위반한 키 하나만 메시지에 담지만, 판별 결과가 순서에 따라 달라지지 않도록 순서를 고정한다.
     * 회원 제약을 먼저 둔다. 같은 회원이 이미 신청했다는 쪽이 사용자에게 더 직접적인 원인이다.
     */
    private static final List<Map.Entry<String, SellerErrorCode>> UNIQUE_CONSTRAINTS = List.of(
            Map.entry("uk_seller_application_active_member_id", SellerErrorCode.SELLER_APPLICATION_IN_PROGRESS),
            Map.entry("uk_seller_application_active_business_number", SellerErrorCode.DUPLICATE_BUSINESS_NUMBER)
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
     * <p>제약 이름은 DB 예외 메시지에서 찾는다
     * MySQL 버전이 바뀌어 메시지 형식이 달라지면 깨질 수 있어, 통합 테스트가 실제 MySQL 예외로 이 변환을 확인한다.
     *
     * <p>회원 제약 위반은 진행 중 신청 오류로 바꾼다. 동시에 들어온 두 신청은 둘 다 심사 대기라 승인된 경우가 없다.
     */
    public RuntimeException toDuplicateException(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());

        return UNIQUE_CONSTRAINTS.stream()
                .filter(entry -> message.contains(entry.getKey()))
                .<RuntimeException>map(entry -> new RestApiException(entry.getValue()))
                .findFirst()
                .orElse(e);
    }

}
