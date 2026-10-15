package com.ok.wallet.event;

import com.ok.common.event.SellerPayoutCreatedEvent;
import com.ok.wallet.service.SellerWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 정산 모듈이 발행하는 이벤트를 받아 판매자 지갑에 반영한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutEventListener {
    private final SellerWalletService sellerWalletService;

    /**
     * 판매자 몫 정산이 생성되면 판매자 지갑에 정산금을 입금한다.
     * 같은 정산 이벤트가 재발행으로 다시 와도 payoutId로 중복 입금을 막는다.
     */
    @ApplicationModuleListener
    void on(SellerPayoutCreatedEvent event) {
        // 값이 잘못된 이벤트는 다시 처리해도 결과가 같으므로, 입금하지 않고 기록만 남긴 뒤 정상 종료해 재처리 대상에서 뺀다.
        // 확인은 트랜잭션이 걸린 서비스를 부르기 전에 끝낸다. 서비스 안에서 예외가 나면 트랜잭션이 롤백 전용이 되어 정상 종료할 수 없다.
        Optional<String> invalidReason = findInvalidReason(event);
        if (invalidReason.isPresent()) {
            log.error("처리할 수 없는 정산 이벤트라 입금하지 않습니다. reason={}, event={}", invalidReason.get(), event);
            return;
        }
        // DB 오류 같은 일시적 실패는 예외를 그대로 전파해 재처리 대상으로 남긴다.
        sellerWalletService.depositPayout(event.payeeId(), event.payoutId(), event.amount());
    }

    // 정산 이벤트 계약(세미 범위): 모든 값이 있고 amount는 0보다 크다.
    private static Optional<String> findInvalidReason(SellerPayoutCreatedEvent event) {
        if (event.payoutId() == null) {
            return Optional.of("payoutId 없음");
        }
        if (event.payeeId() == null) {
            return Optional.of("payeeId 없음");
        }
        if (event.amount() == null) {
            return Optional.of("amount 없음");
        }
        if (event.amount() <= 0) {
            return Optional.of("amount가 0 이하");
        }
        return Optional.empty();
    }
}
