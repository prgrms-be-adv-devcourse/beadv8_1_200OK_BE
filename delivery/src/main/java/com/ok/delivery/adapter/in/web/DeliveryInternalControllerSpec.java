package com.ok.delivery.adapter.in.web;

import com.ok.delivery.adapter.in.web.dto.ShippingFeeQuoteRequest;
import com.ok.delivery.adapter.in.web.dto.ShippingFeesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Delivery (Internal)", description = "order 모듈 연동용 내부 API (외부 공개 안 함)")
interface DeliveryInternalControllerSpec {

    @Operation(summary = "주문 생성용 배송비 계산", description = """
            주문 생성 때 판매자별 상품 금액으로 배송비를 계산합니다. 계산은 배송비 견적 API와 같은 정책을 써서, 같은 입력이면 금액이 같습니다.
            응답 규칙은 다음과 같습니다.
            - 요청한 모든 판매자를 요청 순서대로 응답합니다.
            - baseShippingFee와 freeShippingThreshold는 판매자 설정 값입니다. 무료배송이어도 baseShippingFee는 설정 값이고, freeShippingThreshold가 null이면 무료배송이 없습니다.
            - shippingFee는 무료배송을 적용한 뒤의 기본 배송비입니다. 상품 금액이 무료배송 기준 이상이면 0입니다.
            - regionSurcharge는 판매자(송장) 한 건에 붙는 도서산간 추가 운임입니다. 무료배송이어도 붙고, 일반 지역이면 0입니다.
            - 배송 설정을 저장하지 않은 판매자는 기본 설정을 적용하고, 등록되지 않은 우편번호는 일반 지역으로 봅니다.
            - 판매자가 중복되거나 우편번호가 없거나 숫자 5자리가 아니면 검증 실패로 거절합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "계산 성공"),
            @ApiResponse(responseCode = "422", description = "우편번호 형식 오류, 판매자 목록이 비었거나 100개 초과, 판매자 중복, 금액이 음수")
    })
    ResponseEntity<ShippingFeesResponse> getShippingFees(ShippingFeeQuoteRequest request);
}
