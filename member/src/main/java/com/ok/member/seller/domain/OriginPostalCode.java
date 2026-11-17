package com.ok.member.seller.domain;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.exception.SellerErrorCode;

import java.util.regex.Pattern;

/**
 * 판매자 출고지 우편번호 규칙. 판매자 신청(처음 값)과 프로필(이후 변경)이 같은 규칙을 쓰도록 한곳에 둔다 (#587).
 * 국가기본구역 우편번호(5자리 숫자)이고, 요청 DTO들과 같은 규칙이다.
 */
public final class OriginPostalCode {

    /** DB 컬럼 길이(VARCHAR(5))와 같다 */
    public static final int LENGTH = 5;

    private static final Pattern FORMAT = Pattern.compile("\\d{5}");

    private OriginPostalCode() {
    }

    /**
     * 앞뒤 공백을 지운 값이 5자리 숫자인지 확인하고 그 값을 돌려준다.
     *
     * @throws RestApiException 비었거나 5자리 숫자가 아니면 {@code INVALID_ORIGIN_POSTAL_CODE}
     */
    public static String require(String postalCode) {
        String stripped = postalCode == null ? null : postalCode.strip();
        if (stripped == null || !FORMAT.matcher(stripped).matches()) {
            throw new RestApiException(SellerErrorCode.INVALID_ORIGIN_POSTAL_CODE);
        }
        return stripped;
    }
}
