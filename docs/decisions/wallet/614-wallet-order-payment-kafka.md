---
issue: 614
module: wallet
summary: 주문 결제를 Kafka로만 주고받도록 바꾸고(Spring 길 삭제), 결과는 결제 커밋 뒤에 보내며 같은 주문에는 처음 결과를 다시 보냄
status: 확정
date: 2026-11-17
paths: [wallet/src/main/java/com/ok/wallet/application/service/OrderPaymentService.java, wallet/src/main/java/com/ok/wallet/application/dto/OrderPaymentResult.java, wallet/src/main/java/com/ok/wallet/adapter/in/event/OrderKafkaListener.java, wallet/src/main/java/com/ok/wallet/adapter/out/event/KafkaOrderPaymentResultPublisher.java]
related: [34, 360, 579, 602, 612, 613, 637, 638, 656, 669, 693]
---

> 이 문서는 #614 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

지갑 Kafka 전환의 마지막 조각(정산 #579 → 환불 #34 → 주문)이다. 처음에는 Kafka 길을 Spring 길 옆에 더하고(1단계) Spring 길은 주문 #602와 함께 지우려 했다(2단계). 주문이 #602(PR #680)에서 주문 생성을 Spring과 Kafka로 함께 보내고 결제 결과도 두 길로 함께 받게 했고, 정산도 결제 완료를 Kafka로 받게(#675) 되어, 두 단계를 이 PR 하나로 합치고 지갑의 Spring 길을 지웠다.
범위: 결제 결과에 처리 기록 값 담기, 결과 Kafka 출구, 결제 처리, `order.order-created.v1` 수신부, 실패 사유 한 곳으로 모으기, Spring 받기·보내기 삭제. 범위 밖: 결과 보내기 실패를 길게 다시 시도(#638, 먼저 머지됨), 결제 만료 요청(#693, 주문 #656), 주문 요약 저장·표시(#609).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| Spring 길 | 지움(받기 `OrderEventListener`, 보내기 `WalletEventPublisher`, `pay`) | 주문(#680)과 정산(#675)이 결과를 Kafka로 받고 주문이 주문 생성을 Kafka로도 보낸다. 남겨 두면 같은 주문을 두 길로 받아 결과도 두 길로 나간다 (사용자 결정) |
| Kafka 길 결과 보내는 때 | 결제 커밋 뒤, 오프셋 커밋 전, 브로커 저장 확인까지 기다림 | 환불 반환(#34)과 같은 방식. 서비스 메서드를 트랜잭션 없이 두고 `payForOrder`가 돌아오면 커밋돼 있다 |
| 서비스 트랜잭션 | `OrderPaymentService`의 클래스 단위 `@Transactional(readOnly = true)`를 빼고, `payAndSendResult`는 `Propagation.NEVER` | 트랜잭션 안에서 돌면 커밋 전에 보낸다. NEVER는 누가 트랜잭션 안에서 부르면 조용히 되살아나는 대신 바로 예외로 드러낸다(자동 리뷰 반영) |
| 같은 주문이 다시 옴 | 처음 기록 값으로 결과 다시 보냄 | 보내기 실패는 다시 받는 것으로만 복구한다 |
| 다시 온 주문의 확인 순서 | 금액을 보기 전에 처리 기록부터 찾음 | 결제된 주문이 금액이 빠진 채 다시 오면 "처리할 수 없는 요청" 실패가 나가 처음 결과와 모순됐다(자동 리뷰 반영). 처리 기록이 있으면 요청 값과 상관없이 처음 결과가 답이다 |
| 다시 보내는 값 | 이번 요청이 아니라 처리 기록의 구매자·금액·시각 | 처음과 똑같은 메시지가 가야 주문·정산이 같은 결과로 보고 한 번만 반영한다 |
| 결제 시각 | 처리 기록 시각을 마이크로초로 잘라 넣고 그 값을 보냄 | DB가 마이크로초까지 저장해, 자르지 않으면 처음 결과(나노초)와 다시 읽은 결과의 시각이 달랐다(시험으로 확인) |
| 실패 사유 | `OrderPaymentResult.failureReason()` 한 곳. 지금은 기록된 실패면 잔액 부족 | 처음·다시 보낼 때 같은 사유가 나가게 한다. 결제 만료가 실패 기록에 들어오면 여기만 기록된 사유를 읽게 바꾼다 (사용자 결정) |
| 출구 포트 | `OrderPaymentResultPublisher` (Spring 출구 `WalletEventPublisher`는 지움) | 결제를 커밋한 뒤 보내고, 실패하면 예외로 같은 요청을 다시 받는다 |
| 값 확인 위치 | 수신부가 아니라 서비스 | 처음 보는 주문의 금액이 틀려도 결제 대기로 멈추지 않게 `INVALID_PAYMENT_REQUEST` 결과를 보내야 한다. 주문·구매자 ID가 없으면 서비스가 `IllegalArgumentException`을 던져 실패 기록에 남는다 |
| 키·그룹 | 받기·보내기 모두 `orderId`, 그룹 `wallet.order-payment` | #602 약속. 한 주문의 완료와 실패가 같은 파티션에 들어간다. 지갑 잠금으로 같은 구매자의 동시 결제도 맞게 처리된다. 그룹은 #648 규칙(`<모듈>.<용도>`)으로 처음부터 리스너 전용 |
| 결과 토픽 이름 | `wallet.wallet-payment-completed.v1`, `wallet.wallet-payment-failed.v1`. 옛 이름으로는 보내지 않음 | #648 규칙. 아직 한 번도 보낸 적 없는 토픽이라 이중 발행이 필요 없다. 주문(#602)과 정산(#653)이 `.v1`로 받는다 |
| 토픽 생성 빈 이름 | `walletOutboundPaymentCompletedTopic` 등 | 정산이 같은 토픽을 받으려고 미리 만드는 빈 이름이 `walletPaymentCompletedTopic`이라, 같으면 monolith가 뜨지 않는다 |

## 버린 대안

- **두 단계로 나눠 Spring 길을 #602와 같은 배포에 지우기(처음 계획):** 주문이 두 길로 함께 보내고 받게 해서 같은 배포로 묶을 까닭이 없어졌다. 나누면 그사이 같은 주문을 두 길로 받아 결과가 두 번 나간다.
- **전환 기간에 받은 길로만 답하기(1단계 설계):** Spring 수신부는 자기 트랜잭션 안에서 서비스를 불러, 거기서 Kafka로 보내면 커밋 전에 나간다. Spring 길을 지우며 이 구분도 필요 없어졌다.
- **outbox로 결과 보내기:** 처리 기록이 곧 보낼 결과라 다시 받으면 같은 결과를 다시 만들 수 있다(#34와 같은 판단).

## 건드릴 때 주의

- 결과 보내기가 다시 시도를 다 쓰면 실패 기록 표로 넘어가, 돈은 빠졌는데 결과가 나가지 않은 주문이 남는다. #638로 일시적 보내기 실패는 30분까지 다시 시도하고, 결제 만료(#693)가 들어오면 30분 뒤 주문이 만료를 물을 때 지갑이 처음 결과를 다시 보내 풀린다. 그래서 머지 순서는 이 PR → #693 → 주문 #684다.
- `OrderPaymentService`에 클래스 단위 `@Transactional`을 다시 붙이지 않는다. `payAndSendResult`의 `Propagation.NEVER`를 빼지 않는다.
- 배포 직후 새 그룹 `wallet.order-payment`가 `order.order-created.v1`을 처음부터 읽어, Spring으로 이미 결제한 주문이 다시 온다. 처리 기록으로 한 번만 차감하고 처음 결과를 다시 보내며, 주문은 결제 대기가 아니면 무시하고 정산은 주문 번호로 한 번만 기록한다.
- 처리 기록 시각을 넣는 곳을 바꾸면 마이크로초 자르기를 유지한다. 다시 보낸 결제 시각이 처음과 달라진다.
- 결제 만료를 처리 기록 실패로 넣으면 `failureReason()`이 기록된 사유를 읽게 바꾼다. 그대로 두면 늦게 온 주문 생성에 만료가 잔액 부족으로 다시 나간다.
- 보내는 메시지(`WalletPayment*Message`)와 받는 메시지(`OrderCreatedMessage`)의 필드는 주문(#602)·정산과의 약속이다. 정산은 `PayoutWalletKafkaListener`로 결제 완료를 받는다.
