package com.ok.product.product.controller;

import com.ok.common.exception.RestApiException;
import com.ok.member.MemberRole;
import com.ok.product.product.controller.dto.ProductCreateRequest;
import com.ok.product.product.controller.dto.ProductCreateResponse;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
		// TODO: 로그인 정보에서 권한을 꺼내는 방식이 정해지면 임시 헤더 대신 그 값으로 확인한다
		if (memberRole != MemberRole.SELLER) {
			throw new RestApiException(ProductErrorCode.PRODUCT_ACCESS_DENIED);
		}
		Long productId = productService.create(memberId, request.toCommand());
		return ResponseEntity.status(HttpStatus.CREATED).body(new ProductCreateResponse(productId));
	}
}
