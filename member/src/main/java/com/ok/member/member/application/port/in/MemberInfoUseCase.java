package com.ok.member.member.application.port.in;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.application.dto.MemberInfoResult;
import com.ok.member.member.application.dto.MemberInfoUpdateCommand;

/**
 * 회원 본인의 정보 조회·수정 유스케이스.
 */
public interface MemberInfoUseCase {

    /**
     * 회원 본인의 정보를 반환한다.
     *
     * @throws RestApiException 회원이 없거나 탈퇴했으면 {@code MEMBER_NOT_FOUND}
     */
    MemberInfoResult getMyInfo(Long memberId);

    /**
     * 회원 본인의 이름과 닉네임을 수정한다. 닉네임 중복은 허용한다.
     *
     * @throws RestApiException 회원이 없거나 탈퇴했으면 {@code MEMBER_NOT_FOUND}
     */
    MemberInfoResult updateMyInfo(Long memberId, MemberInfoUpdateCommand command);

}
