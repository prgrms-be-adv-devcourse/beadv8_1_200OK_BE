package com.ok.member.member.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.application.dto.PasswordChangeCommand;
import com.ok.member.member.application.port.in.MemberPasswordChangeUseCase;
import com.ok.member.member.exception.MemberErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 현재 비밀번호를 확인하고 새 비밀번호로 바꾼다.
 * bcrypt 비교와 인코딩(각각 수십~수백 ms) 동안 DB 커넥션을 잡지 않도록 트랜잭션 없이 실행하고,
 * DB 작업은 {@link MemberService}의 짧은 트랜잭션에 맡긴다. 로그인({@code LoginFacade})과 같은 구조이고,
 * 배경은 {@code docs/decisions/member/425-login-connection-deadlock.md}에 있다.
 */
@Component
@RequiredArgsConstructor
public class MemberPasswordFacade implements MemberPasswordChangeUseCase {

    private final MemberService memberService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void changePassword(Long memberId, PasswordChangeCommand command) {
        String verifiedHash = memberService.findPasswordHash(memberId);
        if (!passwordEncoder.matches(command.currentPassword(), verifiedHash)) {
            throw new RestApiException(MemberErrorCode.INVALID_CURRENT_PASSWORD);
        }
        String encodedNewPassword = passwordEncoder.encode(command.newPassword());
        memberService.changePassword(memberId, verifiedHash, encodedNewPassword);
    }

}
