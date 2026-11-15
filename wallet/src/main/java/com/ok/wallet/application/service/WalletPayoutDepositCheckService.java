package com.ok.wallet.application.service;

import com.ok.wallet.application.dto.PayoutDepositCheckCommand;
import com.ok.wallet.application.dto.PayoutDepositCheckResult;
import com.ok.wallet.application.dto.SellerLedgerDepositResult;
import com.ok.wallet.application.port.in.PayoutDepositCheckUseCase;
import com.ok.wallet.application.port.out.PayoutDepositCheckResultPublisher;
import com.ok.wallet.application.port.out.SellerWalletLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 정산이 묻는 정산금 입금 여부에 판매자 원장을 읽어 답한다. 이 클래스에는 @Transactional을 두지 않는다.
 * 답을 보낼 때 받는 쪽의 저장 확인을 기다리므로, 트랜잭션 안에서 보내면 그동안 DB 연결을 붙잡는다.
 * 원장은 추가만 되므로 묶음을 나눠 읽는 사이에 한 트랜잭션으로 묶지 않아도 이미 읽은 사실이 바뀌지 않는다.
 * 원장을 읽기만 해서 같은 요청을 다시 받아도 답이 같고, 보내기가 실패하면 예외를 그대로 올려 다시 받아 다시 답한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletPayoutDepositCheckService implements PayoutDepositCheckUseCase {
    // 한 번에 조회할 근거 키 수. 정산은 한 묶음을 500건까지 보내기로 해서 보통은 한 번에 끝난다.
    // 더 많이 와도 거절하지 않고 나눠 읽어, 조회 조건이 한없이 길어지지 않게 한다
    static final int QUERY_CHUNK_SIZE = 500;

    private final SellerWalletLedgerRepository sellerWalletLedgerRepository;
    private final PayoutDepositCheckResultPublisher payoutDepositCheckResultPublisher;

    @Override
    public void check(PayoutDepositCheckCommand command) {
        // 정산 ID가 없는 항목은 받는 쪽이 이미 뺐다. 여기 남아 있으면 "PAYOUT:null"로 조회하게 되므로 코드의 잘못으로 멈춘다
        // contains(null)은 List.of로 만든 목록에서 NullPointerException을 내므로 하나씩 본다
        if (command.payoutIds().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("정산 ID가 없는 항목이 남아 있습니다. checkId=" + command.checkId());
        }
        Map<String, SellerLedgerDepositResult> depositsByKey = findDeposits(command.payoutIds());

        // 같은 정산이 묶음에 두 번 있어도 묻는 대로 각각 답한다. 정산이 보낸 순서를 그대로 지킨다
        List<PayoutDepositCheckResult.Item> items = command.payoutIds().stream()
                .map(payoutId -> {
                    SellerLedgerDepositResult deposit = depositsByKey.get(PayoutReferenceKey.of(payoutId));
                    return deposit == null
                            ? PayoutDepositCheckResult.Item.notFound(payoutId)
                            : PayoutDepositCheckResult.Item.found(payoutId, deposit);
                })
                .toList();
        PayoutDepositCheckResult result = new PayoutDepositCheckResult(command.checkId(), items);

        payoutDepositCheckResultPublisher.publish(result);
        log.info("정산 입금 확인에 답했습니다. checkId={}, requested={}, found={}",
                result.checkId(), items.size(), result.foundCount());
    }

    private Map<String, SellerLedgerDepositResult> findDeposits(List<Long> payoutIds) {
        List<String> referenceKeys = payoutIds.stream()
                .distinct()
                .map(PayoutReferenceKey::of)
                .toList();
        Map<String, SellerLedgerDepositResult> depositsByKey = new HashMap<>();
        for (int from = 0; from < referenceKeys.size(); from += QUERY_CHUNK_SIZE) {
            List<String> chunk = referenceKeys.subList(from, Math.min(from + QUERY_CHUNK_SIZE, referenceKeys.size()));
            sellerWalletLedgerRepository.findDepositsByReferenceKeys(chunk)
                    .forEach(deposit -> depositsByKey.put(deposit.referenceKey(), deposit));
        }
        return depositsByKey;
    }
}
