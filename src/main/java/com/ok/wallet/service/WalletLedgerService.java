package com.ok.wallet.service;

import com.ok.common.exception.RestApiException;
import com.ok.wallet.domain.BuyerWalletLedger;
import com.ok.wallet.domain.BuyerWalletLedgerType;
import com.ok.wallet.exception.WalletErrorCode;
import com.ok.wallet.repository.BuyerWalletLedgerQueryRepository;
import com.ok.wallet.repository.BuyerWalletRepository;
import com.ok.wallet.service.dto.LedgerHistoryQuery;
import com.ok.wallet.service.dto.LedgerHistoryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * 구매자 지갑 이용 내역(원장) 조회.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class WalletLedgerService {
    // 결제 원장의 근거 키 접두어. BuyerWalletService.payForOrder가 "PAYMENT:{orderId}"로 만든다.
    private static final String PAYMENT_KEY_PREFIX = "PAYMENT:";

    // 이용 내역에 보여 주는 유형. 환불은 세미 범위 밖이라 아직 노출하지 않는다 (추가 시 여기와 API 문서를 함께 바꾼다)
    private static final Set<BuyerWalletLedgerType> VISIBLE_TYPES =
            EnumSet.of(BuyerWalletLedgerType.CHARGE, BuyerWalletLedgerType.PAYMENT);

    private final BuyerWalletRepository buyerWalletRepository;
    private final BuyerWalletLedgerQueryRepository ledgerQueryRepository;

    /**
     * 본인 이용 내역을 최신순으로 페이지 단위로 조회한다. 지갑이 아직 없으면 빈 페이지를 돌려준다.
     * 기간은 날짜로 받아 [시작일 0시, 종료일 다음 날 0시)로 바꿔, 종료일 하루 전체를 포함한다.
     */
    public Page<LedgerHistoryResult> getHistory(LedgerHistoryQuery query) {
        Set<BuyerWalletLedgerType> types = toTypes(query.type());
        validatePeriod(query.from(), query.to());
        Pageable pageable = PageRequest.of(query.page(), query.size());

        return buyerWalletRepository.findByMemberId(query.memberId())
                .map(wallet -> ledgerQueryRepository.findHistory(
                                wallet.getId(), startOf(query.from()), endOf(query.to()), types, pageable)
                        .map(WalletLedgerService::toResult))
                .orElseGet(() -> Page.empty(pageable));
    }

    // 값이 없으면 보여 주는 유형 전체, 있으면 그 유형 하나. 보여 주지 않는 유형이나 모르는 값은 422
    private static Set<BuyerWalletLedgerType> toTypes(String type) {
        if (type == null || type.isBlank()) {
            return VISIBLE_TYPES;
        }
        BuyerWalletLedgerType parsed;
        try {
            parsed = BuyerWalletLedgerType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new RestApiException(WalletErrorCode.UNKNOWN_LEDGER_TYPE);
        }
        if (!VISIBLE_TYPES.contains(parsed)) {
            throw new RestApiException(WalletErrorCode.UNKNOWN_LEDGER_TYPE);
        }
        return EnumSet.of(parsed);
    }

    private static void validatePeriod(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new RestApiException(WalletErrorCode.INVALID_LEDGER_PERIOD);
        }
    }

    private static LocalDateTime startOf(LocalDate from) {
        return from == null ? null : from.atStartOfDay();
    }

    // 종료일 23:59:59.999...까지 포함하도록 다음 날 0시 미만으로 비교한다
    private static LocalDateTime endOf(LocalDate to) {
        return to == null ? null : to.plusDays(1).atStartOfDay();
    }

    private static LedgerHistoryResult toResult(BuyerWalletLedger ledger) {
        return new LedgerHistoryResult(
                ledger.getCreatedAt(), ledger.getType().name(), orderIdOf(ledger),
                ledger.getAmount(), ledger.getBalanceAfter());
    }

    // 결제 내역이면 근거 키에서 주문 ID를 꺼낸다. 프론트가 주문 상세로 이동할 때 쓴다
    private static Long orderIdOf(BuyerWalletLedger ledger) {
        if (ledger.getType() != BuyerWalletLedgerType.PAYMENT) {
            return null;
        }
        return Long.valueOf(ledger.getReferenceKey().substring(PAYMENT_KEY_PREFIX.length()));
    }
}
