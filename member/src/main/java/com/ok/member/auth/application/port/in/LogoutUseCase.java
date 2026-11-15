package com.ok.member.auth.application.port.in;

import com.ok.member.auth.application.dto.LogoutCommand;

/**
 * Refresh Token의 세션을 끝내 그 기기에서 로그아웃하는 유스케이스.
 */
public interface LogoutUseCase {

    /**
     * Refresh Token의 세션을 끝낸다. 끝낸 뒤에는 그 토큰으로 재발급할 수 없고, 같은 회원의 다른 세션은 그대로 남는다.
     * 없거나 만료되었거나 이미 로그아웃한 토큰이어도 예외 없이 끝난다(멱등).
     * 이미 발급된 Access Token은 만료될 때까지 유효하다.
     */
    void logout(LogoutCommand command);

}
