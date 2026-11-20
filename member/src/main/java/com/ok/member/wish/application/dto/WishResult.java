package com.ok.member.wish.application.dto;

import java.time.LocalDateTime;

/**
 * 찜 목록의 한 항목. 상품 카드 정보는 담지 않는다. 프론트가 상품 카드 조회 API(#816)로 붙인다.
 */
public record WishResult(
        Long productId,
        LocalDateTime wishedAt
) {
}
