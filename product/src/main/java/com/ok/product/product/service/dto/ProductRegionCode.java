package com.ok.product.product.service.dto;

import com.ok.product.product.domain.ProductRegion;

// API로 주고받는 산지 값
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
		return switch (this) {
			case SEOUL -> ProductRegion.SEOUL;
			case BUSAN -> ProductRegion.BUSAN;
			case DAEGU -> ProductRegion.DAEGU;
			case INCHEON -> ProductRegion.INCHEON;
			case GWANGJU -> ProductRegion.GWANGJU;
			case DAEJEON -> ProductRegion.DAEJEON;
			case ULSAN -> ProductRegion.ULSAN;
			case SEJONG -> ProductRegion.SEJONG;
			case GYEONGGI -> ProductRegion.GYEONGGI;
			case GANGWON -> ProductRegion.GANGWON;
			case CHUNGBUK -> ProductRegion.CHUNGBUK;
			case CHUNGNAM -> ProductRegion.CHUNGNAM;
			case JEONBUK -> ProductRegion.JEONBUK;
			case JEONNAM -> ProductRegion.JEONNAM;
			case GYEONGBUK -> ProductRegion.GYEONGBUK;
			case GYEONGNAM -> ProductRegion.GYEONGNAM;
			case JEJU -> ProductRegion.JEJU;
		};
	}

	public static ProductRegionCode from(ProductRegion region) {
		return switch (region) {
			case SEOUL -> SEOUL;
			case BUSAN -> BUSAN;
			case DAEGU -> DAEGU;
			case INCHEON -> INCHEON;
			case GWANGJU -> GWANGJU;
			case DAEJEON -> DAEJEON;
			case ULSAN -> ULSAN;
			case SEJONG -> SEJONG;
			case GYEONGGI -> GYEONGGI;
			case GANGWON -> GANGWON;
			case CHUNGBUK -> CHUNGBUK;
			case CHUNGNAM -> CHUNGNAM;
			case JEONBUK -> JEONBUK;
			case JEONNAM -> JEONNAM;
			case GYEONGBUK -> GYEONGBUK;
			case GYEONGNAM -> GYEONGNAM;
			case JEJU -> JEJU;
		};
	}
}
