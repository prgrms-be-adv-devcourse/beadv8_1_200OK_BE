package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerApplyCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "판매자 등록 신청 요청")
public record SellerApplyRequest(

        @Schema(description = "상호명 (50자 이하, 앞뒤 공백 제거)", example = "청송 햇살농원")
        @NotBlank(message = "상호명은 필수입니다.")
        @Size(max = 50, message = "상호명은 50자 이하여야 합니다.")
        String storeName,

        @Schema(description = "대표자명 (50자 이하, 앞뒤 공백 제거)", example = "김영수")
        @NotBlank(message = "대표자명은 필수입니다.")
        @Size(max = 50, message = "대표자명은 50자 이하여야 합니다.")
        String representativeName,

        @Schema(description = "사업자등록번호 (숫자 10자리). 하이픈 없이 보내도 000-00-00000 형식으로 저장됩니다.",
                example = "512-81-04273")
        @NotBlank(message = "사업자등록번호는 필수입니다.")
        @Pattern(regexp = SellerApplyRequest.BUSINESS_NUMBER_FORMAT, message = "사업자등록번호는 숫자 10자리여야 합니다.")
        String businessNumber,

        @Schema(description = "통신판매업 신고번호 (50자 이하, 앞뒤 공백 제거)", example = "제2026-경북청송-0031호")
        @NotBlank(message = "통신판매업 신고번호는 필수입니다.")
        @Size(max = 50, message = "통신판매업 신고번호는 50자 이하여야 합니다.")
        String mailOrderNumber
) {

    /**
     * 저장하는 사업자등록번호 형식. {@code @Pattern}은 전체 일치로 검사하므로 앵커({@code ^}, {@code $})를 붙이지 않는다.
     * 엔티티({@code SellerApplication})도 같은 형식을 검사한다. 컨트롤러 계층은 도메인 계층을 참조할 수 없어(ArchitectureTest)
     * 두 곳에 두며, 한쪽을 바꾸면 다른 쪽도 함께 바꾼다.
     */
    static final String BUSINESS_NUMBER_FORMAT = "\\d{3}-\\d{2}-\\d{5}";

    // jakarta의 @Pattern과 이름이 겹쳐 정규식 Pattern은 패키지 이름을 붙여 쓴다. 요청마다 컴파일하지 않도록 한 번만 만든다
    private static final java.util.regex.Pattern SEPARATORS = java.util.regex.Pattern.compile("[\\s-]");
    private static final java.util.regex.Pattern TEN_DIGITS = java.util.regex.Pattern.compile("\\d{10}");

    /**
     * 요청을 역직렬화하는 시점에 값을 정규화한다. 검증은 정규화된 값을 기준으로 수행된다.
     * - 사업자등록번호: 하이픈·공백 유무와 관계없이 같은 번호가 같은 값으로 저장되어야 중복 신청을 막을 수 있다.
     *   숫자 10자리면 000-00-00000 형식으로 맞추고, 아니면 그대로 두어 형식 검증에서 거절한다.
     * - 그 밖의 값: 앞뒤 공백 제거
     */
    public SellerApplyRequest {
        storeName = trim(storeName);
        representativeName = trim(representativeName);
        businessNumber = normalizeBusinessNumber(businessNumber);
        mailOrderNumber = trim(mailOrderNumber);
    }

    public SellerApplyCommand toCommand() {
        return new SellerApplyCommand(storeName, representativeName, businessNumber, mailOrderNumber);
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

    private static String normalizeBusinessNumber(String value) {
        if (value == null) {
            return null;
        }
        String digits = SEPARATORS.matcher(value).replaceAll("");
        if (!TEN_DIGITS.matcher(digits).matches()) {
            return value.strip();
        }
        return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5);
    }
}
