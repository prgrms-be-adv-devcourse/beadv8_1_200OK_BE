package com.ok.member.member.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.domain.Member;
import com.ok.member.member.exception.MemberErrorCode;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.member.service.dto.MemberInfoResult;
import com.ok.member.member.service.dto.MemberInfoUpdateCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인한 회원 본인의 정보 조회·수정을 처리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;

    /**
     * 회원 본인의 정보를 반환한다.
     *
     * @throws RestApiException 회원이 없거나 탈퇴했으면 {@code MEMBER_NOT_FOUND}
     */
    public MemberInfoResult getMyInfo(Long memberId) {
        return toResult(findMember(memberId));
    }

    /**
     * 회원 본인의 이름과 닉네임을 수정한다. 닉네임 중복은 허용한다.
     *
     * @throws RestApiException 회원이 없거나 탈퇴했으면 {@code MEMBER_NOT_FOUND}
     */
    @Transactional
    public MemberInfoResult updateMyInfo(Long memberId, MemberInfoUpdateCommand command) {
        Member member = findMember(memberId);
        member.updateInfo(command.name(), command.nickname());
        return toResult(member);
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
