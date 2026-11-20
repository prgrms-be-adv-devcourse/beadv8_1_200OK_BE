---
issue: 603
module: order
summary: 결제 완료를 order.order-paid.v1(outbox)로 보내고 배송 시작·완료를 Kafka로만 받으며, 배송(#644)이 Spring·Kafka 이중 경로로 먼저 머지한 뒤 머지해 주문의 Spring 경로를 한 번에 지움
status: 확정
date: 2026-11-16
paths: [order/src/main/java/com/ok/order/order/adapter/out/event/SpringOrderEventPublisher.java, order/src/main/java/com/ok/order/order/adapter/in/event/ShipmentKafkaListener.java, order/src/main/java/com/ok/order/order/adapter/out/event/dto/OrderPaidMessage.java]
related: [644, 648, 658, 666, 599, 343]
---

> 이 문서는 #603 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

주문은 결제 완료를 Spring 이벤트(`OrderPaidEvent`)로 배송에 보내고, 배송 시작·완료(`ShipmentDispatchedEvent`, `ShipmentDeliveredEvent`)를 Spring 이벤트로 받았다. 팀은 모듈 간 알림을 Kafka로 옮기는 중이다(#599). 배송 쪽은 #644에서 같은 전환을 한다.

11/16 #644 댓글로 배송과 메시지 약속과 전환 순서를 맞췄다.

범위 밖: 배송 쪽 변경(#644), 공용 이벤트 클래스 삭제(#343).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 전환 순서 | 배송이 먼저 Spring·Kafka 둘 다 받고 보내는 PR을 머지 → 이 PR 머지 → 배송이 Spring 쪽 삭제 (#644 ④, 주문 동의) | 받는 쪽이 먼저 머지한다(CONVENTION 11.4). 배송이 두 길을 모두 열어 두므로 주문은 이 PR에서 Spring 발행·수신을 바로 지울 수 있다 |
| 토픽 | `order.order-paid.v1`, `delivery.shipment-dispatched.v1`, `delivery.shipment-delivered.v1`, 키 모두 `orderId` | #648 규칙. 같은 주문의 배송 시작·완료가 같은 파티션에 쌓인다 |
| 주소 필드 이름 | 보내는 메시지에서만 `zipCode` → `postalCode` | 배송 요청(#644). 주문 안의 이름과 DB 칼럼은 그대로 두고 Kafka DTO(`OrderPaidMessage`)에서만 바꾼다 |
| 받는 그룹 | 배송 시작·완료 모두 `order.shipping-status` | #648 `<모듈>.<용도>`. 한 주문의 배송 상태라 같은 용도로 묶는다(결제 결과 `order.payment-result`와 같은 방식) |
| 순서·중복 | 배송에 순서 보장을 요구하지 않음 | 주문 상품 상태로 처리해 결과가 같다. 배송 완료는 배송 준비에서도 바로 완료로 바뀌고, 뒤에 온 배송 시작과 다시 온 배송 완료는 무시된다 |
| 받는 토픽 선언 | 주문도 배송 토픽 2개를 `NewTopic`으로 선언 | 배송 PR 머지 전에는 토픽을 만드는 곳이 없어 "토픽 없음" 경고가 계속 난다(#602와 같은 방식) |
| 값 검사 | 주문·판매자 ID, 배송 완료 시각이 없으면 `IllegalArgumentException` | 다시 받아도 처리할 수 없으므로 실패 기록 표에 바로 남긴다(#666). 배송 상태는 다시 오지 않아 버리지 않고 남기는 게 중요하다 |

## 버린 대안

- **주문도 이중 경로(Spring+Kafka):** 머지 순서와 관계없이 안전하지만, 배송이 이미 두 길을 열어 두기로 해서 한쪽만 두 길이면 충분하다. 주문까지 두 길이면 후속 PR이 하나 더 필요하다.
- **주문 안 이름도 `postalCode`로 바꾸기:** 엔티티·DB 칼럼·API 응답까지 바뀌어 범위가 커진다.

## 건드릴 때 주의

- 이 PR은 배송 PR보다 먼저 머지하면 안 된다. 결제 완료가 Spring으로 나가지 않아 배송이 만들어지지 않는다.
- 결제 완료 뒤 배송용 메시지는 `WalletPaymentKafkaIntegrationTest`가 outbox 줄(`order_outbox`)로 확인한다(`OrderModuleTest`는 #602 후속에서 지웠다). 이 전환으로 `SpringOrderEventPublisher`에 Spring 발행이 하나도 남지 않아 `ApplicationEventPublisher` 의존을 뺐다.
- 메시지 필드는 배송과의 약속이다. 바꾸면 배송 DTO도 같이 바꿔야 한다.
