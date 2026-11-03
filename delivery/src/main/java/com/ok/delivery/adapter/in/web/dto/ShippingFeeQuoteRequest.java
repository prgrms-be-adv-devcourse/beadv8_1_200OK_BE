package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.ShippingFeeQuoteQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Schema(description = "배송비 견적 요청")
public record ShippingFeeQuoteRequest(

        @Schema(description = "배송지 우편번호 (숫자 5자리)", example = "63100")
        @NotBlank(message = "우편번호는 필수입니다.")
        @Pattern(regexp = "^\\d{5}$", message = "우편번호는 숫자 5자리여야 합니다.")
        String postalCode,

        // 한 주문에 판매자가 100명을 넘을 일은 없어서, 지나치게 큰 요청으로 계산을 반복하지 않도록 막는다
        @Schema(description = "판매자별 상품 금액 (1~100개, 판매자 중복 불가)")
        @NotEmpty(message = "판매자 목록은 1개 이상이어야 합니다.")
        @Size(max = 100, message = "판매자 목록은 100개 이하여야 합니다.")
        List<@NotNull(message = "판매자 항목은 비울 수 없습니다.") @Valid SellerItemAmountRequest> sellers
) {

    // 같은 판매자가 두 번 오면 송장이 둘로 계산되어 배송비가 두 번 붙는다. 기본 검증으로는 목록 안의 중복을 표현할 수 없어 @AssertTrue로 검증한다
    // is로 시작하는 메서드는 Swagger가 요청 필드로 오해해 문서에 나오므로 숨긴다
    // 목록이나 항목이 비었으면 다른 제약이 잡으므로 여기서는 통과시켜 오류 메시지가 겹치지 않게 한다
    @Schema(hidden = true)
    @AssertTrue(message = "판매자는 중복될 수 없습니다.")
    public boolean isSellerIdsUnique() {
        if (sellers == null) {
            return true;
        }
        Set<Long> sellerIds = new HashSet<>();
        return sellers.stream()
                .filter(Objects::nonNull)
                .map(SellerItemAmountRequest::sellerId)
                .filter(Objects::nonNull)
                .allMatch(sellerIds::add);
    }

    public ShippingFeeQuoteQuery toQuery() {
        return new ShippingFeeQuoteQuery(postalCode, sellers.stream()
                .map(SellerItemAmountRequest::toSellerItemAmount)
                .toList());
    }
}
