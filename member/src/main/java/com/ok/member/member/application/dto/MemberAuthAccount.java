package com.ok.member.member.application.dto;

/**
 * 토큰을 발급할지 정하는 데 필요한 회원 계정 상태를 담는다.
 * 상태는 회원 도메인이 판단한 결과만 넘기고 회원 유형·상태 enum은 넘기지 않아, 인증이 판단 규칙을 따로 갖지 않게 한다.
 *
 * @param passwordHash 지금 저장된 비밀번호 해시. 비밀번호를 확인한 뒤 바뀌었는지 비교하는 데 쓴다
 */
public record MemberAuthAccount(
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
        return "MemberAuthAccount[memberId=%d, admin=%s, withdrawn=%s, suspended=%s, passwordHash=****]"
                .formatted(memberId, admin, withdrawn, suspended);
    }

}
