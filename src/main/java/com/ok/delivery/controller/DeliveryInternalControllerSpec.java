package com.ok.delivery.controller;

import com.ok.delivery.controller.dto.RemoteAreaFeeRequest;
import com.ok.delivery.controller.dto.RemoteAreaFeeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;

@Tag(name = "Delivery (Internal)", description = "order 모듈 연동용 내부 API (외부 공개 안 함)")
interface DeliveryInternalControllerSpec {

    @Operation(summary = "도서산간 추가 운임 조회", description = """
            주문 생성 때 배송지 우편번호의 도서산간 여부와 추가 운임을 조회합니다.
            추가 운임은 송장(판매자) 한 건 기준이라, 판매자가 여럿인 주문은 판매자마다 더합니다.
            등록되지 않은 우편번호는 일반 지역(remoteArea=false, additionalFee=0)으로 응답합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "422", description = "우편번호가 없거나 숫자 5자리가 아님")
    })
    ResponseEntity<RemoteAreaFeeResponse> getRemoteAreaFee(@ParameterObject RemoteAreaFeeRequest request);
}
