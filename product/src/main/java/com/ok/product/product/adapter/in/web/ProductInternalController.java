package com.ok.product.product.adapter.in.web;

import com.ok.product.product.adapter.in.web.dto.ProductOptionInfoResponse;
import com.ok.product.product.adapter.in.web.dto.StockAllocationRequest;
import com.ok.product.product.adapter.in.web.dto.StockPartialReleaseRequest;
import com.ok.product.product.application.port.in.ProductOptionInfoUseCase;
import com.ok.product.product.application.port.in.StockPartialReleaseUseCase;
import com.ok.product.product.application.port.in.StockAllocationUseCase;
import lombok.RequiredArgsConstructor;
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
// 외부에서 재고를 배정해 품절로 만들지 못하게 nginx가 /internal/**의 외부 접근을 막는다(#204).
@RestController
@RequestMapping("/internal/v1/products")
@RequiredArgsConstructor
public class ProductInternalController implements ProductInternalControllerSpec {

	private final ProductOptionInfoUseCase productOptionInfoUseCase;
	private final StockAllocationUseCase stockAllocationUseCase;
	private final StockPartialReleaseUseCase stockPartialReleaseUseCase;

	@Override
	@GetMapping("/options")
	public ResponseEntity<List<ProductOptionInfoResponse>> getOptions(@RequestParam List<Long> optionIds) {
		return ResponseEntity.ok(productOptionInfoUseCase.findOptionInfos(optionIds).stream()
			.map(ProductOptionInfoResponse::from)
			.toList());
	}

	@Override
	@PostMapping("/stock-allocations")
	public ResponseEntity<Void> allocateStock(@RequestBody StockAllocationRequest request) {
		stockAllocationUseCase.allocate(request.orderId(), request.toChanges());
		return ResponseEntity.noContent().build();
	}

	@Override
	@PostMapping("/stock-allocations/{orderId}/release")
	public ResponseEntity<Void> releaseStock(@PathVariable Long orderId) {
		stockAllocationUseCase.release(orderId);
		return ResponseEntity.noContent().build();
	}

	@Override
	@PostMapping("/stock-allocations/{orderId}/partial-release")
	public ResponseEntity<Void> partialReleaseStock(@PathVariable Long orderId,
		@RequestBody StockPartialReleaseRequest request) {
		stockPartialReleaseUseCase.partialRelease(request.toCommand(orderId));
		return ResponseEntity.noContent().build();
	}
}
