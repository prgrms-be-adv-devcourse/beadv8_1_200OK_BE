package com.ok.product.product.controller;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.controller.dto.ProductOptionInfoResponse;
import com.ok.product.product.controller.dto.StockAllocationRequest;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.service.ProductService;
import com.ok.product.product.service.ProductStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// order 모듈이 HTTP로 호출하는 내부 API
// TODO: 인증 없이 열려 있으면 누구나 재고를 배정해 품절로 만들 수 있어 운영에는 등록하지 않는다.
@Profile({"dev", "test"})
@RestController
@RequestMapping("/internal/v1/products")
@RequiredArgsConstructor
public class ProductInternalController implements ProductInternalControllerSpec {

	private final ProductService productService;
	private final ProductStockService productStockService;

	@Override
	@GetMapping("/options")
	public ResponseEntity<List<ProductOptionInfoResponse>> getOptions(@RequestParam List<Long> optionIds) {
		return ResponseEntity.ok(productService.findOptionInfos(optionIds).stream()
			.map(ProductOptionInfoResponse::from)
			.toList());
	}

	@Override
	@PostMapping("/stock-allocations")
	public ResponseEntity<Void> allocateStock(@RequestBody StockAllocationRequest request) {
		try {
			productStockService.allocate(request.orderId(), request.toChanges());
		} catch (DataIntegrityViolationException e) {
			// 같은 주문 ID 요청이 동시에 와서 배정 기록의 유니크 키가 겹친 경우 409
			throw new RestApiException(ProductErrorCode.ALLOCATION_IN_PROGRESS);
		}
		return ResponseEntity.noContent().build();
	}

	@Override
	@PostMapping("/stock-allocations/{orderId}/release")
	public ResponseEntity<Void> releaseStock(@PathVariable Long orderId) {
		productStockService.release(orderId);
		return ResponseEntity.noContent().build();
	}
}
