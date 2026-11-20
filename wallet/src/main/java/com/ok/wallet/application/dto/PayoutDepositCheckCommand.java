package com.ok.wallet.application.dto;

import java.util.List;

/**
 * 정산이 묻는 "이 정산들이 지갑에 입금됐는지" 한 묶음.
 * 정산이 기대하는 판매자·금액은 지갑이 판정하지 않으므로 담지 않는다.
 *
 * @param checkId   정산이 붙인 묶음 번호. 답에 그대로 돌려준다
 * @param payoutIds 확인할 정산 ID. 정산 ID가 없는 항목은 받는 쪽에서 이미 뺐다
 */
public record PayoutDepositCheckCommand(
        Long checkId,
        List<Long> payoutIds
) {
}
