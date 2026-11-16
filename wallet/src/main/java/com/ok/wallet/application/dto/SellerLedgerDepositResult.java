package com.ok.wallet.application.dto;

import java.time.LocalDateTime;

/**
 * 판매자 원장 한 건과 그 원장이 속한 판매자 지갑의 회원 ID를 함께 담는다.
 * 원장에는 지갑 ID만 있어 회원 ID를 따로 찾으면 원장마다 지갑을 다시 읽게 되므로, 조회에서 한 번에 채운다.
 *
 * @param memberId    원장이 속한 판매자 지갑의 회원 ID
 * @param depositedAt 원장이 생긴 시각(지갑이 입금한 시각)
 */
public record SellerLedgerDepositResult(
        String referenceKey,
        Long memberId,
        long amount,
        LocalDateTime depositedAt
) {
}
