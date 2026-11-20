package com.ok.testsupport;

import com.ok.order.order.application.port.out.WalletPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 지갑 모듈 대신 메모리에 넣어 둔 잔액을 돌려주는 Fake 출력 포트. 등록하지 않은 회원은 잔액 0이다.
 * 등록 방식과 쓰는 이유는 {@link FakeProductPort}와 같다.
 */
@Component
@Primary
public class FakeWalletPort implements WalletPort {

    private final Map<Long, Long> balances = new ConcurrentHashMap<>();

    public void setBalance(Long memberId, long balance) {
        balances.put(memberId, balance);
    }

    public void clear() {
        balances.clear();
    }

    @Override
    public long getBalance(Long memberId) {
        return balances.getOrDefault(memberId, 0L);
    }
}
