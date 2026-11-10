package com.ok.testsupport;

import com.ok.common.exception.RestApiException;
import com.ok.order.order.application.dto.ProductOption;
import com.ok.order.order.application.dto.StockItem;
import com.ok.order.order.application.port.out.ProductPort;
import com.ok.order.order.exception.OrderErrorCode;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 상품 모듈 대신 메모리에 넣어 둔 옵션을 돌려주고, 재고 선점·해제를 받은 주문 ID를 기록하는 Fake 출력 포트.
 * 통합 테스트는 웹 서버 없이(MOCK) 돌아 자기 자신을 HTTP로 부를 수 없어 대신 쓴다 (상품 모듈 FakeOrderApiClient와 같은 방식).
 * HTTP 어댑터가 아니라 포트를 바꿔 끼우므로, 어댑터의 응답 변환은 이 테스트들이 확인하지 않는다.
 * {@code AbstractIntegrationTest}의 {@code @ComponentScan}이 주워 가고, {@code @Primary}로 실제 어댑터를 대신한다.
 * 모듈 테스트는 컴포넌트 스캔 범위가 주문 패키지뿐이라 {@code @Import}로 등록한다.
 * 리스너가 다른 스레드에서 부르므로 동시성 컬렉션을 쓴다.
 */
@Component
@Primary
public class FakeProductPort implements ProductPort {

    private final Map<Long, ProductOption> options = new ConcurrentHashMap<>();
    private final List<Long> allocatedOrderIds = new CopyOnWriteArrayList<>();
    private final List<Long> releasedOrderIds = new CopyOnWriteArrayList<>();
    private volatile OrderErrorCode allocationFailure;

    public void register(ProductOption option) {
        options.put(option.optionId(), option);
    }

    // 다음 재고 선점부터 이 에러 코드로 실패한다 (상품 모듈의 409 응답을 어댑터가 주문 에러 코드로 바꾼 결과와 같다)
    public void failAllocationWith(OrderErrorCode errorCode) {
        this.allocationFailure = errorCode;
    }

    public List<Long> allocatedOrderIds() {
        return List.copyOf(allocatedOrderIds);
    }

    public List<Long> releasedOrderIds() {
        return List.copyOf(releasedOrderIds);
    }

    public void clear() {
        options.clear();
        allocatedOrderIds.clear();
        releasedOrderIds.clear();
        allocationFailure = null;
    }

    // 상품 모듈은 없는 옵션을 응답에서 빼고 돌려준다
    @Override
    public List<ProductOption> findOptions(List<Long> optionIds) {
        return optionIds.stream()
                .map(options::get)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public void allocateStock(Long orderId, List<StockItem> items) {
        if (allocationFailure != null) {
            throw new RestApiException(allocationFailure);
        }
        allocatedOrderIds.add(orderId);
    }

    @Override
    public void releaseStock(Long orderId) {
        releasedOrderIds.add(orderId);
    }
}
