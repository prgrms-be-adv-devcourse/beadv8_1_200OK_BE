package com.ok.payout.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PayoutErrorCode implements ErrorCode {

    // 구매확정 이벤트 값 검증용. 규칙(exception.md)상 이벤트로 받은 값의 오류는 표준 예외 대상이지만,
    // 정산 이벤트·배치 경로는 #117 협의 전까지 기존처럼 PayoutErrorCode를 씀
    // (검증 위치 정리는 원래 #90 범위였으나 10/26 범위 조정으로 구매확정 이벤트 처리와 함께 Kafka 전환 때로 미룸, #52)
    MISSING_ORDER_ID(HttpStatus.UNPROCESSABLE_ENTITY, "Missing order id", "주문 ID는 필수입니다."),
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
    // 판매자 draft 합계(헤더)가 항목 합계와 다름 (#290). 같은 코드가 같은 순간에 만드는 값이라, 다르면 draft를 만드는 코드 자체가 망가진 상태
    // 데이터 문제가 아니라 누군가 코드를 고치다 생긴 사고라는 것을 실패 기록(EXIT_MESSAGE)·로그에서 바로 알 수 있게 전용 코드로 둠
    DRAFT_SELLER_TOTAL_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "Draft seller total mismatch", "판매자 임시 정산 합계가 항목 합계와 다릅니다. 임시 정산을 만드는 코드 오류이므로 최근 변경을 확인하세요."),
    // 판매자 draft 항목의 스냅샷 번호가 묶인 헤더와 다름 (#290). 항목은 헤더의 번호를 그대로 복사해 만들어서, 다르면 항목을 만드는 코드가 망가진 상태
    // verify는 이번 스냅샷 기준으로 항목을 비교하고 commitStep은 헤더 기준으로 정산해서, 이런 항목은 검증 없이 지급될 수 있음 → 전체를 멈춤
    DRAFT_ITEM_SNAPSHOT_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "Draft item snapshot mismatch", "판매자 임시 정산 항목의 스냅샷이 묶인 판매자 정산과 다릅니다. 임시 정산 항목을 만드는 코드 오류이므로 최근 변경을 확인하세요."),
    // 이번 스냅샷에서 실패로 기록된 후보가 draft에 들어감 (#290). draft 대상 조회 조건(실패 후보 제외)이 망가진 상태라 위와 같은 코드 고장
    // 주문 단위로 빼지 않고 전체를 멈추는 이유: 이 후보에는 실패 기록이 이미 있어 결과 검증 실패를 따로 남길 수 없고(UK), 공통 조회 조건의 버그라 다른 주문도 믿기 어려움
    // 코드 버그가 아닌 경로도 하나 있음: 판매자 draft 저장이 DB에는 커밋됐는데 호출 쪽에 예외로 돌아오면(커밋 응답 유실 등) 그 판매자 후보가 UNEXPECTED_ERROR로도 기록됨
    //   이때도 멈추는 편이 안전함 (UNEXPECTED_ERROR는 잠그지 않아, 원인을 확인하고 재실행하면 새 스냅샷에서 다시 처리됨)
    DRAFT_INCLUDES_FAILED_CANDIDATE(HttpStatus.INTERNAL_SERVER_ERROR, "Draft includes failed candidate", "실패로 기록된 정산후보가 임시 정산에 들어갔습니다. draft 대상 조회 조건 오류이므로 최근 변경을 확인하세요."),
    PAYOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payout not found", "정산을 찾을 수 없습니다."),
    PAYOUT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Payout access denied", "정산을 조회할 권한이 없습니다."),
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
