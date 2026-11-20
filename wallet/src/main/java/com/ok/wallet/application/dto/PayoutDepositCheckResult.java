package com.ok.wallet.application.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 정산 입금 확인 한 묶음의 답. 요청한 정산마다 원장에서 찾은 사실만 담고, 맞는지 판정은 정산이 한다.
 *
 * @param items 요청한 순서 그대로의 정산별 답
 */
public record PayoutDepositCheckResult(
        Long checkId,
        List<Item> items
) {

    /**
     * 정산 하나의 답. 원장에 없으면 판매자·금액·입금 시각은 null이다.
     *
     * @param payeeId     원장이 속한 판매자 지갑의 회원 ID
     * @param depositedAt 지갑이 입금한 시각(원장 생성 시각)
     */
    public record Item(
            Long payoutId,
            boolean found,
            Long payeeId,
            Long amount,
            LocalDateTime depositedAt
    ) {
        public static Item found(Long payoutId, SellerLedgerDepositResult deposit) {
            return new Item(payoutId, true, deposit.memberId(), deposit.amount(), deposit.depositedAt());
        }

        public static Item notFound(Long payoutId) {
            return new Item(payoutId, false, null, null, null);
        }
    }

    public long foundCount() {
        return items.stream().filter(Item::found).count();
    }
}
