package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductRegion;

// API로 주고받는 산지 값. 컨트롤러가 도메인 enum을 알지 않도록 서비스 경계에서 바꾼다 (상수 이름은 도메인과 같아야 한다)
public enum ProductRegionCode {
	SEOUL,
	BUSAN,
	DAEGU,
	INCHEON,
	GWANGJU,
	DAEJEON,
	ULSAN,
	SEJONG,
	GYEONGGI,
	GANGWON,
	CHUNGBUK,
	CHUNGNAM,
	JEONBUK,
	JEONNAM,
	GYEONGBUK,
	GYEONGNAM,
	JEJU;

	public ProductRegion toDomain() {
		return ProductRegion.valueOf(name());
	}

	public static ProductRegionCode from(ProductRegion region) {
		return valueOf(region.name());
	}
}
