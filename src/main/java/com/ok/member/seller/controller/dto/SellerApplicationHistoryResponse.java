package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerApplicationResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "판매자 신청 내역 응답")
public record SellerApplicationHistoryResponse(

        @Schema(description = "신청 내역 (최근 신청부터). 거절된 신청도 포함한다")
        List<SellerApplicationHistoryItemResponse> applications
) {

    public static SellerApplicationHistoryResponse from(List<SellerApplicationResult> results) {
        return new SellerApplicationHistoryResponse(
                results.stream().map(SellerApplicationHistoryItemResponse::from).toList());
    }

}
