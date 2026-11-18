package com.ok.member.member.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.application.dto.MemberAuthAccount;
import com.ok.member.member.application.dto.MemberInfoResult;
import com.ok.member.member.application.dto.MemberInfoUpdateCommand;
import com.ok.member.member.application.dto.MemberLoginCredential;
import com.ok.member.member.application.dto.TokenVersionChange;
import com.ok.member.member.application.port.in.MemberAuthAccountUseCase;
import com.ok.member.member.application.port.in.MemberInfoUseCase;
import com.ok.member.member.application.port.out.MemberRepository;
import com.ok.member.member.domain.Member;
import com.ok.member.member.exception.MemberErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 로그인한 회원 본인의 정보 조회·수정과 비밀번호 변경의 DB 작업, 인증이 토큰 발급에 쓰는 계정 조회를 처리한다.
 * 인증용 조회도 클래스의 읽기 전용 트랜잭션이라, 인증의 발급 트랜잭션 안에서 불리면 그 트랜잭션에 참여한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService implements MemberInfoUseCase, MemberAuthAccountUseCase {

    private final MemberRepository memberRepository;
    /** 현재 시각 기준. 테스트에서 고정할 수 있도록 주입받는다. (ClockConfig 참고) */
    private final Clock clock;

    @Override
    public MemberInfoResult getMyInfo(Long memberId) {
        return toResult(findMember(memberId));
    }

    @Override
    @Transactional
    public MemberInfoResult updateMyInfo(Long memberId, MemberInfoUpdateCommand command) {
        Member member = findMember(memberId);
        member.updateInfo(command.name(), command.nickname());
        return toResult(member);
    }

    @Override
    public Optional<MemberLoginCredential> findLoginCredential(String loginId) {
        return memberRepository.findByLoginId(loginId)
                .map(member -> new MemberLoginCredential(member.getId(), member.getPassword()));
    }

    @Override
    public Optional<MemberAuthAccount> findAuthAccount(Long memberId) {
        return memberRepository.findById(memberId)
                .map(member -> new MemberAuthAccount(member.getId(), member.isAdmin(), member.isWithdrawn(),
                        member.isSuspended(), member.getPassword(), member.getTokenVersion()));
    }

    /**
     * 비밀번호 확인에 쓸 지금 비밀번호 해시를 조회한다.
     * {@link #changePassword}와 이어 부르면 현재 비밀번호 확인 없이 비밀번호를 바꿀 수 있어, 확인을 맡는 같은 패키지의
     * {@link MemberPasswordFacade}만 부를 수 있게 입력 포트에 넣지 않는다.
     *
     * @throws RestApiException 회원이 없거나 탈퇴했으면 {@code MEMBER_NOT_FOUND}
     */
    String findPasswordHash(Long memberId) {
        return findMember(memberId).getPassword();
    }

    /**
     * 회원 행을 잠그고, 확인에 쓴 해시가 그대로일 때만 비밀번호를 바꾸고 토큰 버전을 올린다.
     * bcrypt 확인과 인코딩은 트랜잭션 밖({@link MemberPasswordFacade})에서 끝낸 뒤 부른다. 잠금을 쥔 동안 비용이 큰 작업을 하지 않기 위해서다.
     * 해시를 다시 비교하는 이유는 bcrypt 확인과 이 트랜잭션 사이에 다른 요청이 비밀번호를 바꿨을 수 있어서다.
     * 잠금 조회는 커밋된 최신 값을 읽으므로, 같은 회원의 동시 변경은 먼저 잠근 요청만 성공한다.
     *
     * @param verifiedHash       현재 비밀번호 확인에 쓴 해시
     * @param encodedNewPassword 인코딩된 새 비밀번호
     * @return 올린 토큰 버전과 변경 시각. 커밋된 뒤 토큰 검증기가 읽는 저장소에 남긴다
     * @throws RestApiException 회원이 없거나 탈퇴했으면 {@code MEMBER_NOT_FOUND},
     *                          해시가 그사이 바뀌었으면 {@code PASSWORD_CHANGED_CONCURRENTLY}
     */
    @Transactional
    TokenVersionChange changePassword(Long memberId, String verifiedHash, String encodedNewPassword) {
        Member member = memberRepository.findForUpdateById(memberId)
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new RestApiException(MemberErrorCode.MEMBER_NOT_FOUND));
        if (!Objects.equals(member.getPassword(), verifiedHash)) {
            throw new RestApiException(MemberErrorCode.PASSWORD_CHANGED_CONCURRENTLY);
        }
        member.changePassword(encodedNewPassword, LocalDateTime.now(clock));
        return toTokenVersionChange(member);
    }

    /**
     * 기준 시각 뒤에 토큰 버전을 올린 회원의 지금 버전을 조회한다. 저장소의 기록이 빠졌을 때 다시 채우는 데 쓴다.
     * 다시 채우기({@link TokenVersionResyncFacade})만 쓰는 조회라 입력 포트에 넣지 않는다.
     */
    List<TokenVersionChange> findTokenVersionChangesSince(LocalDateTime since) {
        return memberRepository.findTokenVersionChangesSince(since);
    }

    private static TokenVersionChange toTokenVersionChange(Member member) {
        return new TokenVersionChange(member.getId(), member.getTokenVersion(), member.getTokenVersionChangedAt());
    }

    private Member findMember(Long memberId) {
        // 탈퇴한 뒤에도 만료 전 Access Token은 남아 있을 수 있다. 로그인·재발급(AuthService)과 같이 없는 회원으로 본다
        return memberRepository.findById(memberId)
                .filter(member -> !member.isWithdrawn())
                .orElseThrow(() -> new RestApiException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    private MemberInfoResult toResult(Member member) {
        return new MemberInfoResult(
                member.getLoginId(),
                member.getEmail(),
                member.getName(),
                member.getNickname()
        );
    }

}
