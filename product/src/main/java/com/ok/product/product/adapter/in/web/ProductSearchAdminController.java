package com.ok.product.product.adapter.in.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ok.product.product.adapter.in.web.dto.ZeroResultKeywordResponse;
import com.ok.product.product.application.dto.ZeroResultKeywordQuery;
import com.ok.product.product.application.port.in.ZeroResultKeywordUseCase;

import lombok.RequiredArgsConstructor;

@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/product-search")
@RequiredArgsConstructor
public class ProductSearchAdminController implements ProductSearchAdminControllerSpec {

	private final ZeroResultKeywordUseCase zeroResultKeywordUseCase;

	@Override
	@GetMapping("/zero-result-keywords")
	public ResponseEntity<List<ZeroResultKeywordResponse>> getZeroResultKeywords(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(defaultValue = "20") int limit
	) {
		return ResponseEntity.ok(zeroResultKeywordUseCase.getZeroResultKeywords(new ZeroResultKeywordQuery(from, to, limit))
			.stream()
			.map(ZeroResultKeywordResponse::from)
			.toList());
	}
}
