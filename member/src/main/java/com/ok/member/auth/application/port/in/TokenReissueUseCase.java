package com.ok.member.auth.application.port.in;

import com.ok.member.auth.application.dto.TokenReissueCommand;
import com.ok.member.auth.application.dto.TokenResult;

/**
 * Refresh Token으로 새 Access Token과 Refresh Token을 발급하는 유스케이스.
 */
public interface TokenReissueUseCase {

    /**
     * Refresh Token으로 새 토큰을 발급한다. 쓴 Refresh Token은 다시 쓸 수 없다.
     *
     * @throws com.ok.common.exception.RestApiException 없거나 만료되었거나 이미 사용한 토큰이면, 또는 회원이 없거나 탈퇴했으면
     *                                                  {@code INVALID_REFRESH_TOKEN}, 정지된 회원이면 {@code MEMBER_SUSPENDED}
     */
    TokenResult reissue(TokenReissueCommand command);

}
