package com.ok.payout.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.payout.repository.PayoutCandidateItemRepository;
import com.ok.payout.service.dto.PurchaseConfirmedCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PayoutCandidateService {

    private final PayoutCandidateItemRepository repository;

    public void createFromPurchaseConfirmed(PurchaseConfirmedCommand command) {
        validate(command);
        // 같은 이벤트가 이미 처리됐으면 무시한다 (재발행·재시작 시 멱등).
        // 평소 이벤트(adjustmentId = 0)만 확인해서, 같은 주문상품에 조정 후보가 있어도 구매 확정 처리를 막지 않는다.
        // 동시에 두 번 처리되는 드문 경우는 DB 유니크 제약이 중복 저장을 막고, 실패한 쪽은 재처리 시 이 확인에서 무시되어 정리된다.
        if (repository.existsByOrderItemIdAndEventTypeInAndAdjustmentId(
                command.orderItemId(), PayoutEventType.PURCHASE_CONFIRMED_TYPES, PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID)) {
            log.info("이미 처리된 구매확정 이벤트, 무시. orderItemId={}", command.orderItemId());
            return;
        }
        long saleFee = PayoutPolicy.calculateSaleFee(command.totalItemPrice());
        long sellerAmount = command.totalItemPrice() - saleFee;
        long shippingPrice = command.sellerShippingPrice();

        List<PayoutCandidateItem> items = new ArrayList<>();
        items.add(PayoutCandidateItem.ofSaleFee(
                command.orderItemId(), command.sellerId(), saleFee));
        items.add(PayoutCandidateItem.ofSaleAmount(
                command.orderItemId(), command.sellerId(), sellerAmount));
        if (shippingPrice > 0) {
            items.add(PayoutCandidateItem.ofSaleShippingFee(
                    command.orderItemId(), command.sellerId(), shippingPrice));
        }

        repository.saveAll(items);
    }

    private void validate(PurchaseConfirmedCommand command) {
        if (command.orderId() == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_ORDER_ID);
        }
        if (command.orderItemId() == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_ORDER_ITEM_ID);
        }
        if (command.sellerId() == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_SELLER_ID);
        }
        if (command.totalItemPrice() == null || command.totalItemPrice() < 0) {
            throw new RestApiException(PayoutErrorCode.INVALID_TOTAL_ITEM_PRICE);
        }
        if (command.sellerShippingPrice() == null || command.sellerShippingPrice() < 0) {
            throw new RestApiException(PayoutErrorCode.INVALID_SHIPPING_PRICE);
        }
    }

}
