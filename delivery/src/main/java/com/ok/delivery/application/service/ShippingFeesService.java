package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.RegionResult;
import com.ok.delivery.application.dto.SellerItemAmount;
import com.ok.delivery.application.dto.SellerShippingFeeResult;
import com.ok.delivery.application.dto.ShippingFeeQuoteQuery;
import com.ok.delivery.application.dto.ShippingFeesResult;
import com.ok.delivery.application.port.in.ShippingFeesUseCase;
import com.ok.delivery.domain.SellerShippingFee;
import com.ok.delivery.domain.SellerShippingSetting;
import com.ok.delivery.domain.ShippingFeePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 주문 생성 때 주문이 결제 금액으로 쓰는 판매자별 배송비를 계산한다.
 * 견적({@link ShippingFeeQuoteService})과 같은 정책을 써서, 구매자에게 보여 준 금액과 결제 금액이 어긋나지 않게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShippingFeesService implements ShippingFeesUseCase {

    private final DeliveryRegionService deliveryRegionService;
    private final SellerShippingSettingService sellerShippingSettingService;

    @Override
    public ShippingFeesResult calculate(ShippingFeeQuoteQuery query) {
        RegionResult region = deliveryRegionService.resolve(query.postalCode());
        Map<Long, SellerShippingSetting> settings = sellerShippingSettingService.getSettings(query.sellerIds());

        // getSettings는 요청한 모든 판매자 ID를 채워(저장값이 없으면 기본값) 돌려주므로 settings.get은 null이 아니다
        List<SellerShippingFeeResult> sellers = query.sellers().stream()
                .map(seller -> toResult(seller, settings.get(seller.sellerId()), region.surcharge()))
                .toList();
        return new ShippingFeesResult(sellers);
    }

    private static SellerShippingFeeResult toResult(SellerItemAmount seller, SellerShippingSetting setting, int regionSurcharge) {
        SellerShippingFee fee = ShippingFeePolicy.calculate(seller.sellerId(), seller.itemAmount(), setting, regionSurcharge);
        return new SellerShippingFeeResult(seller.sellerId(), setting.baseShippingFee(), setting.freeShippingThreshold(),
                fee.baseShippingFee(), fee.regionSurcharge());
    }
}
