package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.BuyerWalletLedger;
import com.ok.wallet.domain.BuyerWalletLedgerType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 구매자 지갑 원장의 이용 내역 조회 출력 포트. 기간·유형은 값이 있을 때만 조건에 더한다.
 * 기간은 [from, toExclusive) 반열린 구간이라, 종료일 하루 전체를 포함하려면 호출하는 쪽이 다음 날 0시를 넘긴다.
 * 유형은 묶음으로 받아, 호출하는 쪽이 보여 줄 유형만 고를 수 있게 한다.
 */
public interface BuyerWalletLedgerQueryRepository {

    Page<BuyerWalletLedger> findHistory(
            Long buyerWalletId,
            LocalDateTime from,
            LocalDateTime toExclusive,
            Collection<BuyerWalletLedgerType> types,
            Pageable pageable
    );
}
