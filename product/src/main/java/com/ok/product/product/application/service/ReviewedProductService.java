package com.ok.product.product.application.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.application.dto.ProductCategoryCode;
import com.ok.product.product.application.dto.ReviewedProductResult;
import com.ok.product.product.application.port.in.ReviewedProductUseCase;
import com.ok.product.product.application.port.out.ProductOptionRepository;
import com.ok.product.product.application.port.out.ProductRepository;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.exception.ProductErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewedProductService implements ReviewedProductUseCase {

	private final ProductRepository productRepository;
	private final ProductOptionRepository productOptionRepository;

	@Override
	public ReviewedProductResult getActiveProduct(Long productId) {
		return toResult(productRepository.findByIdAndDeletedAtIsNull(productId)
			.orElseThrow(() -> new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND)));
	}

	@Override
	public ProductCategoryCode getVisibleProductCategory(Long productId) {
		Product product = productRepository.findByIdAndVisibilityAndDeletedAtIsNull(productId, ProductVisibility.VISIBLE)
			.orElseThrow(() -> new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND));
		return ProductCategoryCode.from(product.getCategory());
	}

	@Override
	public void checkSellerProduct(Long sellerId, Long productId) {
		productRepository.findByIdAndDeletedAtIsNull(productId)
			.filter(product -> product.isOwnedBy(sellerId))
			.orElseThrow(() -> new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND));
	}

	@Override
	public Map<Long, ReviewedProductResult> findProducts(Collection<Long> productIds) {
		return productRepository.findAllById(productIds).stream()
			.collect(Collectors.toMap(Product::getId, this::toResult));
	}

	@Override
	public Map<Long, String> findOptionNames(Collection<Long> optionIds) {
		return productOptionRepository.findAllById(optionIds).stream()
			.collect(Collectors.toMap(ProductOption::getId, ProductOption::getName));
	}

	@Override
	public Optional<String> findOptionName(Long productId, Long optionId) {
		return productOptionRepository.findById(optionId)
			.filter(option -> option.getProduct().getId().equals(productId))
			.map(ProductOption::getName);
	}

	// 리뷰 저장과 같은 트랜잭션에서만 집계를 바꾼다. 트랜잭션 없이 불리면 집계만 따로 커밋되므로 새로 열지 않고 실패시킨다
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void addReviewStats(Long productId, int countDelta, int ratingDelta) {
		productRepository.addReviewStats(productId, countDelta, ratingDelta);
	}

	private ReviewedProductResult toResult(Product product) {
		return new ReviewedProductResult(product.getName(), ProductCategoryCode.from(product.getCategory()));
	}
}
