package com.ok.member.seller.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SellerErrorCode implements ErrorCode {
    // 판매자 신청
    SELLER_APPLICATION_IN_PROGRESS(HttpStatus.CONFLICT, "Seller application in progress", "심사 중인 판매자 신청이 있습니다."),
    ALREADY_SELLER(HttpStatus.CONFLICT, "Already seller", "이미 판매자로 승인된 회원입니다."),
    DUPLICATE_BUSINESS_NUMBER(HttpStatus.CONFLICT, "Duplicate business number", "이미 신청된 사업자등록번호입니다."),
    SELLER_APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Seller application not found", "판매자 신청을 찾을 수 없습니다."),
    INVALID_SELLER_APPLICATION_STATUS(HttpStatus.CONFLICT, "Invalid seller application status",
            "현재 신청 상태에서는 처리할 수 없습니다."),
    INVALID_SELLER_APPLICATION_FIELD(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid seller application field",
            "판매자 신청 정보가 올바르지 않습니다."),
    INVALID_BUSINESS_NUMBER(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid business number",
            "사업자등록번호는 000-00-00000 형식이어야 합니다."),
    INVALID_REJECT_REASON(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid reject reason", "거절 사유가 올바르지 않습니다."),
    SELLER_APPLICATION_CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Seller application concurrent modification",
            "다른 관리자가 먼저 처리했습니다. 새로고침 후 다시 확인해 주세요."),

    // 판매자 프로필
    SELLER_NOT_APPROVED(HttpStatus.FORBIDDEN, "Seller not approved", "판매자 승인을 받은 회원만 이용할 수 있습니다."),
    SELLER_PROFILE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Seller profile already exists", "이미 등록된 판매자 프로필이 있습니다."),
    SELLER_PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "Seller profile not found", "판매자 프로필을 찾을 수 없습니다."),
    INVALID_PROFILE_IMAGE_URL(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid profile image url",
            "업로드한 판매자 프로필 사진 주소가 아닙니다."),
    INVALID_SELLER_PROFILE_FIELD(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid seller profile field",
            "판매자 프로필 정보가 올바르지 않습니다."),
    INVALID_CUSTOMER_SERVICE_PHONE(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid customer service phone",
            "고객센터 연락처는 054-873-2210, 1588-0000 같은 형식이어야 합니다."),
    ;

    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }

    @Override
    public String slug() {
        return "member/" + ErrorCode.super.slug();
    }

}
