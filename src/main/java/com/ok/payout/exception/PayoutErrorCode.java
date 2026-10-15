package com.ok.payout.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PayoutErrorCode implements ErrorCode {

    MISSING_ORDER_ITEM_ID(HttpStatus.UNPROCESSABLE_ENTITY, "Missing order item id", "주문항목 ID는 필수입니다."),
    MISSING_SELLER_ID(HttpStatus.UNPROCESSABLE_ENTITY, "Missing seller id", "판매자 ID는 필수입니다."),
    INVALID_TOTAL_ITEM_PRICE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid total item price", "상품 총액은 필수이며 0 이상이어야 합니다."),
    INVALID_SHIPPING_PRICE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid shipping price", "배송비는 필수이며 0 이상이어야 합니다."),
    INVALID_ADJUSTMENT_ID(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid adjustment id", "수동 조정 번호는 1 이상이어야 합니다."),
    MISSING_ADMIN_ID(HttpStatus.UNPROCESSABLE_ENTITY, "Missing admin id", "수동 조정에는 관리자 ID가 필수입니다."),
    MISSING_ADJUSTMENT_REASON(HttpStatus.UNPROCESSABLE_ENTITY, "Missing adjustment reason", "수동 조정에는 조정 사유가 필수입니다."),
    ADJUSTMENT_REASON_TOO_LONG(HttpStatus.UNPROCESSABLE_ENTITY, "Adjustment reason too long", "조정 사유는 255자 이하여야 합니다."),
    MISSING_PAYOUT_REQUESTED_AT(HttpStatus.INTERNAL_SERVER_ERROR, "Missing payout requested at", "정산 Job 실행에는 requestedAt 파라미터가 필요합니다."),
    MISSING_CANDIDATE_ID(HttpStatus.INTERNAL_SERVER_ERROR, "Missing candidate id", "정산 항목은 저장된 정산후보로만 만들 수 있습니다."),
    UNSETTLED_CANDIDATES_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "Unsettled candidates not found", "정산 대상 판매자의 미정산 후보가 없습니다. 조회 조건 불일치를 확인하세요."),
    DRAFT_VERIFICATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Draft verification failed", "임시 정산이 스냅샷 대상과 일치하지 않습니다. 정산을 확정하지 않습니다."),
    PAYOUT_RUN_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Payout run failed", "정산 실행에 실패했습니다."),
    PAYOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payout not found", "정산을 찾을 수 없습니다."),
    PAYOUT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Payout access denied", "정산을 조회할 권한이 없습니다."),
    // 정산의 snapshot_id는 외래키라 정상이면 생길 수 없음. 데이터가 깨진 경우라 클라이언트 잘못(404)이 아닌 서버 오류로 둠
    PAYOUT_SNAPSHOT_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "Payout snapshot not found", "정산의 스냅샷을 찾을 수 없습니다."),
    // 형태만 공개하고 실제 동작은 막아 둔 API(1건 수동 실행, 수동 조정)를 표시하는 임시 코드
    // 두 API가 모두 열리면 이 항목도 지움. 막아 둔 이유는 각 API의 TODO(논의 필요) 주석 참고
    FEATURE_NOT_SUPPORTED(HttpStatus.NOT_IMPLEMENTED, "Feature not supported", "아직 지원하지 않는 기능입니다.");

    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }

    /**
     * 다른 모듈의 에러코드와 type URI가 겹치지 않도록 모듈 접두사를 붙인다.
     * 예) https://.../problems/payout/missing-seller-id
     */
    @Override
    public String slug() {
        return "payout/" + ErrorCode.super.slug();
    }
}
