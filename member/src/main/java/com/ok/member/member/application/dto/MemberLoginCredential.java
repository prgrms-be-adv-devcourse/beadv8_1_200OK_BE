package com.ok.member.member.application.dto;

/**
 * 로그인 비밀번호 확인에 필요한 회원 ID와 비밀번호 해시를 담는다.
 * 회원 상태는 담지 않는다. 상태와 해시는 토큰을 발급하는 트랜잭션 안에서 {@link MemberAuthAccount}로 다시 읽어 확인한다.
 */
public record MemberLoginCredential(
        Long memberId,
        String passwordHash
) {

    /**
     * 비밀번호 해시가 로그에 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "MemberLoginCredential[memberId=%d, passwordHash=****]".formatted(memberId);
    }

}
