package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.exception.SellerErrorCode;
import com.ok.member.seller.repository.SellerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 판매자 프로필의 중복 규칙 담당. 한 회원은 프로필(상점)을 하나만 가진다.
 */
@Component
@RequiredArgsConstructor
public class SellerProfileDuplicateValidator {

    /** 마이그레이션의 unique 제약 이름 */
    private static final String UNIQUE_MEMBER_ID = "uk_seller_profile_member_id";

    private final SellerProfileRepository sellerProfileRepository;

    /**
     * 저장 전에 이 회원의 프로필이 이미 있는지 확인한다.
     *
     * @throws RestApiException 이미 있으면 {@code SELLER_PROFILE_ALREADY_EXISTS}
     */
    public void validate(Long memberId) {
        if (sellerProfileRepository.existsByMemberId(memberId)) {
            throw new RestApiException(SellerErrorCode.SELLER_PROFILE_ALREADY_EXISTS);
        }
    }

    /**
     * validate 통과 후 동시 요청(등록 버튼 중복 클릭)으로 중복이 생기면 unique 제약 이름으로 판별해 변환한다.
     * unique 제약이 아닌 무결성 위반은 원래 예외를 그대로 반환한다.
     *
     * <p>제약 이름은 DB 예외 메시지에서 찾는다. MySQL 버전이 바뀌어 메시지 형식이 달라지면
     * 깨질 수 있어, 통합 테스트가 실제 MySQL 예외로 이 변환을 확인한다.
     *
     * <p>위반 후 프로필이 있는지 다시 조회해 판별하지 않는다. 제약 위반이 난 트랜잭션은 롤백 전용이 되고
     * Hibernate 세션도 더 쓸 수 없어, 같은 트랜잭션에서 다시 조회한 결과를 믿을 수 없다.
     */
    public RuntimeException toDuplicateException(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains(UNIQUE_MEMBER_ID)) {
            return new RestApiException(SellerErrorCode.SELLER_PROFILE_ALREADY_EXISTS);
        }
        return e;
    }

}
