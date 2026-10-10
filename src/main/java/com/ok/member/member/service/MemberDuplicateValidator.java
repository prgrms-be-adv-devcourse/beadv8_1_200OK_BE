package com.ok.member.member.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.domain.MemberErrorCode;
import com.ok.member.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 회원의 아이디, 이메일 중복 규칙 담당
 */
@Component
@RequiredArgsConstructor
public class MemberDuplicateValidator {

    /** 마이그레이션의 unique 제약 이름 -> 에러코드 */
    private static final Map<String, MemberErrorCode> UNIQUE_CONSTRAINTS = Map.of(
            "uk_member_login_id", MemberErrorCode.DUPLICATE_LOGIN_ID,
            "uk_member_email", MemberErrorCode.DUPLICATE_EMAIL
    );

    private final MemberRepository memberRepository;

    /**
     * 저장 전에 아이디, 이메일 중복을 확인한다.
     */
    public void validate(String loginId, String email) {
        if (memberRepository.existsByLoginId(loginId)) {
            throw new RestApiException(MemberErrorCode.DUPLICATE_LOGIN_ID);
        }
        if (memberRepository.existsByEmail(email)) {
            throw new RestApiException(MemberErrorCode.DUPLICATE_EMAIL);
        }
    }

    /**
     * validate 통과 후 동시 요청으로 인한 중복 발생 시 unique 제약 이름으로 에러를 찾아 변환한다.
     * unique 제약이 아닌 무결성 위반은 원래 예외를 그대로 반환한다.
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
