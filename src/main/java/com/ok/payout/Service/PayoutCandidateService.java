package com.ok.payout.Service;

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

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PayoutCandidateService {

    private final PayoutCandidateItemRepository repository;

    public void createFromPurchaseConfirmed(OrderProductConfirmedEvent event) {
        validate(event);
        if (repository.existsByOrderItemIdAndEventType(event.orderItemId(), PayoutEventType.SALE_AMOUNT)) {
            throw new RestApiException(PayoutErrorCode.PAYOUT_CANDIDATE_ALREADY_EXISTS);
        }
        long saleFee = PayoutPolicy.calculateSaleFee(event.totalItemPrice());
        long sellerPrice =event.totalItemPrice() - saleFee;
        long shippingPrice = event.shippingPrice();

        repository.save(PayoutCandidateItem.saleFeeAmount(
                event.orderItemId(), event.sellerId(), saleFee));
        repository.save(PayoutCandidateItem.sellerAmount(
                event.orderItemId(), event.sellerId(), sellerPrice));
        if(shippingPrice > 0) {
            repository.save(PayoutCandidateItem.saleShippingFee(
                    event.orderItemId(), event.sellerId(), shippingPrice));
        }
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
