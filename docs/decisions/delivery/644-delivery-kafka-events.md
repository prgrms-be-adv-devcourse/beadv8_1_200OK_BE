---
issue: 644
module: delivery
summary: 배송과 주문 사이 Spring 이벤트를 Kafka로 옮기고, 배송 상태를 상태별 토픽과 outbox로 보내며 구매확정은 받아서 반영한다
status: 확정
date: 2026-11-17
paths: [delivery/src/main/java/com/ok/delivery/adapter/out/event/OutboxDeliveryEventPublisher.java, delivery/src/main/java/com/ok/delivery/application/service/DeliveryOutboxRelayFacade.java, delivery/src/main/java/com/ok/delivery/adapter/in/scheduler/DeliveryOutboxRelayScheduler.java, delivery/src/main/java/com/ok/delivery/adapter/in/event/DeliveryOutboxSendListener.java, delivery/src/main/java/com/ok/delivery/config/DeliveryAsyncConfig.java, delivery/src/main/java/com/ok/delivery/application/service/ShipmentService.java, delivery/src/main/java/com/ok/delivery/application/dto/CarrierTrackingRequestedEvent.java]
related: [599, 603, 648, 558, 668, 600]
---

> 이 문서는 #644 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

모듈별 서버로 나누면 모듈 간 Spring 이벤트는 다른 서버에 닿지 않는다. 그래서 배송이 주문과 주고받던 이벤트(결제 완료 받기, 배송 시작·완료 보내기)를 Kafka로 옮기고, 배송 상태(`PREPARING` → `SHIPPING` → `DELIVERED`)가 바뀔 때마다 주문에 알린다. 구매확정(`COMPLETED`)은 주문이 정하므로 배송은 받아서 반영한다. 주문 쪽 변경은 #603(PR #686)이 한다.
범위 밖: 택배사(tracker) 연동 방식(HTTP 조회와 웹훅 #668 그대로), 택배사 중간 상태(집하·이동 중·배송 출발) 저장과 발송, 판매자 출고지 수신(#558), Kafka 추적 헤더.

| 상태 | 계기 | 방향·토픽 |
|---|---|---|
| (생성) | 주문의 결제 완료 | 주문 → 배송 `order.order-paid.v1` (그룹 `delivery.shipment-create`) |
| `PREPARING` | 배송 생성 직후 | 배송 → 주문 `delivery.shipment-created.v1` |
| `SHIPPING` | 판매자 송장 등록 API | 배송 → 주문 `delivery.shipment-dispatched.v1` |
| `DELIVERED` | 택배사 알림(웹훅)·주기 조회가 모두 `ShipmentService.deliver()`로 모임 | 배송 → 주문 `delivery.shipment-delivered.v1` |
| `COMPLETED` | 주문의 구매확정(구매자 버튼, 배송 완료 3일 뒤 자동) | 주문 → 배송 `order.order-product-confirmed.v1` (그룹 `delivery.purchase-confirm`) |

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 작업 단위 | 이슈 #644 하나에 이벤트별 PR(결제 완료 수신, 배송 시작, 배송 완료, 배송 생성 발행, 구매확정 수신). 발행 PR은 outbox 위에, 구매확정 수신은 결제 완료 수신 위에 `gh stack`으로 쌓는다 | 한 PR에 몰지 말고 이벤트 하나씩 옮기자는 팀 피드백(사용자 결정). 각 PR이 필요한 기반 위에만 올라가 기존 PR을 다시 쌓지 않는다 |
| 보내는 방식 | 배송 전용 `delivery_outbox` 표에 상태 변경과 같은 트랜잭션으로 줄을 남긴다. 커밋 뒤 이름 붙은 전용 실행기(`delivery-outbox-send`, #417)의 `@Async`로 그 줄을 바로 보내고, 못 보낸 줄은 스케줄러(30초)가 보낸다(사용자 결정). 스케줄러는 남은 지 10초 지난 줄만 고르고, "보냄"은 `PENDING`일 때만 바꾸는 조건부 UPDATE다 | 롤백되면 나가지 않고 브로커 장애가 끝나면 빠짐없이 나간다. 주문의 Spring 경로가 지워지면 이 경로뿐이라 상태 변경이 최대 30초 늦지 않게 한다(측정: 커밋 뒤 토픽 도착 16ms). 요청 스레드가 넘기므로 대기열 있는 `platform`이고, 브로커 확인 중에는 커넥션을 잡지 않아 2스레드. 10초는 브로커 확인 대기 상한과 같다. 두 경로가 겹치면 한 번 더 나가고 주문이 무시한다. 10초·조건부 UPDATE·중복 허용은 구현 중 설계다 |
| 토픽 모양 | 상태별 토픽. `status` 필드를 둔 토픽 하나로 합치지 않는다 | 상태마다 필드가 달라 합치면 null 필드가 많다. 받는 쪽이 필요한 상태만 구독하고 상태별로 재처리·DLT를 본다. 주문이 순서 뒤바뀜을 이미 처리해 단일 토픽의 순서 보장 이점이 작다(사용자 결정) |
| 받다가 실패 | 1·2·4초 재시도 뒤 그룹별 DLT(`<받은 토픽>.<그룹>.DLT`). `IllegalArgumentException`과 읽지 못한 JSON은 재시도 없이 바로 DLT. 로그에는 배송지 대신 토픽·파티션·오프셋·키만 | 배송 생성이 빠지면 결제된 주문이 배송되지 않아 건너뛸 수 없다. 그룹은 리스너마다 `<모듈>.<용도>`(#648) |
| 전환 순서 | 이중 경로. 배송이 Spring·Kafka 둘 다 받고 보낸 뒤, 주문(#686) 머지 다음에 배송의 Spring 경로를 지운다 | 머지 날짜가 묶이지 않는다. 주문 담당 동의 |
| 택배사 추적 요청 | 모듈 간 이벤트 대신 배송 안 이벤트(`CarrierTrackingRequestedEvent`)를 커밋 뒤에 받는다 | 같은 서버 안의 일이라 브로커를 거칠 이유가 없다. 재시작 때 배송 중인 건을 다시 요청하는 복구가 있다 |
| 배송 시작 계기 | 판매자가 프론트에서 배송 API로 송장을 등록할 때. 회원 모듈을 거치지 않는다 | 판매자 ID는 로그인 토큰에서 얻는다(사용자 확인) |
| 배송 생성 메시지 | 판매자 배송 1건당 1메시지 `{shipmentId, orderId, sellerId, orderItemIds, createdAt}`. `createdAt`은 저장된 엔티티 값 | 배송 시작·완료와 같은 단위(shipment)라 주문이 같은 방식으로 처리한다(사용자 결정). 메시지 값이 DB 값과 같게 한다 |
| 구매확정 | 주문이 정하고 배송은 받아 항목을 확정한다. 배송 완료 상태에서 모든 항목이 확정되면 `COMPLETED`. 확정 판단은 `Shipment.confirmPurchase`가 한다 | 구매자 확정과 자동 확정이 이미 주문에 있다(2026-10-19 합의). 같은 결정을 두 모듈이 하지 않는다 |
| 구매확정 예외 | 없는 주문 상품은 `IllegalArgumentException`으로 바로 DLT. 배송 완료 전 구매확정은 거절해 재시도 뒤 DLT. 이미 거래완료면 무시 | 없는 상품은 데이터 불일치라 묻히면 안 된다. 항목만 확정해 두면 거래완료로 바꿀 계기가 없어 멈춘 배송이 생긴다 |
| 멱등 | 결제 완료는 주문 ID 확인과 `(order_id, seller_id)` 유니크 제약, 구매확정은 배송 행·항목 행 잠금과 상태 확인. 처리 기록 표(inbox)는 두지 않는다 | 주문 ID·주문 상품 ID가 자연스러운 멱등 키다 |

## 버린 대안

- **보내는 방식:** outbox 없이 커밋 뒤 바로 `send`는 브로커 장애·앱 종료 때 메시지를 잃는다. Modulith 외부화(`@Externalized`)는 공통 의존성이라 팀 결정이 필요하다. 스케줄러만 쓰면 최대 30초 늦고 처리량이 약 3.3건/초다. 이름 없는 `@Async`는 모든 리스너가 쓰는 `event` 실행기를 같이 써 브로커가 느리면 다른 리스너가 밀린다. `@ApplicationModuleListener`는 이름 없는 `@Async`(공용 `event` 실행기)와 `REQUIRES_NEW`를 강제해, 실행기를 나눌 수 없고 브로커 확인 중에도 커넥션을 잡는다.
- **토픽:** `delivery.shipment-status-changed.v1` 하나에 `status` 필드. 같은 주문의 순서가 보장되지만 null 필드가 늘고, 이미 합의한 주문(#686)과 PR을 함께 바꿔야 한다.
- **작업 단위:** 세 이벤트를 한 PR(닫은 #645)로. 리뷰 범위가 넓어 팀 피드백으로 나눴다. 구매확정 수신을 위해 결제 완료 수신 PR 위로 발행 PR들을 다시 쌓는 한 줄 스택은 기존 PR을 rebase·재생성해야 해서 버렸다.
- **구매확정:** 배송이 배송 완료 N일 뒤 스스로 확정하는 배치. 주문에 이미 있고 합의와 어긋난다.
- **배송 생성 메시지:** 주문 1건당 1메시지(판매자 배송 목록). 다른 배송 메시지와 단위가 달라진다.
- **중간 상태:** 택배사의 집하·이동 중 같은 상태도 보내기. 배송 상태에 없는 단계이고 받을 쪽이 없다.

## 건드릴 때 주의

- 주문 PR #686은 주문의 Spring 경로를 한꺼번에 지운다. 배송의 결제 완료 수신, 배송 시작·완료 발행 PR이 모두 머지된 뒤에 머지해야 한다. 하나라도 빠지면 그 이벤트가 상대에 닿지 않는다.
- 토픽이 상태별이라 같은 주문이라도 토픽 사이 순서는 보장되지 않는다. 주문이 주문 상품 상태로 중복·순서 뒤바뀜을 처리한다는 전제다. 이 전제가 바뀌면 토픽 결정을 다시 본다.
- 즉시 발송은 같은 주문의 순서를 지키지 않는다. 스케줄러는 즉시 발송에서 남은 줄만 보내며, 30초마다 100줄이라 장애 뒤 복구 처리량은 약 3.3건/초다.
- 즉시 발송 실행기의 대기열(1000)이 차면 커밋한 요청 스레드가 기다린다(#417 정책). 브로커가 오래 죽으면 작업마다 10초씩 걸려 찰 수 있다. (미확인) 부하에서 측정하지 않았다.
- min-age는 대기열에서 기다린 시간을 빼 주지 않는다. 브로커 장애 때 10초 넘게 기다린 즉시 발송과 스케줄러가 같은 줄을 함께 보낼 수 있다. 대부분은 `send()`가 보내기 전에 `PENDING`인지 다시 보는 것으로 걸러진다.
- 즉시 발송 리스너도 커밋 뒤 리스너라 Modulith가 outbox 줄마다 `event_publication` 행을 하나 더 쌓는다(업무 트랜잭션의 INSERT, 발송 뒤 완료 표시). 지금은 그대로 둔다.
- 릴레이는 줄을 잠그지 않는다. 서버를 여러 대 띄우면 같은 줄을 여러 번 보낸다(주문은 중복을 무시한다). 모듈별 서버로 나눌 때 다시 본다.
- DLT에는 받는 DTO로 다시 직렬화한 값이 들어간다(DTO에 없는 필드는 빠짐). 읽지 못한 JSON만 원본 그대로다.
- `deliveredAt`은 주문 자동 구매확정(배송 완료 3일 뒤)의 기준 시각이다. 배송에 저장된 완료 시각을 그대로 보낸다.
- 구매확정은 주문 상품으로 배송을 찾아 잠근다. 이때 items를 fetch join하지 않는다. fetch join하면 조건에 맞는 항목만 담겨 "모두 확정" 판정이 틀어진다.
- 그 잠금 조회(`findByOrderItemIdForUpdate`)는 네이티브 `FOR UPDATE`로 배송 행과 항목 행을 함께 잠근다. JPQL + `@Lock`은 `FOR UPDATE OF`로 배송 행만 잠그고 join한 항목은 일반 읽기로 읽는데, 이 읽기가 잠금을 기다리기 전에 REPEATABLE READ 스냅숏을 정한다. 그러면 같은 배송의 두 항목이 동시에 확정될 때 뒤 트랜잭션이 앞 확정을 못 봐서 두 항목 모두 확정인데 `DELIVERED`에 남는다(동시 호출에서 재현, 수정 뒤 동시 확정 통합 테스트로 확인). JPQL 서브쿼리와 잠금 범위 `EXTENDED` 힌트도 같은 문제가 나서 버렸다. 주문 상품으로 배송을 찾는 조회를 바꿀 때는 찾는 읽기까지 잠금 읽기인지 본다.
- outbox에 보낸 줄을 지우는 정리 작업이 없어 표가 계속 커진다.

## 후속 (2026-11-18)

- 주문(#686) 머지 뒤 배송의 Spring 경로(결제 완료 수신 리스너, 배송 시작·완료 Spring 발행)와 `common.event`의 해당 이벤트 클래스를 지웠다(#721).
