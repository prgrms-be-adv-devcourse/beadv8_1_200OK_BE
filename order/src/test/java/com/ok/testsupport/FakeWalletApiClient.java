package com.ok.testsupport;

import com.ok.order.order.client.WalletApiClient;
import com.ok.order.order.client.dto.WalletBalanceResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 지갑 모듈 잔액 조회 내부 API 대신 메모리에 넣어 둔 잔액을 돌려주는 Fake. 등록하지 않은 회원은 잔액 0이다.
 * 등록 방식과 쓰는 이유는 {@link FakeProductApiClient}와 같다.
 */
@Component
@Primary
public class FakeWalletApiClient extends WalletApiClient {

    private final Map<Long, Long> balances = new ConcurrentHashMap<>();

    public FakeWalletApiClient() {
        super("http://localhost:0"); // 실제로 호출하지 않으므로 주소는 의미 없다
    }

    public void setBalance(Long memberId, long balance) {
        balances.put(memberId, balance);
    }

    public void clear() {
        balances.clear();
    }

    @Override
    public WalletBalanceResponse getBalance(Long memberId) {
        return new WalletBalanceResponse(memberId, balances.getOrDefault(memberId, 0L));
    }
}
