package com.ok.member.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 토큰 원문을 저장·조회용 해시로 변환한다.
 *
 * <p><b>왜 bcrypt가 아니라 SHA-256인가</b>
 * <ul>
 *   <li>bcrypt는 사람이 정한 짧은 비밀번호를 무차별 대입으로부터 지키기 위해 일부러 느리게 만든 해시다.
 *       Refresh Token은 32바이트(256비트) 난수라 경우의 수가 너무 커서, 빠른 해시로도 대입 공격이 불가능하다.</li>
 *   <li>bcrypt는 매번 다른 salt를 섞어 같은 입력도 다른 결과가 나온다. 그러면 "해시로 DB를 조회"할 수 없다.
 *       SHA-256은 같은 입력이면 항상 같은 결과라, 클라이언트가 보낸 토큰을 해시해서 바로 찾을 수 있다.</li>
 * </ul>
 */
public class TokenHasher {

    private static final String ALGORITHM = "SHA-256";
    private static final HexFormat HEX = HexFormat.of();

    private TokenHasher() {
    }

    /**
     * 토큰의 SHA-256 해시를 16진수 소문자 64자로 반환한다.
     */
    public static String sha256Hex(String token) {
        Objects.requireNonNull(token, "token은 null일 수 없습니다.");
        try {
            // MessageDigest는 스레드에 안전하지 않으므로 호출마다 새로 만든다.
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 Java 구현체가 반드시 제공해야 하는 알고리즘이라 실제로는 발생하지 않는다.
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

}
