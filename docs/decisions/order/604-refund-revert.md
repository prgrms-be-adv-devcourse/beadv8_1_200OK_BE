---
issue: 604
module: order
summary: 환불이 Kafka로 요청한 취소 기록 되돌리기를 받아, 기록을 지우지 않고 되돌린 시각만 남기고(reverted_at) 같은 판매자 묶음이 출고 전일 때만 되돌리며, 결과를 이유와 함께 Kafka로 돌려줌
status: 확정
date: 2026-11-18
paths: [order/src/main/java/com/ok/order/order/domain/OrderItem.java, order/src/main/java/com/ok/order/order/domain/OrderItemRefund.java, order/src/main/java/com/ok/order/order/domain/RefundRevertOutcome.java, order/src/main/java/com/ok/order/order/application/service/OrderService.java, order/src/main/java/com/ok/order/order/adapter/in/event/RefundRevertKafkaListener.java, order/src/main/java/com/ok/order/order/adapter/out/event/SpringOrderEventPublisher.java]
related: [28, 537, 599, 622, 649, 658, 735]
---

> 이 문서는 #604 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

환불은 판매자 승인 직후 주문에 환불 수량을 기록(내부 API ②, #28)한 뒤 지갑에 돈 반환을 요청한다. 지갑 반환이 끝내 실패하거나 재고 거절 뒤 회수에 성공하면, 주문에는 취소 기록만 남아 그 상품은 배송·정산에서 빠진 채 구매자는 돈도 상품도 받지 못한다. 환불 #622(PR #717)가 이때 `refund.refund-order-revert-requested.v1`로 되돌리기를 요청하게 됐고, 주문이 받아서 되돌리고 결과를 돌려준다(PR #537 합의).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 되돌린 기록 | 줄을 지우지 않고 `order_item_refund.reverted_at`에 시각을 남기고, 환불 수량·뺀 배송비 합계에서 뺀다 | 행을 지우지 않는 팀 규칙. 흔적이 있어야 같은 요청이 다시 왔는지, 되돌린 환불의 기록 요청이 늦게 왔는지 가를 수 있다(ADR 28 "건드릴 때 주의") |
| 출고 판단 | 그 상품이 배송 준비이고 **같은 판매자 묶음의 다른 상품도** 배송 중·배송 완료·구매확정이 아닐 때만 되돌린다 | 모두 환불된 상품은 묶음이 출고돼도 배송 준비로 남는다(#658). 그 뒤 되돌리면 배송 시작이 다시 오지 않아 상품이 배송 준비에 머물고 구매확정·정산도 안 된다. 일부 환불 상품이 배송 중이면 판매자가 실제로 몇 개 보냈는지 알 수 없다 |
| 실패 이유 | `RefundRevertOutcome` 이름을 그대로 `reason`으로 보낸다: `ORDER_ITEM_NOT_FOUND`, `REFUND_NOT_FOUND`, `QUANTITY_MISMATCH`, `ALREADY_CONFIRMED`, `ALREADY_SHIPPED` | 환불은 실패면 처리 중으로 남겨 사람이 보므로 이유는 사람이 읽는 값이다. 코드 값이라 환불의 100자 제한을 넘지 않는다 |
| 같은 요청 반복 | 이미 되돌린 환불이면 다시 "되돌림"으로 답한다(되돌린 시각은 처음 그대로) | 환불은 결과가 없으면 다시 보낸다. 실패로 답하면 이미 되돌렸는데 환불이 사람에게 넘긴다. 환불은 끝난 줄이면 결과를 넘겨 두 번 답해도 안전하다 |
| 늦게 온 기록 요청 | 되돌린 환불 ID로 기록(②)이 다시 오면 422(`REFUND_ALREADY_REVERTED`) | 되살리면 실패로 끝난 환불의 수량이 다시 빠진다(PR #537 댓글의 경합). 기록이 어긋난 경우라 #735처럼 422로 둔다 |
| 기록 없는 되돌리기 | `REFUND_NOT_FOUND`로 실패를 보낸다 | 환불은 기록 성공(204)을 받은 뒤에만 지갑으로 넘어가 정상 흐름에서는 생기지 않는다. 생기면 기록이 어긋난 것이라 사람이 본다 |
| 메시지 주문 ID 불일치 | 예외가 아니라 `ORDER_ITEM_NOT_FOUND` 실패 결과로 답한다 | 예외를 올리면 다시 해도 같아 실패 기록 표에만 남고 환불은 답을 못 받아 오래 기다린다. ID 자체가 빠진 메시지만 결과를 보낼 대상이 없어 실패 기록 표에 남긴다 |
| 동시성 | 기록(②)과 같이 READ COMMITTED로 주문을 잠근다(`findByOrderItemIdForUpdate`) | 배송 시작·구매확정·기록과 차례로 처리돼 출고·구매확정 판단이 최신 상태를 본다. 리스너가 트랜잭션 없이 불러 격리 수준이 그대로 적용된다(ADR 28 "건드릴 때 주의") |
| 결과 발행 | outbox(#666), 키 `orderId` | 되돌림과 결과가 같은 트랜잭션에 남아 롤백되면 결과도 나가지 않는다. 환불이 보낸 요청과 같은 키다 |

## 버린 대안

- **줄 삭제:** 행 삭제 금지 규칙에 어긋나고, 다시 온 요청을 "기록 없음"으로 잘못 답한다.
- **별도 되돌림 표:** 합계를 낼 때마다 조인이 필요하고, 한 환불에 되돌림은 한 번뿐이라 칸 하나로 충분하다.
- **그 상품 상태만으로 출고 판단:** 모두 환불된 상품이 묶음 출고 뒤에도 배송 준비라 위 "출고 판단"의 문제를 막지 못한다.
- **구매확정 전이면 출고돼도 되돌리기:** 배송 시작이 다시 오지 않아 상품이 갇히고, 구매자 문구("상품은 원래대로 배송됩니다", 환불 #717)가 사실과 달라진다.

## 건드릴 때 주의

- 환불 수량·뺀 배송비를 새로 합산하는 곳을 만들면 되돌린 기록(`isReverted`)을 빼야 한다. 지금은 `OrderItem.refundedQuantity`, `deductedShippingFee`만 합산한다.
- `RefundRevertOutcome`의 실패 이름은 환불에 보내는 값이다. 바꾸거나 더하면 환불 담당과 먼저 맞춘다.
- 출고 판단은 주문이 반영한 출고만 본다. 배송이 송장을 등록했지만 주문이 배송 시작을 아직 처리하지 않은 틈에 온 되돌리기는 되돌린다(ADR 28 "그래도 남는 범위"와 같은 한계, 환불 #649 R1).
- 출고된 뒤 실패로 답한 환불은 처리 중으로 남아 사람이 정리한다. 자동으로 정리하는 길(관리자 처리 등)은 아직 없다.
