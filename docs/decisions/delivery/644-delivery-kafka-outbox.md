---
issue: 644
module: delivery
summary: 배송의 결제 완료 수신과 배송 시작·완료 발행을 Kafka로 옮기고, 보내기는 outbox, 받기 실패는 그룹별 DLT, 택배사 추적은 배송 안 이벤트로 정함
status: 진행 중
date: 2026-11-16
paths: [delivery/src/main/java/com/ok/delivery/adapter/out/event, delivery/src/main/java/com/ok/delivery/adapter/in/event, delivery/src/main/java/com/ok/delivery/application/service/DeliveryOutboxRelayFacade.java, delivery/src/main/java/com/ok/delivery/domain/DeliveryOutbox.java]
related: [599, 603, 558, 560, 330, 637, 343]
---

> 이 문서는 #644 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

팀은 모듈 간 Spring 이벤트를 없애고 생성·수정 알림을 메시지 브로커로 보내기로 했다(모듈별 서버로 나누면 Spring 이벤트가 다른 서버에 닿지 않는다). 배송이 주문과 주고받는 세 가지, 결제 완료 받기와 배송 시작·완료 보내기를 Kafka로 옮긴다. 주문 쪽(#603)은 범위 밖이다. 이슈의 결정 ①~④는 추천안을 적용했다. 주문 담당의 확인이 남아 상태를 `진행 중`으로 둔다. 주문 전환 뒤 Spring 경로를 지우는 일도 이 이슈에 남아 있다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| ① 보내는 방식 | 배송 전용 `delivery_outbox` 표에 출고·배송 완료와 같은 트랜잭션으로 줄을 남기고, 스케줄러가 30초마다 보낸다 | 커밋 뒤 바로 `send`하면 브로커 장애나 앱 종료 때 메시지를 잃는다. Modulith 외부화는 공통 의존성이라 팀 결정이 필요하다. 회원(#560)·정산(#309)과 같은 방식이라 리뷰가 쉽다 |
| outbox 본문 | 기록할 때 JSON 본문(`payload`)을 저장한다 | 회원 outbox는 회원 ID만 보내 본문을 두지 않았다. 배송 메시지는 송장·시각처럼 그 순간의 값을 담고, 보낼 때 shipment를 다시 읽으면 그 사이 바뀐 상태(배송 완료 등)가 섞인다 |
| 발송 실패 정책 | 한 줄이 실패하면 그 실행을 멈추고, 10번을 넘게 실패하면 ERROR를 남긴다(회원 outbox와 같음) | 실패는 대개 브로커 장애라 뒤 줄도 실패한다. 멈추면 같은 주문의 배송 완료가 배송 시작보다 먼저 나가지 않는다 |
| 메시지 키 | `orderId` | 같은 주문의 시작·완료가 한 파티션에 들어가 순서가 지켜진다(이슈 메시지 약속) |
| ② 받다가 실패 | 1·2·4초 재시도 뒤 `<받은 토픽>.<그룹>.DLT`(결제 완료는 `order.order-paid.delivery.shipment-create.DLT`). 값 오류(`IllegalArgumentException`)와 깨진 JSON은 재시도 없이 바로 보낸다 | 결제 완료를 놓치면 결제된 주문이 배송되지 않아 건너뛰기는 안 된다. 지갑도 DB 실패 표에서 그룹별 DLT로 옮기는 방향(#637)이라 맞췄다. 이름에 그룹을 넣어 같은 토픽을 받는 다른 그룹에 다시 가지 않게 한다. 오류 처리기는 실패한 리스너의 그룹(`KafkaUtils.getConsumerGroupId()`)으로 이름을 만든다 |
| 컨슈머 그룹 | 리스너마다 하나씩, `<모듈>.<용도>`(결제 완료 수신은 `delivery.shipment-create`) | 오프셋이 그룹마다 따로라 한 리스너만 다시 받거나 오프셋을 옮길 수 있고, 한 리스너의 리밸런스가 다른 리스너를 멈추지 않으며, 랙 지표에서 누가 무슨 일로 밀렸는지 보인다. 이름은 누가 무엇을 무슨 일로 받는지를 나타내므로 리스너가 다른 서버로 옮겨 가도 그대로다 |
| DLT 로그 | 토픽·파티션·오프셋·키만 남긴다 | 값에 배송지(개인정보)가 들어 있다 |
| ③ 택배사 추적 요청 | 배송 안 이벤트 `CarrierTrackingRequestedEvent`를 `@ApplicationModuleListener`로 커밋 뒤에 받는다 | 같은 서버 안의 일이라 브로커를 거칠 까닭이 없다. 요청이 사라져도 앱 시작 때 배송 중인 건을 다시 요청하는 복구가 이미 있다. 리스너 방식을 바꾸지 않아 지금의 비동기·커밋 뒤 동작이 그대로다 |
| ③의 이벤트 위치 | `application/dto` | 발행은 `adapter.out`, 수신은 `adapter.in`이 하는데, 둘이 함께 볼 수 있는 곳은 `ArchitectureTest`상 application뿐이고 입력 어댑터는 `port.in`과 `dto`만 알 수 있다 |
| ④ 전환 순서 | 이중 경로: 배송이 Spring·Kafka를 둘 다 받고 둘 다 보낸다. 주문이 Kafka로 바뀐 뒤 Spring 쪽을 지운다(`TODO(#603)`) | 두 PR의 머지 날짜를 묶지 않는다. 배송 생성은 주문 ID 확인과 `(order_id, seller_id)` 유니크 제약으로, 주문은 중복 무시(#603)로 두 번 받아도 안전하다 |
| 메시지 DTO 위치 | 받는 DTO는 `adapter/in/event/dto`, 보내는 DTO는 `adapter/out/event/dto`. `common.event`는 쓰지 않는다 | 모듈 간 약속은 클래스가 아니라 필드 이름·타입·단위로 한다(팀 규칙, #599) |

## 버린 대안

- **보내기:** 커밋 뒤 리스너가 바로 `send`하는 방식은 장애 때 메시지를 잃어 버렸다. outbox는 최대 30초 늦게 나가고 표와 스케줄러가 늘어난다.
- **받기 실패:** 배송 DB 실패 기록 표(지갑 #579 방식)는 가장 흔한 원인인 DB 장애 때 같은 DB에 쓰지 못해 버렸다. 재시도 뒤 건너뛰기(정산·환불 방식)는 결제된 주문의 배송이 빠져 버렸다.
- **택배사 추적:** 배송이 자기 토픽 `delivery.shipment-dispatched`를 다른 그룹으로 구독하는 방식은 같은 서버 안의 일에 브로커를 끼워 넣어 버렸다.
- **전환:** #603과 같은 날 머지하는 방식은 두 담당의 일정을 묶어 버렸다. 이중 경로 기간에는 Spring 코드와 Kafka 코드가 함께 있다.
- **컨슈머 그룹 이름:** 모듈 하나에 그룹 `delivery` 하나는 리스너가 늘면 한 리스너만 다시 받거나 따로 랙을 볼 수 없어 버렸다. 환경 이름(`dev` 등)을 넣는 방식은 환경마다 브로커가 따로라 얻는 것이 없고 그룹·DLT 이름이 설정에 따라 달라져 버렸다. 서버 이름을 넣는 방식은 배포 구성이 바뀔 때마다 그룹 이름이 바뀌어(`earliest`라 처음부터 다시 읽음) 버렸다. 다른 모듈(정산·지갑·환불)은 아직 모듈 이름 그룹이고 팀 규칙은 바꾸지 않았다.
- **DLT 값:** 원본 바이트를 그대로 넣으려면 리스너가 DTO가 아니라 원본을 받아야 해서 하지 않았다. 그래서 JSON으로는 읽혔지만 검사에서 걸린 메시지는 DTO를 다시 직렬화한 값이 DLT에 들어가고, DTO에 없는 필드는 빠진다. 깨진 JSON만 원본 그대로 들어간다.

## 건드릴 때 주의

- outbox 본문은 문자열로 저장되어 있다. 부트의 `KafkaTemplate`은 값을 JSON으로 직렬화하므로, 문자열을 그대로 보내면 따옴표로 감싼 문자열이 나간다. `KafkaDeliveryMessageSender`는 본문을 `JsonNode`로 읽어서 보낸다.
- DLT용 바이트 발행기를 `KafkaTemplate` 빈으로 등록하지 않는다. 등록하면 부트가 기본 `KafkaTemplate`을 만들지 않아 monolith의 다른 모듈 주입이 깨진다. `DeliveryKafkaConsumerConfig` 안에서만 만든다.
- 배송 `@KafkaListener`에는 `groupId`(`DeliveryInboundTopics`의 리스너별 그룹 상수)와 `containerFactory`(`deliveryKafkaListenerContainerFactory`)를 꼭 적는다. 리스너를 더하면 그룹 상수와 DLT 상수, DLT `NewTopic`을 함께 더한다. 이미 쓰는 그룹 이름을 바꾸면 새 그룹이 `earliest`로 처음부터 다시 읽는다. 빠뜨리면 monolith에서 그룹이 앱 이름이 되어 다른 모듈과 메시지를 나눠 갖고, 실패는 DLT가 아니라 기본 처리(로그 뒤 건너뛰기)로 간다.
- 수신 팩토리 빈 이름과 NewTopic 빈 이름에 `delivery`를 넣는다. monolith 한 앱에서 다른 모듈 빈과 이름이 겹치면 앱이 뜨지 않는다.
- 발송은 `send()` 안에서 브로커 주소를 찾느라 producer의 `max.block.ms`(기본 60초)까지 막힐 수 있다. `SEND_TIMEOUT`(10초)은 `send()` 뒤의 기다림에만 걸린다(회원과 같은 한계). 실제 실행에서 브로커를 30초 멈췄을 때 실패 횟수가 오르지 않았고, 브로커가 살아나자 바로 나갔다.
- 서버가 여러 대면 같은 outbox 줄을 함께 보낼 수 있다. 잠금을 두지 않은 것은 받는 쪽(주문)이 중복을 무시한다는 약속(#603)을 전제로 한다.
- `DeliveryModuleTest`는 `spring.kafka.listener.auto-startup=false`로 둔다. 통합 테스트와 같은 그룹으로 켜 두면 통합 테스트 컨텍스트가 받아야 할 메시지를 가져간다.
