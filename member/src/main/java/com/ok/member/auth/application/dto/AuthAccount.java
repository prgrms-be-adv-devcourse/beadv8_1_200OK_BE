package com.ok.member.auth.application.dto;

/**
 * 토큰을 발급할지 정하는 데 필요한 회원 계정 상태를 담는다.
 * 회원 하위 도메인의 타입을 그대로 쓰지 않고 인증이 자기 타입을 가져, 회원 쪽 연동 방식이 바뀌어도 서비스가 바뀌지 않게 한다.
 *
 * @param passwordHash 지금 저장된 비밀번호 해시. 비밀번호를 확인한 뒤 바뀌었는지 비교하는 데 쓴다
 */
public record AuthAccount(
        Long memberId,
        boolean admin,
        boolean withdrawn,
        boolean suspended,
        String passwordHash
) {

    /**
     * 비밀번호 해시가 로그에 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "AuthAccount[memberId=%d, admin=%s, withdrawn=%s, suspended=%s, passwordHash=****]"
                .formatted(memberId, admin, withdrawn, suspended);
    }

}
