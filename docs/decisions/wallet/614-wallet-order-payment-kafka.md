---
issue: 614
module: wallet
summary: 주문 생성을 Kafka로 받는 길을 Spring 길 옆에 더하고, 받은 길로만 답하게 해 Kafka 결과는 결제 커밋 뒤에 보내며 같은 주문에는 처음 결과를 다시 보냄
status: 확정
date: 2026-11-15
paths: [wallet/src/main/java/com/ok/wallet/application/service/OrderPaymentService.java, wallet/src/main/java/com/ok/wallet/application/dto/OrderPaymentResult.java, wallet/src/main/java/com/ok/wallet/adapter/in/event/OrderKafkaListener.java, wallet/src/main/java/com/ok/wallet/adapter/out/event/KafkaOrderPaymentResultPublisher.java]
related: [34, 360, 579, 602, 612, 613, 637, 638]
---

> 이 문서는 #614 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

지갑 Kafka 전환의 마지막 조각(정산 #579 → 환불 #34 → 주문)이다. 주문 쪽 전환(#602)은 아직 시작 전이라, 지갑이 Kafka 받기·보내기를 먼저 더해 두고 Spring 받기·보내기는 남긴다(1단계). Spring 길은 #602 PR과 함께 지운다(2단계, `refactor/614`).
범위: 결제 결과에 처리 기록 값 담기, 결과 Kafka 출구, Kafka 길 결제 처리, `order.order-created.v1` 수신부, 실패 사유 한 곳으로 모으기. 범위 밖: Spring 길 삭제(2단계), 결과 보내기 실패를 길게 다시 시도(#638), 결제 만료 요청(주문과 협의 중), 늦은 결제 반환(#612, 결제 만료 방식으로 정리 예정).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 결과를 보내는 길 | 받은 길로만 답함. Spring으로 받으면 Spring, Kafka로 받으면 Kafka | Spring 수신부(`@ApplicationModuleListener`)는 자기 트랜잭션 안에서 서비스를 부른다. 거기서 Kafka로 보내면 커밋 전에 나가, 커밋이 실패하면 빠지지 않은 돈을 결제 완료로 알린다. Spring 결과는 커밋과 함께 저장돼 이 문제가 없다 (사용자 결정) |
| Kafka 길 결과 보내는 때 | 결제 커밋 뒤, 오프셋 커밋 전, 브로커 저장 확인까지 기다림 | 환불 반환(#34)과 같은 방식. 서비스 메서드를 트랜잭션 없이 두고 `payForOrder`가 돌아오면 커밋돼 있다 |
| 서비스 클래스 트랜잭션 | `OrderPaymentService`의 클래스 단위 `@Transactional(readOnly = true)`를 뺌 | 남기면 Kafka 길도 읽기 전용 트랜잭션 안에서 돌아 커밋 전에 보낸다. Spring 길 `pay`는 메서드에 `@Transactional`이 있어 그대로다 |
| 같은 주문이 다시 옴 | Kafka 길: 처음 기록 값으로 결과 다시 보냄. Spring 길: 다시 보내지 않음(지금과 같음) | Kafka는 보내기 실패를 다시 받는 것으로만 복구한다. Spring은 결과가 커밋과 함께 저장돼 반드시 전달된다 |
| 다시 보내는 값 | 이번 요청이 아니라 처리 기록의 구매자·금액·시각 | 처음과 똑같은 메시지가 가야 주문·정산이 같은 결과로 보고 한 번만 반영한다 |
| 결제 시각 | 처리 기록 시각을 마이크로초로 잘라 넣고 그 값을 보냄 | DB가 마이크로초까지 저장해, 자르지 않으면 처음 결과(나노초)와 다시 읽은 결과의 시각이 달랐다(시험으로 확인) |
| 실패 사유 | `OrderPaymentResult.failureReason()` 한 곳. 지금은 기록된 실패면 잔액 부족 | Spring·Kafka 길, 처음·다시 보낼 때 같은 사유가 나가게 한다. 결제 만료가 실패 기록에 들어오면 여기만 기록된 사유를 읽게 바꾼다 (사용자 결정) |
| 출구 포트 | Spring 출구(`WalletEventPublisher`)와 나눈 `OrderPaymentResultPublisher` | 보내는 시점(트랜잭션 안·커밋 뒤)과 실패 처리(저장·예외로 다시 받기)가 다르다. 2단계에서 Spring 출구만 지우면 된다 |
| 값 확인 위치 | 수신부가 아니라 서비스 | 금액이 틀린 주문도 결제 대기로 멈추지 않게 `INVALID_PAYMENT_REQUEST` 결과를 보내야 한다. 주문·구매자 ID가 없으면 서비스가 `IllegalArgumentException`을 던져 실패 기록에 남는다 |
| 키·그룹 | 받기·보내기 모두 `orderId`, 그룹 `wallet` | #602 약속. 한 주문의 완료와 실패가 같은 파티션에 들어간다. 지갑 잠금으로 같은 구매자의 동시 결제도 맞게 처리된다 |
| 토픽 생성 빈 이름 | `walletOutboundPaymentCompletedTopic` 등 | 정산이 같은 토픽을 받으려고 미리 만드는 빈 이름이 `walletPaymentCompletedTopic`이라, 같으면 monolith가 뜨지 않는다 |

## 버린 대안

- **Spring으로 받은 주문에도 Kafka로 결과 보내기:** 정산이 1단계부터 Kafka로 결제 완료를 받을 수 있지만, 커밋 전 발송 위험이 있다. 감수하는 것은 정산이 Kafka로 결제 완료를 받기 시작하는 때가 2단계로 미뤄지는 점.
- **Spring 길을 트랜잭션 없이 바꿔 두 결과를 다 커밋 뒤에 보내기:** (미확인) Spring 수신부의 트랜잭션은 이벤트 처리 완료 기록과 묶여 있어 바꾸면 Spring 이벤트 재처리 동작까지 흔든다. 어차피 2단계에서 지운다.
- **outbox로 결과 보내기:** 처리 기록이 곧 보낼 결과라 다시 받으면 같은 결과를 다시 만들 수 있다(#34와 같은 판단).
- **Kafka 길을 새 서비스 클래스로:** 처리 규칙이 Spring 길과 같은 처리 기록·사유를 쓰므로 한 서비스에 둔다. 2단계에서 `pay`만 지운다.

## 건드릴 때 주의

- 결과 보내기가 다시 시도(1·2·4초)를 다 쓰면 실패 기록 표로 넘어가, 돈은 빠졌는데 결과가 나가지 않은 주문이 남는다. #638(일시적 보내기 실패는 길게 다시 시도)이 #614 2단계 전에 머지되어야 한다. 결제 만료 요청이 생기면 30분 뒤 주문이 만료를 물을 때 지갑이 이미 결제됨을 보고 결제 완료를 다시 보내 풀린다.
- `OrderPaymentService`에 클래스 단위 `@Transactional`을 다시 붙이지 않는다. `payAndSendResult`를 다른 트랜잭션 안에서 부르게 바꾸지 않는다.
- Spring 수신부에서 Kafka 출구를 부르지 않는다. 2단계 전까지는 받은 길로만 답한다.
- 처리 기록 시각을 넣는 곳을 바꾸면 마이크로초 자르기를 유지한다. 다시 보낸 결제 시각이 처음과 달라진다.
- 결제 만료를 처리 기록 실패로 넣으면 `failureReason()`이 기록된 사유를 읽게 바꾼다. 그대로 두면 늦게 온 주문 생성에 만료가 잔액 부족으로 다시 나간다.
- 보내는 메시지(`WalletPayment*Message`)와 받는 메시지(`OrderCreatedMessage`)의 필드는 주문(#602)·정산과의 약속이다. 정산은 `PayoutWalletKafkaListener`로 결제 완료를 받는다.
