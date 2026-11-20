package com.ok.member.member.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.application.dto.PasswordChangeCommand;
import com.ok.member.member.application.dto.TokenVersionChange;
import com.ok.member.member.application.port.in.MemberPasswordChangeUseCase;
import com.ok.member.member.application.port.out.TokenVersionRecorder;
import com.ok.member.member.exception.MemberErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 현재 비밀번호를 확인하고 새 비밀번호로 바꾼 뒤, 올린 토큰 버전을 토큰 검증기가 읽는 저장소에 남긴다.
 * bcrypt 비교와 인코딩(각각 수십~수백 ms) 동안 DB 커넥션을 잡지 않도록 트랜잭션 없이 실행하고,
 * DB 작업은 {@link MemberService}의 짧은 트랜잭션에 맡긴다. 로그인({@code LoginFacade})과 같은 구조이고,
 * 배경은 {@code docs/decisions/member/425-login-connection-deadlock.md}에 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberPasswordFacade implements MemberPasswordChangeUseCase {

    private final MemberService memberService;
    private final PasswordEncoder passwordEncoder;
    private final TokenVersionRecorder tokenVersionRecorder;
    private final TokenVersionRecordPolicy tokenVersionRecordPolicy;

    @Override
    public void changePassword(Long memberId, PasswordChangeCommand command) {
        String verifiedHash = memberService.findPasswordHash(memberId);
        if (!passwordEncoder.matches(command.currentPassword(), verifiedHash)) {
            throw new RestApiException(MemberErrorCode.INVALID_CURRENT_PASSWORD);
        }
        // 같은 비밀번호로 "바꾸면" 아무것도 바뀌지 않고 모든 기기의 세션만 끊긴다. 실수일 가능성이 커 막는다.
        // 현재 비밀번호를 확인한 뒤에 비교해, 틀린 현재 비밀번호로는 이 오류를 볼 수 없게 한다.
        // 현재 비밀번호가 해시와 맞는 것을 확인했으므로 bcrypt를 한 번 더 돌리지 않고 원문끼리 비교하면 된다
        if (command.newPassword().equals(command.currentPassword())) {
            throw new RestApiException(MemberErrorCode.SAME_AS_CURRENT_PASSWORD);
        }
        String encodedNewPassword = passwordEncoder.encode(command.newPassword());
        TokenVersionChange change = memberService.changePassword(memberId, verifiedHash, encodedNewPassword);
        // 서비스가 반환했다는 것은 커밋됐다는 뜻이라 이제 남긴다. 커밋 전에 남기면 롤백된 변경 때문에 그 회원의 토큰이 모두 거부된다
        record(change);
    }

    // 비밀번호는 이미 바뀌었으므로 저장소에 남기지 못해도 요청은 성공으로 끝낸다. Refresh Token은 DB 비교로 이미 막혔고,
    // 빠진 기록은 다시 채우기(TokenVersionResyncFacade)가 다음 실행에서 DB 값으로 메운다.
    // 저장소 장애(DataAccessException)만이 아니라 어떤 예외든 잡는다. 놓치면 커밋된 변경에 500이 나가 클라이언트가 실패로 알고 다시 시도한다
    private void record(TokenVersionChange change) {
        tokenVersionRecordPolicy.remainingTtl(change.changedAt()).ifPresent(ttl -> {
            try {
                tokenVersionRecorder.record(change.memberId(), change.tokenVersion(), ttl);
            } catch (DataAccessException e) {
                // 저장소 장애는 예상한 실패라 원인 한 줄이면 된다. 장애 동안 요청마다 스택이 쌓이지 않게 한다
                log.warn("비밀번호는 바뀌었지만 토큰 버전을 저장소에 남기지 못했다. 다시 채우기가 메운다. memberId={}, causeType={}, cause={}",
                        change.memberId(), e.getClass().getName(), e.getMessage());
            } catch (RuntimeException e) {
                // 그 밖의 예외는 버그라 원인을 찾을 수 있게 스택과 함께 남긴다. 응답은 위와 같은 이유로 성공으로 둔다.
                // 예외를 처리하고 계속 진행하므로 레벨은 ERROR가 아니라 WARN이다(서비스 로그 규칙)
                log.warn("비밀번호는 바뀌었지만 예상하지 못한 예외로 토큰 버전을 남기지 못했다. 다시 채우기가 메운다. memberId={}",
                        change.memberId(), e);
            }
        });
    }

}
