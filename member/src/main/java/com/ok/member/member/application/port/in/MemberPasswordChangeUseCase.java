package com.ok.member.member.application.port.in;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.application.dto.PasswordChangeCommand;

/**
 * 회원 본인의 비밀번호 변경 유스케이스.
 */
public interface MemberPasswordChangeUseCase {

    /**
     * 현재 비밀번호를 확인하고 새 비밀번호로 바꾼다. 비밀번호를 바꾸면 토큰 버전이 올라 그 전에 발급된 토큰은 거부된다.
     *
     * @throws RestApiException 회원이 없거나 탈퇴했으면 {@code MEMBER_NOT_FOUND},
     *                          현재 비밀번호가 틀리면 {@code INVALID_CURRENT_PASSWORD},
     *                          새 비밀번호가 현재와 같으면 {@code SAME_AS_CURRENT_PASSWORD},
     *                          확인하는 사이 다른 요청이 비밀번호를 먼저 바꿨으면 {@code PASSWORD_CHANGED_CONCURRENTLY}
     */
    void changePassword(Long memberId, PasswordChangeCommand command);

}
