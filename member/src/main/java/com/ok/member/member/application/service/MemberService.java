package com.ok.member.member.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.application.dto.MemberInfoResult;
import com.ok.member.member.application.dto.MemberInfoUpdateCommand;
import com.ok.member.member.application.port.in.MemberInfoUseCase;
import com.ok.member.member.application.port.out.MemberRepository;
import com.ok.member.member.domain.Member;
import com.ok.member.member.exception.MemberErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인한 회원 본인의 정보 조회·수정을 처리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService implements MemberInfoUseCase {

    private final MemberRepository memberRepository;

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
