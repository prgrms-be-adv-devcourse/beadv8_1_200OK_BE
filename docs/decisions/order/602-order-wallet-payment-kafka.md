---
issue: 602
module: order
summary: 주문 생성(결제 요청)을 Kafka로도 보내고(orderSummary 추가) 지갑의 결제 결과를 Kafka로도 받으며, 지갑이 Kafka로 옮길 때까지 Spring 길을 함께 둬 머지 순서와 관계없이 결제가 멈추지 않게 함
status: 진행 중
date: 2026-11-16
paths: [order/src/main/java/com/ok/order/order/adapter/out/event/SpringOrderEventPublisher.java, order/src/main/java/com/ok/order/order/adapter/in/event/WalletPaymentKafkaListener.java, order/src/main/java/com/ok/order/order/adapter/in/event/WalletPaymentEventListener.java, order/src/main/java/com/ok/order/order/domain/Order.java]
related: [666, 640, 648, 657, 656, 599, 343]
---

> 이 문서는 #602 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

주문은 지갑에 결제를 Spring 이벤트(`OrderCreatedEvent`)로 요청하고 결과(`WalletPaymentCompletedEvent`, `WalletPaymentFailedEvent`)를 Spring 이벤트로 받았다. 팀은 모듈 간 알림을 Kafka로 옮기는 중이고(#599), 지갑은 주문 생성을 Kafka로 받고 결과를 Kafka로 보내는 PR(#640)을 준비했다. 지갑과 "주문이 먼저 머지하고 지갑이 뒤에 머지한다"로 정했다.

지갑은 이용 내역에 주문 요약을 보여 주려고 주문 생성 메시지에 `orderSummary`를 요청했다(지갑 #609에서 저장·표시).

범위 밖: 결제 만료 요청(#656), 구매확정(#600)·결제 완료→배송(#603) 전환, 공용 이벤트 클래스 삭제(#343).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 전환 방식 | 주문 생성은 Spring + Kafka로 함께 보내고, 결제 결과는 Spring + Kafka로 함께 받는다. 지갑이 Kafka로만 주고받으면 후속 PR에서 Spring 길을 지운다 (사용자 결정) | 주문이 먼저 머지하면 그동안 지갑은 Spring으로만 받는다. 주문이 Kafka로만 보내면 결제가 멈춘다. 지갑은 주문 ID로 처리 기록을 남겨 두 길로 받아도 한 번만 차감하고, 주문은 결제 대기가 아닌 주문에 온 결과를 무시해 두 번 받아도 한 번만 반영한다 |
| 주문 생성 발행 위치 | `publishCreated`(주문 잠금 + 결제 요청 시각 기록) 트랜잭션 안에서 outbox에 남긴다(#666) | 결제 요청 시각 기록과 메시지가 함께 커밋되거나 함께 롤백된다. 재고 선점이 실패한 주문은 `publishCreated`까지 가지 않아 메시지도 나가지 않는다 |
| 메시지 키 | `orderId` | 지갑의 결제 결과도 키가 `orderId`라 한 주문의 메시지가 같은 기준으로 같은 파티션에 순서대로 쌓인다 |
| `orderSummary` | 첫 주문 상품의 상품 이름 + 나머지가 있으면 " 외 N건". N은 나머지 주문 상품 줄 수(옵션이 다르면 따로 셈). 한 줄이면 이름만. 자르지 않음 (지갑 요청 + 사용자 결정) | 지갑이 요청한 모양이다. 상품 이름은 최대 100자라 지갑 칸(120자)에 들어간다. 주문 상품은 최소 1줄이고 상품 이름은 필수라 항상 값이 있다 |
| 첫 상품 기준 | ID가 가장 작은 주문 상품 | 묶음·주문 상품 목록은 `@OrderBy`가 없어 DB에서 읽은 순서가 정해져 있지 않다. 주문서 순서대로 저장되므로 ID 순서가 주문서 순서다 |
| 받는 그룹 | 완료·실패 모두 `order.payment-result` 하나 (#648, 이슈 #602 본문) | 한 주문의 결제 결과라는 같은 용도다. 정산도 결제 완료를 받으므로 모듈마다 그룹을 나눠 각자 모두 받는다 |
| 받는 토픽 선언 | 주문도 결제 결과 토픽 2개를 `NewTopic`으로 선언한다 (정산과 같은 방식) | 지갑 #640이 머지되기 전에는 결제 실패 토픽을 선언하는 곳이 없어, 리스너가 "토픽 없음" 경고를 계속 찍는다. 있으면 그대로라 지갑이 같은 선언을 해도 충돌하지 않는다 |
| 값 검사 | `orderId`가 없으면 `IllegalArgumentException` | 다시 받아도 처리할 수 없으므로 주문 오류 처리기가 다시 시도하지 않고 바로 실패 기록 표에 남긴다(#666) |
| 리스너 트랜잭션 | Kafka 리스너에 `@Transactional`을 붙이지 않음 | 결제 실패는 서비스가 자기 트랜잭션으로 먼저 커밋한 뒤 재고 해제를 부른다(#657) |

## 버린 대안

- **Kafka로만 보내고 받기:** 코드는 가장 적지만 지갑 #640이 머지될 때까지 결제가 멈춘다. 두 PR을 같은 시각에 머지하는 방법은 팀 규칙(컨슈머 먼저 머지, CONVENTION 11.4)과 맞지 않고, 둘 중 하나가 되돌려지면 다시 멈춘다.
- **`orderSummary`를 상품 종류 수로 세기:** 같은 상품의 옵션 두 줄을 1건으로 보면 "사과" 하나로 보이지만, 주문 상세는 줄 단위로 보여 줘서 화면과 어긋난다.
- **`orderSummary`를 지갑 길이에 맞게 자르기:** 주문 상품 이름이 100자라 넘칠 일이 없고, 자르는 규칙이 두 모듈에 생긴다.
- **결제 결과 그룹을 리스너마다 나누기:** 이슈와 지갑 합의에서 `order.payment-result` 하나로 정했다. 완료와 실패를 따로 다시 읽을 일이 생기면 그때 나눈다.

## 건드릴 때 주의

- 지갑 #640이 머지되면 지갑은 쌓인 `order.order-created.v1`를 처음부터 읽는다. 이미 Spring으로 처리한 주문은 다시 차감하지 않고 처음 결과를 Kafka로 다시 보내고, 주문은 같은 결과라 무시한다. Spring으로 처리 못 한 주문이 그사이 정리로 취소됐다면 `[ORDER_PAID_AFTER_CANCELED]`가 남는다(지금도 있는 위험, #656에서 닫는다).
- 같은 결제 실패가 두 길로 오면 재고 해제가 두 번 불린다. 상품의 해제는 여러 번 불러도 결과가 같다(#605, #657).
- 후속 PR에서 지울 것: `SpringOrderEventPublisher.publishOrderCreatedEvent`의 Spring 발행, `WalletPaymentEventListener`(둘 다 `TODO(#602)`). 지우기 전에 지운 리스너 앞 `event_publication` 미완료가 0건인지 확인한다. `OrderModuleTest`의 Spring 결제 결과 시나리오는 Kafka 통합 테스트로 옮긴다.
- 메시지 필드는 지갑과의 약속이다. 바꾸면 지갑 DTO(`OrderCreatedMessage`, `WalletPayment*Message`)도 같이 바꿔야 한다.
