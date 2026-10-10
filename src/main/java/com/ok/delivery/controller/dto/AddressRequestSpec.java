package com.ok.delivery.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

interface AddressRequestSpec {

    @Schema(description = "수령인 이름", example = "홍길동")
    String recipientName();

    @Schema(description = "연락처 (하이픈 허용, 제거 후 숫자 10~11자리). 숫자만 저장됩니다.", example = "010-1234-5678")
    String recipientPhone();

    @Schema(description = "우편번호 (숫자 5자리)", example = "06236")
    String postalCode();

    @Schema(description = "주소", example = "서울시 강남구 테헤란로 1")
    String address();

    @Schema(description = "상세 주소", example = "101동 202호")
    String addressDetail();

    @Schema(description = "배송 요청사항 (선택)", example = "문 앞에 놓아주세요")
    String deliveryNote();
}
