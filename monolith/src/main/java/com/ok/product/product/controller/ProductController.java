package com.ok.product.product.controller;

import com.ok.common.exception.RestApiException;
import com.ok.common.security.CurrentMemberId;
import com.ok.product.product.controller.dto.ProductCreateRequest;
import com.ok.product.product.controller.dto.ProductCreateResponse;
import com.ok.product.product.controller.dto.ProductStatusChangeRequest;
import com.ok.product.product.controller.dto.ProductUpdateRequest;
import com.ok.product.product.controller.dto.ProductVisibilityChangeRequest;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 상품 등록·수정·삭제는 판매자만 한다.
@PreAuthorize("hasRole('SELLER')")
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController implements ProductControllerSpec {

	private final ProductService productService;

	@Override
	@PostMapping
	public ResponseEntity<ProductCreateResponse> create(
		@CurrentMemberId Long memberId,
		@Valid @RequestBody ProductCreateRequest request
	) {
		Long productId = productService.create(memberId, request.toCommand());
		return ResponseEntity.status(HttpStatus.CREATED).body(new ProductCreateResponse(productId));
	}

	@Override
	@PutMapping("/{productId}")
	public ResponseEntity<Void> update(
		@CurrentMemberId Long memberId,
		@PathVariable Long productId,
		@Valid @RequestBody ProductUpdateRequest request
	) {
		runDetectingConflict(() -> productService.update(memberId, productId, request.toCommand()));
		return ResponseEntity.noContent().build();
	}

	@Override
	@PatchMapping("/{productId}/status")
	public ResponseEntity<Void> changeStatus(
		@CurrentMemberId Long memberId,
		@PathVariable Long productId,
		@Valid @RequestBody ProductStatusChangeRequest request
	) {
		runDetectingConflict(() -> productService.changeStatus(memberId, productId, request.status()));
		return ResponseEntity.noContent().build();
	}

	@Override
	@PatchMapping("/{productId}/visibility")
	public ResponseEntity<Void> changeVisibility(
		@CurrentMemberId Long memberId,
		@PathVariable Long productId,
		@Valid @RequestBody ProductVisibilityChangeRequest request
	) {
		runDetectingConflict(() -> productService.changeVisibility(memberId, productId, request.visibility()));
		return ResponseEntity.noContent().build();
	}

	@Override
	@DeleteMapping("/{productId}")
	public ResponseEntity<Void> delete(
		@CurrentMemberId Long memberId,
		@PathVariable Long productId
	) {
		runDetectingConflict(() -> productService.delete(memberId, productId));
		return ResponseEntity.noContent().build();
	}

	// 상품 행을 바꾸는 요청은 커밋 때 @Version 검사를 한다. 그 사이 다른 요청이 먼저 커밋했으면 실패하므로 409로 바꾼다.
	// 커밋은 서비스 메서드가 끝난 뒤(트랜잭션 프록시) 일어나서 서비스 안에서는 잡을 수 없다
	private void runDetectingConflict(Runnable action) {
		try {
			action.run();
		} catch (OptimisticLockingFailureException e) {
			throw new RestApiException(ProductErrorCode.PRODUCT_CONCURRENT_MODIFICATION);
		}
	}
}
