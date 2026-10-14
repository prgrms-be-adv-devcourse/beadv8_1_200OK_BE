package com.ok.product.product.service;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductStockAllocation;
import com.ok.product.product.domain.StockAllocationStatus;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductStockAllocationRepository;
import com.ok.product.product.service.dto.StockChange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 단위로 옵션 재고를 배정·해제한다.
 * 상품 수정과 달리 배정 기록과 옵션 행 단위 조건부 UPDATE로 동작해서 {@link ProductService}와 나눴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductStockService {

	private final ProductOptionRepository productOptionRepository;
	private final ProductStockAllocationRepository allocationRepository;

	/**
	 * 주문의 옵션별 수량만큼 재고를 배정한다.
	 * 같은 주문으로 다시 호출하면 아무것도 하지 않는다. 하나라도 실패하면 이 주문의 배정은 모두 롤백된다.
	 *
	 * @throws RestApiException 옵션이 없으면 {@code OPTION_NOT_FOUND}, 판매 중이 아니면 {@code OPTION_NOT_PURCHASABLE},
	 *                          판매 가능 수량이 모자라면 {@code INSUFFICIENT_STOCK}
	 */
	@Transactional
	public void allocate(Long orderId, List<StockChange> changes) {
		if (allocationRepository.existsByOrderId(orderId)) {
			return;
		}

		// 같은 옵션 여러 줄은 합친다(배정 기록이 주문·옵션당 하나). 두 주문이 같은 옵션들을 다른 순서로 잠그면
		// 데드락이 나므로 옵션 ID 오름차순으로 처리한다
		Map<Long, Integer> quantityByOptionId = changes.stream()
			.collect(Collectors.groupingBy(StockChange::optionId, TreeMap::new,
				Collectors.summingInt(StockChange::quantity)));
		Map<Long, ProductOption> optionById = productOptionRepository
			.findAllWithProductByIdIn(quantityByOptionId.keySet()).stream()
			.collect(Collectors.toMap(ProductOption::getId, Function.identity()));

		quantityByOptionId.forEach((optionId, quantity) -> {
			ProductOption option = optionById.get(optionId);
			if (option == null) {
				throw new RestApiException(ProductErrorCode.OPTION_NOT_FOUND);
			}
			// 재고는 아래 조건부 UPDATE로만 판정해, 재고 문제는 항상 INSUFFICIENT_STOCK으로 응답한다
			if (!option.isOnSale()) {
				throw new RestApiException(ProductErrorCode.OPTION_NOT_PURCHASABLE);
			}
			allocationRepository.save(ProductStockAllocation.allocate(orderId, optionId, quantity));
			if (productOptionRepository.allocate(optionId, quantity) == 0) {
				throw new RestApiException(ProductErrorCode.INSUFFICIENT_STOCK);
			}
		});
	}

	/**
	 * 주문에 배정한 재고를 모두 되돌린다.
	 * 배정 기록이 없거나 이미 해제했으면 아무것도 하지 않는다. 그사이 삭제된 옵션도 해제한다.
	 */
	@Transactional
	public void release(Long orderId) {
		List<ProductStockAllocation> allocations = allocationRepository
			.findByOrderIdAndStatusOrderByProductOptionIdAsc(orderId, StockAllocationStatus.ALLOCATED);
		for (ProductStockAllocation allocation : allocations) {
			// 동시에 해제 요청이 와도 기록 상태를 바꾼 한쪽만 재고를 되돌린다
			if (allocationRepository.release(allocation.getId()) == 1) {
				productOptionRepository.release(allocation.getProductOptionId(), allocation.getQuantity());
			}
		}
	}
}
