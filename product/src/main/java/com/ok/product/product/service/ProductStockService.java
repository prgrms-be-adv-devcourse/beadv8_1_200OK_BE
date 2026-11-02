package com.ok.product.product.service;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductStockAllocation;
import com.ok.product.product.domain.ProductStockPartialRelease;
import com.ok.product.product.domain.StockAllocationStatus;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductStockAllocationRepository;
import com.ok.product.product.repository.ProductStockPartialReleaseRepository;
import com.ok.product.product.service.dto.StockChange;
import com.ok.product.product.service.dto.StockPartialReleaseCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	private final ProductStockPartialReleaseRepository partialReleaseRepository;

	/**
	 * 주문의 옵션별 수량만큼 재고를 배정한다.
	 * 같은 주문으로 다시 호출하면 아무것도 하지 않는다. 하나라도 실패하면 이 주문의 배정은 모두 롤백된다.
	 *
	 * @throws RestApiException 옵션이 없으면 {@code OPTION_NOT_FOUND}, 판매 중이 아니면 {@code OPTION_NOT_PURCHASABLE},
	 *                          판매 가능 수량이 모자라면 {@code INSUFFICIENT_STOCK},
	 *                          이미 해제한 주문이면 {@code ALLOCATION_ALREADY_RELEASED}
	 */
	@Transactional
	public void allocate(Long orderId, List<StockChange> changes) {
		// 배정 해지 된 주문인지 체크
		if (allocationRepository.existsByOrderIdAndStatus(orderId, StockAllocationStatus.RELEASED)) {
			throw new RestApiException(ProductErrorCode.ALLOCATION_ALREADY_RELEASED);
		}
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
		List<Long> allocationIds = allocationRepository.findAllocatedIdsByOrderId(orderId);
		if (allocationIds.isEmpty()) {
			return;
		}
		for (ProductStockAllocation allocation : allocationRepository.findAllocatedByIdInForUpdate(allocationIds)) {
			// 락을 잡은 뒤 ALLOCATED로 거른 행이라 0이면 불변식 위반이다. 동시 해제는 잠금 조회에서 이미 걸러진다
			if (allocationRepository.release(allocation.getId()) == 0) {
				throw new IllegalStateException(
					"잠근 배정 기록을 해제하지 못했습니다. orderId=" + orderId + ", allocationId=" + allocation.getId());
			}
			if (productOptionRepository.release(allocation.getProductOptionId(), allocation.getQuantity()) == 0) {
				throw new IllegalStateException(
					"옵션 배정 수량이 배정 기록보다 적습니다. orderId=" + orderId + ", optionId=" + allocation.getProductOptionId());
			}
		}
	}

	/**
	 * 환불 한 건만큼 주문의 옵션 배정 재고를 일부 되돌린다.
	 * 출고 전 취소 전용이다. 같은 환불 ID로 같은 내용을 다시 보내면 아무것도 하지 않는다.
	 * 같은 환불 ID 요청이 동시에 겹치면 뒤에 온 쪽은 수량 초과나 유니크 키 위반으로 롤백된다.
	 * 이 경우의 재확인은 {@link ProductStockPartialReleaseFacade}가 한다.
	 *
	 * @throws RestApiException 같은 환불 ID로 내용이 다르면 {@code PARTIAL_RELEASE_MISMATCH},
	 *                          주문에 그 옵션의 배정 기록이 없으면 {@code ALLOCATION_NOT_FOUND},
	 *                          해제 가능 수량보다 많거나 이미 전체 해제한 주문이면 {@code RELEASE_QUANTITY_EXCEEDED}
	 */
	@Transactional
	public void partialRelease(StockPartialReleaseCommand command) {
		if (matchesRecordedPartialRelease(command)) {
			return;
		}
		ProductStockAllocation allocation = allocationRepository
			.findByOrderIdAndProductOptionId(command.orderId(), command.productOptionId())
			.orElseThrow(() -> new RestApiException(ProductErrorCode.ALLOCATION_NOT_FOUND));

		// 이력 INSERT보다 차감을 먼저 한다. 같은 주문·옵션 요청은 배정 행 락에서 줄을 서고, 수량 초과로 롤백될 요청은 INSERT까지 가지 않는다.
		// 먼저 INSERT하고 롤백하면 같은 환불 ID로 유니크 키를 기다리던 요청끼리 서로를 기다려 데드락이 난다
		if (allocationRepository.decreaseQuantity(allocation.getId(), command.quantity()) == 0) {
			throw new RestApiException(ProductErrorCode.RELEASE_QUANTITY_EXCEEDED);
		}
		// IDENTITY라 바로 INSERT된다. 같은 환불 ID 동시 요청 중 뒤에 온 쪽은 여기서 유니크 키에 막혀 차감까지 롤백된다
		partialReleaseRepository.save(ProductStockPartialRelease.of(
			command.refundId(), command.orderId(), command.productOptionId(), command.quantity()));
		if (productOptionRepository.release(command.productOptionId(), command.quantity()) == 0) {
			throw new IllegalStateException(
				"옵션 배정 수량이 배정 기록보다 적습니다. orderId=" + command.orderId() + ", optionId=" + command.productOptionId());
		}
	}

	/**
	 * 같은 환불 ID로 같은 내용의 해제가 이미 반영됐는지 확인한다.
	 *
	 * @return 같은 내용의 이력이 있으면 {@code true}, 이력이 없으면 {@code false}
	 * @throws RestApiException 같은 환불 ID 이력이 있는데 내용이 다르면 {@code PARTIAL_RELEASE_MISMATCH}
	 */
	public boolean isPartiallyReleased(StockPartialReleaseCommand command) {
		return matchesRecordedPartialRelease(command);
	}

	private boolean matchesRecordedPartialRelease(StockPartialReleaseCommand command) {
		Optional<ProductStockPartialRelease> existing = partialReleaseRepository.findByRefundId(command.refundId());
		if (existing.isEmpty()) {
			return false;
		}
		if (existing.get().matches(command.orderId(), command.productOptionId(), command.quantity())) {
			return true;
		}
		throw new RestApiException(ProductErrorCode.PARTIAL_RELEASE_MISMATCH);
	}
}
