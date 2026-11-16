package com.ok.product.product.application.port.in;

import org.springframework.data.domain.Page;

import com.ok.product.product.application.dto.ProductCreateCommand;
import com.ok.product.product.application.dto.ProductStatusCode;
import com.ok.product.product.application.dto.ProductUpdateCommand;
import com.ok.product.product.application.dto.ProductVisibilityCode;
import com.ok.product.product.application.dto.SellerProductDetailResult;
import com.ok.product.product.application.dto.SellerProductSearchQuery;
import com.ok.product.product.application.dto.SellerProductSummaryResult;

/**
 * 판매자가 자기 상품을 등록·수정·삭제하고 조회하는 유스케이스.
 */
public interface SellerProductUseCase {

	Long create(Long sellerId, ProductCreateCommand command);

	void update(Long sellerId, Long productId, ProductUpdateCommand command);

	void changeStatus(Long sellerId, Long productId, ProductStatusCode status);

	void changeVisibility(Long sellerId, Long productId, ProductVisibilityCode visibility);

	void delete(Long sellerId, Long productId);

	Page<SellerProductSummaryResult> searchMyProducts(Long sellerId, SellerProductSearchQuery query);

	SellerProductDetailResult getMyProduct(Long sellerId, Long productId);

}
