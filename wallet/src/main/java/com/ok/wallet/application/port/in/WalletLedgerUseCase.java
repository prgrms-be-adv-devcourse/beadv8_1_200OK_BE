package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.LedgerHistoryQuery;
import com.ok.wallet.application.dto.LedgerHistoryResult;
import org.springframework.data.domain.Page;

/**
 * 구매자 지갑 이용 내역(원장) 조회 유스케이스.
 */
public interface WalletLedgerUseCase {

    /**
     * 본인 이용 내역을 최신순으로 페이지 단위로 조회한다. 지갑이 아직 없으면 빈 페이지를 돌려준다.
     * 기간은 날짜로 받아 [시작일 0시, 종료일 다음 날 0시)로 바꿔, 종료일 하루 전체를 포함한다.
     */
    Page<LedgerHistoryResult> getHistory(LedgerHistoryQuery query);
}
