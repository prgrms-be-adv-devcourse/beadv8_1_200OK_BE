package com.ok.payout.service;

import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutErrorCode;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.repository.PayoutCandidateItemRepository;
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

    public void createFromPurchaseConfirmed(OrderProductConfirmedEvent event) {
        validate(event);
        if (repository.existsByOrderItemIdAndEventTypeIn(event.orderItemId(), PayoutEventType.PURCHASE_CONFIRMED_TYPES)) {
            log.info("{}: 이미 처리된 구매확정 이벤트, 무시. orderItemId={}",
                    PayoutErrorCode.PAYOUT_CANDIDATE_ALREADY_EXISTS.name(), event.orderItemId());
            return;
        }
        long saleFee = PayoutPolicy.calculateSaleFee(event.totalItemPrice());
        long sellerAmount =event.totalItemPrice() - saleFee;
        long shippingPrice = event.shippingPrice();

        List<PayoutCandidateItem> items = new ArrayList<>();
        items.add(PayoutCandidateItem.ofSaleFee(
                event.orderItemId(), event.sellerId(), saleFee));
        items.add(PayoutCandidateItem.ofSaleAmount(
                event.orderItemId(), event.sellerId(), sellerAmount));
        if (shippingPrice > 0) {
            items.add(PayoutCandidateItem.ofSaleShippingFee(
                    event.orderItemId(), event.sellerId(), shippingPrice));
        }

        repository.saveAll(items);
    }

    private void validate(OrderProductConfirmedEvent event) {
        if (event.orderItemId() == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_ORDER_ITEM_ID);
        }
        if (event.sellerId() == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_SELLER_ID);
        }
        if (event.totalItemPrice() == null || event.totalItemPrice() < 0) {
            throw new RestApiException(PayoutErrorCode.INVALID_TOTAL_ITEM_PRICE);
        }
        if (event.shippingPrice() == null || event.shippingPrice() < 0) {
            throw new RestApiException(PayoutErrorCode.INVALID_SHIPPING_PRICE);
        }
    }

}
