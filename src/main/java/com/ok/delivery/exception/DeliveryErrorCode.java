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
    MISSING_CARRIER(HttpStatus.UNPROCESSABLE_ENTITY, "Missing carrier", "택배사는 필수입니다."),
    MISSING_TRACKING_NUMBER(HttpStatus.UNPROCESSABLE_ENTITY, "Missing tracking number", "송장번호는 필수입니다."),
    INVALID_TRACKING_NUMBER(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid tracking number", "송장번호는 숫자 10~13자리여야 합니다."),
    CANCELLATION_RESERVED(HttpStatus.CONFLICT, "Cancellation reserved", "취소가 승인된 상품이 있어 송장을 등록할 수 없습니다."),

    // 배송 상품 항목
    INVALID_CANCEL_QUANTITY(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid cancel quantity", "취소 수량은 1 이상이어야 합니다."),
    CANCEL_QUANTITY_EXCEEDED(HttpStatus.CONFLICT, "Cancel quantity exceeded", "취소 수량이 주문 수량을 넘을 수 없습니다."),

    // 배송지
    MISSING_ADDRESS_FIELD(HttpStatus.UNPROCESSABLE_ENTITY, "Missing address field", "배송지 필수 항목이 비어 있습니다."),
    INVALID_RECIPIENT_PHONE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid recipient phone", "연락처는 숫자 10~11자리여야 합니다."),
    INVALID_POSTAL_CODE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid postal code", "우편번호는 숫자 5자리여야 합니다."),

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
    POSTAL_REGION_NOT_FOUND(HttpStatus.NOT_FOUND, "Postal region not found", "등록되지 않은 우편번호입니다."),
    MISSING_REGION_GROUP(HttpStatus.UNPROCESSABLE_ENTITY, "Missing region group", "지역 그룹은 필수입니다."),
    REGION_GROUP_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY, "Region group not found", "존재하지 않는 지역 그룹입니다."),
    INVALID_EXTRA_DELIVERY_DAYS(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid extra delivery days", "추가 소요일은 0 이상이어야 합니다.");

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
