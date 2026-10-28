package com.ok.payout.service.dto;

// 구매확정된 주문상품 정보. 정산후보 생성(PayoutCandidateService)의 입력
// 서비스는 주문 모듈과의 계약 타입(common.event.OrderProductConfirmedEvent)을 직접 받지 않고,
// 리스너(event 패키지)가 이벤트를 이 커맨드로 바꿔 넘긴다 (structure.md 이벤트 수신 규칙)
// 필드는 OrderProductConfirmedEvent와 같다. 이벤트 필드가 바뀌면 리스너의 변환과 함께 바꾼다
// 값 검증(null, 음수)은 서비스에서 한다. 리스너는 값을 옮기기만 한다
public record PurchaseConfirmedCommand(
        // 주문 ID
        Long orderId,
        // 주문항목 ID (정산후보의 중복 처리 기준)
        Long orderItemId,
        // 판매자 회원 ID
        Long sellerId,
        // 상품 총액 (원). 이 금액에서 판매 수수료를 계산한다
        Long totalItemPrice,
        // 판매자 묶음 배송비 (원). 같은 묶음의 상품마다 같은 값이 온다. 전액 판매자 몫, 무료배송이면 0
        Long sellerShippingPrice
) {
}
