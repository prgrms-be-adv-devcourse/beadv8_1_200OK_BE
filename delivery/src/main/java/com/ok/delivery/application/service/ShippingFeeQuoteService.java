package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.RegionResult;
import com.ok.delivery.application.dto.SellerItemAmount;
import com.ok.delivery.application.dto.SellerShippingQuoteResult;
import com.ok.delivery.application.dto.ShippingFeeQuoteQuery;
import com.ok.delivery.application.dto.ShippingFeeQuoteResult;
import com.ok.delivery.application.port.in.ShippingFeeQuoteUseCase;
import com.ok.delivery.domain.DeliveryDatePolicy;
import com.ok.delivery.domain.DeliveryDateRange;
import com.ok.delivery.domain.SellerShippingFee;
import com.ok.delivery.domain.SellerShippingSetting;
import com.ok.delivery.domain.ShippingFeePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 배송지와 판매자별 상품 금액으로 배송비와 출고·도착 예정일을 견적한다.
 * 구매자에게 보여 주는 값이라 결제 금액으로 쓰지 않는다. 주문은 결제 금액을 서버에서 다시 계산한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShippingFeeQuoteService implements ShippingFeeQuoteUseCase {

    private final DeliveryRegionService deliveryRegionService;
    private final SellerShippingSettingService sellerShippingSettingService;
    private final DeliveryDatePolicy deliveryDatePolicy;
    private final Clock clock;

    @Override
    public ShippingFeeQuoteResult quote(ShippingFeeQuoteQuery query) {
        LocalDateTime baseTime = LocalDateTime.now(clock);
        RegionResult region = deliveryRegionService.resolve(query.postalCode());
        Map<Long, SellerShippingSetting> settings = sellerShippingSettingService.getSettings(query.sellerIds());

        List<SellerShippingFee> fees = new ArrayList<>();
        List<SellerShippingQuoteResult> sellers = new ArrayList<>();
        for (SellerItemAmount seller : query.sellers()) {
            SellerShippingSetting setting = settings.get(seller.sellerId());
            SellerShippingFee fee = ShippingFeePolicy.calculate(seller.sellerId(), seller.itemAmount(), setting, region.surcharge());
            // 마감 시간이 판매자마다 달라 출고 예정일도 판매자별로 계산한다
            LocalDate shipDate = deliveryDatePolicy.shipDate(baseTime, setting.cutoffTime());
            DeliveryDateRange arrival = deliveryDatePolicy.arrivalRange(shipDate, region.extraDeliveryDays());

            fees.add(fee);
            sellers.add(new SellerShippingQuoteResult(
                    seller.sellerId(), fee.baseShippingFee(), fee.regionSurcharge(), fee.totalFee(),
                    shipDate, arrival.start(), arrival.end()));
        }
        return new ShippingFeeQuoteResult(region.regionGroup(), sellers, ShippingFeePolicy.totalFee(fees));
    }
}
