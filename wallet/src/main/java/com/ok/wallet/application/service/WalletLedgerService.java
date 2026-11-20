package com.ok.wallet.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.wallet.application.dto.LedgerHistoryQuery;
import com.ok.wallet.application.dto.LedgerHistoryResult;
import com.ok.wallet.application.port.in.WalletLedgerUseCase;
import com.ok.wallet.application.port.out.BuyerWalletLedgerQueryRepository;
import com.ok.wallet.application.port.out.BuyerWalletRepository;
import com.ok.wallet.application.port.out.WalletOrderPaymentRepository;
import com.ok.wallet.application.port.out.WalletOrderRefundRepository;
import com.ok.wallet.domain.BuyerWalletLedger;
import com.ok.wallet.domain.BuyerWalletLedgerType;
import com.ok.wallet.domain.WalletOrderPayment;
import com.ok.wallet.domain.WalletOrderRefund;
import com.ok.wallet.exception.WalletErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 구매자 지갑 이용 내역(원장) 조회.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class WalletLedgerService implements WalletLedgerUseCase {
    // 이용 내역에 보여 주는 유형. 원장 유형을 더하면 보여 줄지 정해 여기와 API 문서를 함께 바꾼다.
    // 환불을 보이면 되돌리기도 함께 보인다. 하나만 보이면 다시 가져간 환불이 내역에서 잔액과 맞지 않는다
    private static final Set<BuyerWalletLedgerType> VISIBLE_TYPES = EnumSet.of(
            BuyerWalletLedgerType.CHARGE, BuyerWalletLedgerType.PAYMENT,
            BuyerWalletLedgerType.REFUND, BuyerWalletLedgerType.REFUND_REVERT);

    private final BuyerWalletRepository buyerWalletRepository;
    private final BuyerWalletLedgerQueryRepository ledgerQueryRepository;
    private final WalletOrderRefundRepository walletOrderRefundRepository;
    private final WalletOrderPaymentRepository walletOrderPaymentRepository;

    @Override
    public Page<LedgerHistoryResult> getHistory(LedgerHistoryQuery query) {
        Set<BuyerWalletLedgerType> types = toTypes(query.type());
        validatePeriod(query.from(), query.to());
        Pageable pageable = PageRequest.of(query.page(), query.size());

        return buyerWalletRepository.findByMemberId(query.memberId())
                .map(wallet -> toResults(query.memberId(), ledgerQueryRepository.findHistory(
                        wallet.getId(), startOf(query.from()), endOf(query.to()), types, pageable)))
                .orElseGet(() -> Page.empty(pageable));
    }

    // 근거 키는 원장마다 하나뿐이라, 줄별 주문 ID를 근거 키로 모아 두고 주문 요약을 붙인다
    private Page<LedgerHistoryResult> toResults(Long memberId, Page<BuyerWalletLedger> ledgers) {
        Map<String, Long> refundOrderIdByKey = orderIdsOfRefunds(ledgers.getContent());
        Map<String, Long> orderIdByKey = new HashMap<>();
        for (BuyerWalletLedger ledger : ledgers.getContent()) {
            Long orderId = orderIdOf(ledger, refundOrderIdByKey);
            if (orderId != null) {
                orderIdByKey.put(ledger.getReferenceKey(), orderId);
            }
        }
        Map<Long, String> summaryByOrderId = orderSummariesOf(memberId, orderIdByKey.values());
        return ledgers.map(ledger -> {
            Long orderId = orderIdByKey.get(ledger.getReferenceKey());
            return toResult(ledger, orderId, orderId == null ? null : summaryByOrderId.get(orderId));
        });
    }

    // 주문 요약은 결제 처리 기록에만 있어, 반환·되돌리기 줄도 주문 ID로 같은 기록을 찾는다. 한 페이지 분을 한 번에 읽는다.
    // 요약 칸이 생기기 전 결제는 요약이 없어 빠진다.
    // 지갑 주인이 결제한 주문의 요약만 붙인다. 반환은 결제한 구매자에게만 돌려주므로(refundForOrder) 지금은 늘 같지만,
    // 그 규칙에 기대면 규칙이 바뀌거나 데이터가 어긋났을 때 다른 사람의 상품 이름이 보인다
    private Map<Long, String> orderSummariesOf(Long memberId, Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> summaryByOrderId = new HashMap<>();
        for (WalletOrderPayment payment : walletOrderPaymentRepository.findAllByOrderIdIn(Set.copyOf(orderIds))) {
            if (!payment.getBuyerId().equals(memberId)) {
                log.warn("[LEDGER_ORDER_BUYER_MISMATCH] 이용 내역의 주문을 다른 구매자가 결제했습니다. 요약을 붙이지 않습니다. "
                        + "orderId={}, memberId={}, paymentBuyerId={}", payment.getOrderId(), memberId, payment.getBuyerId());
                continue;
            }
            if (payment.getOrderSummary() != null) {
                summaryByOrderId.put(payment.getOrderId(), payment.getOrderSummary());
            }
        }
        return summaryByOrderId;
    }

    // 반환·되돌리기 원장의 근거 키에는 환불 ID만 있어, 반환 처리 기록에서 주문 ID를 찾아 근거 키별로 돌려준다.
    // 줄마다 조회하지 않고 한 페이지 분을 한 번에 읽는다. 키는 여기서 한 번만 읽어, 형식이 틀린 키의 경고가 겹치지 않게 한다.
    // 환불 ID가 아니라 근거 키로 모으는 것은 한 환불의 반환과 되돌리기가 같은 페이지에 오면 환불 ID가 같아서다
    private Map<String, Long> orderIdsOfRefunds(List<BuyerWalletLedger> ledgers) {
        Map<String, Long> refundIdByKey = new HashMap<>();
        for (BuyerWalletLedger ledger : ledgers) {
            Long refundId = refundIdOf(ledger);
            if (refundId != null) {
                refundIdByKey.put(ledger.getReferenceKey(), refundId);
            }
        }
        if (refundIdByKey.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> orderIdByRefundId = walletOrderRefundRepository
                .findAllByRefundIdIn(Set.copyOf(refundIdByKey.values())).stream()
                .collect(Collectors.toMap(WalletOrderRefund::getRefundId, WalletOrderRefund::getOrderId));
        Map<String, Long> orderIdByKey = new HashMap<>();
        refundIdByKey.forEach((key, refundId) -> {
            Long orderId = orderIdByRefundId.get(refundId);
            if (orderId == null) {
                log.warn("[LEDGER_REFUND_RECORD_MISSING] 환불 원장에 맞는 반환 처리 기록이 없습니다. referenceKey={}", key);
                return;
            }
            orderIdByKey.put(key, orderId);
        });
        return orderIdByKey;
    }

    // 값이 없으면 보여 주는 유형 전체, 있으면 그 유형 하나.
    // 모르는 값과 보여 주지 않는 유형은 모두 지원하지 않는 유형으로 422
    private static Set<BuyerWalletLedgerType> toTypes(String type) {
        if (type == null || type.isBlank()) {
            return VISIBLE_TYPES;
        }
        BuyerWalletLedgerType parsed;
        try {
            parsed = BuyerWalletLedgerType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new RestApiException(WalletErrorCode.UNSUPPORTED_LEDGER_TYPE);
        }
        if (!VISIBLE_TYPES.contains(parsed)) {
            throw new RestApiException(WalletErrorCode.UNSUPPORTED_LEDGER_TYPE);
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

    private static LedgerHistoryResult toResult(BuyerWalletLedger ledger, Long orderId, String orderSummary) {
        return new LedgerHistoryResult(
                ledger.getCreatedAt(), ledger.getType().name(), orderId, orderSummary,
                ledger.getAmount(), ledger.getBalanceAfter());
    }

    // 결제·반환·되돌리기 내역의 주문 ID. 프론트가 주문 상세로 이동할 때 쓴다.
    // 반환 처리 기록이 없으면 목록 전체를 실패시키지 않고 주문 ID만 비운다
    private static Long orderIdOf(BuyerWalletLedger ledger, Map<String, Long> refundOrderIdByKey) {
        return switch (ledger.getType()) {
            case PAYMENT -> PaymentReferenceKey.orderIdOf(ledger.getReferenceKey());
            case REFUND, REFUND_REVERT -> refundOrderIdByKey.get(ledger.getReferenceKey());
            case CHARGE -> null;
        };
    }

    // 반환·되돌리기 원장이면 근거 키의 환불 ID, 아니면 null
    private static Long refundIdOf(BuyerWalletLedger ledger) {
        return switch (ledger.getType()) {
            case REFUND -> RefundReferenceKey.refundIdOf(ledger.getReferenceKey());
            case REFUND_REVERT -> RefundRevertReferenceKey.refundIdOf(ledger.getReferenceKey());
            case CHARGE, PAYMENT -> null;
        };
    }
}
