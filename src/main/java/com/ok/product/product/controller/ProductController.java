package com.ok.product.product.controller;

import com.ok.common.exception.RestApiException;
import com.ok.member.MemberRole;
import com.ok.product.product.controller.dto.ProductCreateRequest;
import com.ok.product.product.controller.dto.ProductCreateResponse;
import com.ok.product.product.controller.dto.ProductStatusChangeRequest;
import com.ok.product.product.controller.dto.ProductUpdateRequest;
import com.ok.product.product.controller.dto.ProductVisibilityChangeRequest;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO: 임시 헤더는 클라이언트가 아무 회원으로 위장할 수 있어 운영에는 등록하지 않는다. 인증이 붙으면 @Profile 제거한다
@Profile("!prod")
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController implements ProductControllerSpec {

	// TODO: 로그인 회원 정보를 꺼내는 방법이 정해지면 임시 헤더를 교체한다
	static final String MEMBER_ID_HEADER = "X-Member-Id";
	static final String MEMBER_ROLE_HEADER = "X-Member-Role";

	private final ProductService productService;

	@Override
	@PostMapping
	public ResponseEntity<ProductCreateResponse> create(
		@RequestHeader(MEMBER_ID_HEADER) Long memberId,
		@RequestHeader(MEMBER_ROLE_HEADER) MemberRole memberRole,
		@Valid @RequestBody ProductCreateRequest request
	) {
		checkSeller(memberRole);
		Long productId = productService.create(memberId, request.toCommand());
		return ResponseEntity.status(HttpStatus.CREATED).body(new ProductCreateResponse(productId));
	}

	@Override
	@PutMapping("/{productId}")
	public ResponseEntity<Void> update(
		@RequestHeader(MEMBER_ID_HEADER) Long memberId,
		@RequestHeader(MEMBER_ROLE_HEADER) MemberRole memberRole,
		@PathVariable Long productId,
		@Valid @RequestBody ProductUpdateRequest request
	) {
		checkSeller(memberRole);
		runDetectingConflict(() -> productService.update(memberId, productId, request.toCommand()));
		return ResponseEntity.noContent().build();
	}

	@Override
	@PatchMapping("/{productId}/status")
	public ResponseEntity<Void> changeStatus(
		@RequestHeader(MEMBER_ID_HEADER) Long memberId,
		@RequestHeader(MEMBER_ROLE_HEADER) MemberRole memberRole,
		@PathVariable Long productId,
		@Valid @RequestBody ProductStatusChangeRequest request
	) {
		checkSeller(memberRole);
		runDetectingConflict(() -> productService.changeStatus(memberId, productId, request.status()));
		return ResponseEntity.noContent().build();
	}

	@Override
	@PatchMapping("/{productId}/visibility")
	public ResponseEntity<Void> changeVisibility(
		@RequestHeader(MEMBER_ID_HEADER) Long memberId,
		@RequestHeader(MEMBER_ROLE_HEADER) MemberRole memberRole,
		@PathVariable Long productId,
		@Valid @RequestBody ProductVisibilityChangeRequest request
	) {
		checkSeller(memberRole);
		runDetectingConflict(() -> productService.changeVisibility(memberId, productId, request.visibility()));
		return ResponseEntity.noContent().build();
	}

	@Override
	@DeleteMapping("/{productId}")
	public ResponseEntity<Void> delete(
		@RequestHeader(MEMBER_ID_HEADER) Long memberId,
		@RequestHeader(MEMBER_ROLE_HEADER) MemberRole memberRole,
		@PathVariable Long productId
	) {
		checkSeller(memberRole);
		runDetectingConflict(() -> productService.delete(memberId, productId));
		return ResponseEntity.noContent().build();
	}

	// TODO: 로그인 정보에서 권한을 꺼내는 방식이 정해지면 임시 헤더 대신 그 값으로 확인한다
	// TODO(추후 고려): 필요시 ADMIN이 다른 판매자의 상품을 수정·삭제하는 기능
	private void checkSeller(MemberRole memberRole) {
		if (memberRole != MemberRole.SELLER) {
			throw new RestApiException(ProductErrorCode.PRODUCT_ACCESS_DENIED);
		}
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
