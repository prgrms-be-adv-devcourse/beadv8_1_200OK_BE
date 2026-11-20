package com.ok.payout.application.port.in;

// 구매확정된 주문상품 정보. 정산후보 생성(PayoutCandidateService)의 입력
// 서비스는 Kafka 메시지 모양(adapter/in/event/dto의 OrderProductConfirmedMessage)을 직접 받지 않고,
// 리스너(adapter/in/event)가 메시지를 이 커맨드로 바꿔 넘긴다 (payout/CLAUDE.md "의존 방향")
// 필드는 OrderProductConfirmedMessage와 같다. 메시지 필드가 바뀌면 메시지의 변환(toCommand)과 함께 바꾼다
// 값 검증(null, 음수)은 서비스를 부르기 전에 입구(adapter/in/event의 PayoutInboundEventValidator)에서 한다. 서비스는 검증하지 않으므로,
// 이 입력 포트를 부르는 새 입구를 만들면 같은 검사를 먼저 거쳐야 한다
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
