package com.ok.delivery.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DeliveryErrorCode implements ErrorCode {

    // 배송
    SHIPMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Shipment not found", "배송 정보를 찾을 수 없습니다."),
    INVALID_SHIPMENT_STATE(HttpStatus.CONFLICT, "Invalid shipment state", "현재 배송 상태에서는 처리할 수 없습니다."),
    TRACKING_NUMBER_DUPLICATED(HttpStatus.CONFLICT, "Tracking number duplicated", "이미 사용된 송장번호입니다."),

    // 배송지 변경
    ADDRESS_CHANGE_NOT_ALLOWED(HttpStatus.CONFLICT, "Address change not allowed", "배송지를 변경할 수 없는 상태입니다."),
    ADDRESS_CHANGE_ALREADY_PENDING(HttpStatus.CONFLICT, "Address change already pending", "처리 대기 중인 배송지 변경 요청이 있습니다."),
    ADDRESS_CHANGE_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "Address change request not found", "배송지 변경 요청을 찾을 수 없습니다."),
    ADDRESS_CHANGE_ALREADY_PROCESSED(HttpStatus.CONFLICT, "Address change already processed", "이미 처리된 배송지 변경 요청입니다."),

    // 배송 중단
    REGION_SUSPENDED(HttpStatus.CONFLICT, "Region suspended", "배송이 중단된 지역입니다."),
    SUSPENSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Suspension not found", "배송 중단 정보를 찾을 수 없습니다."),
    SUSPENSION_OVERLAPPED(HttpStatus.CONFLICT, "Suspension overlapped", "같은 지역 그룹의 배송 중단 기간이 겹칩니다."),

    // 도서산간 우편번호
    POSTAL_REGION_DUPLICATED(HttpStatus.CONFLICT, "Postal region duplicated", "이미 등록된 우편번호입니다."),
    POSTAL_REGION_NOT_FOUND(HttpStatus.NOT_FOUND, "Postal region not found", "등록되지 않은 우편번호입니다.");

    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }

    @Override
    public String slug() {
        return "delivery/" + ErrorCode.super.slug();
    }
}
