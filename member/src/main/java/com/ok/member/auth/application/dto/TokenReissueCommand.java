package com.ok.member.auth.application.dto;

public record TokenReissueCommand(String refreshToken) {

    /**
     * Refresh Token 원문의 무작위 바이트 수. 32바이트 = 256비트로, 추측이 현실적으로 불가능한 크기다.
     * 발급({@code RefreshTokenService})과 요청 검증({@code TokenReissueRequest})이 같은 형식을 쓰도록 여기에 둔다.
     * 입력 어댑터는 서비스 구현을 알면 안 되므로 서비스가 아니라 입력 DTO에 둔다.
     */
    public static final int REFRESH_TOKEN_BYTES = 32;

    /**
     * Refresh Token 원문의 길이. 패딩 없는 Base64URL은 3바이트를 4자로 바꾸므로 ceil(바이트 수 * 4 / 3)자다 (32바이트 → 43자).
     * {@link #REFRESH_TOKEN_BYTES}에서 계산해, 바이트 수를 바꿔도 따로 고칠 필요가 없다.
     * 요청 검증({@code TokenReissueRequest})이 이 값을 기준으로 길이를 제한한다.
     */
    public static final int REFRESH_TOKEN_LENGTH = (REFRESH_TOKEN_BYTES * 4 + 2) / 3;

    /**
     * 토큰은 그 자체로 인증 수단이라 로그에 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "TokenReissueCommand[refreshToken=****]";
    }

}
