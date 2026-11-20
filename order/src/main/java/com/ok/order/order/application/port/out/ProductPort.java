package com.ok.order.order.application.port.out;

import com.ok.order.order.application.dto.ProductOption;
import com.ok.order.order.application.dto.StockItem;

import java.util.List;

/**
 * 주문이 상품 모듈에 부탁하는 일. 구현은 {@code adapter.out.product}에 있다.
 */
public interface ProductPort {

    /**
     * 옵션의 지금 가격·재고·판매 여부를 조회한다. 없는 옵션은 결과에서 빠진다.
     */
    List<ProductOption> findOptions(List<Long> optionIds);

    /**
     * 주문의 재고를 잡는다. 전부 잡거나 전부 실패하고, 같은 주문 ID로 다시 요청하면 무시된다.
     *
     * @throws com.ok.common.exception.RestApiException 없는 옵션이면 {@code PRODUCT_OPTION_NOT_FOUND},
     *                                                  판매 중이 아니면 {@code PRODUCT_NOT_ON_SALE}, 재고가 모자라면 {@code OUT_OF_STOCK},
     *                                                  그 밖의 실패는 {@code STOCK_ALLOCATION_FAILED}
     */
    void allocateStock(Long orderId, List<StockItem> items);

    /**
     * 주문이 잡은 재고를 푼다.
     */
    void releaseStock(Long orderId);
}
