---
issue: 309
module: payout
summary: 지갑 입금용 Spring 이벤트는 정산 확정 트랜잭션에서 바로 내고, Kafka 발송만 정산 전용 outbox(payout_outbox)에 남겨 발행 Step이 보내고 실패분은 다음 실행에서 다시 보냄
status: 확정
date: 2026-11-10
paths: [payout/src/main/java/com/ok/payout/application/service/PayoutService.java, payout/src/main/java/com/ok/payout/application/service/PayoutOutboxService.java, payout/src/main/java/com/ok/payout/domain/PayoutOutbox.java, payout/src/main/java/com/ok/payout/adapter/out/persistence/PayoutOutboxJpaRepository.java, payout/src/main/java/com/ok/payout/adapter/out/event/SpringPayoutEventPublisher.java]
related: [339, 343, 304, 390, 223, 388]
---

> 이 문서는 #309 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

- #339 뒤 발행 Step은 정산마다 Kafka 저장 확인을 받은 다음에야 지갑용 Spring 이벤트를 냈다. 지갑은 Kafka를 읽지 않고 Spring 이벤트로만 입금한다(`wallet`의 `PayoutEventListener`, 확인함).
- 그래서 Kafka 한 건이 실패하면 발행 Step이 롤백되어 지갑 이벤트도 사라지고, 확정된 정산은 다음 실행이 고르지 않아 다시 보낼 길이 없었다(발행 누락).
- 범위: 정산 안에서만 처리한다. 지갑의 Kafka 수신 전환(#343), 동시 실행 막기(#223), 대량 발송 성능(#304)은 범위 밖이다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| outbox가 무엇을 맡나 | 지갑 Spring 이벤트는 commit 트랜잭션 안에서 발행, Kafka만 outbox로 (사용자 결정) | 지갑이 읽지 않는 Kafka 상태에 입금이 묶이지 않는다. Spring 이벤트는 Modulith가 `event_publication`에 같은 트랜잭션으로 저장하고 지갑은 커밋 뒤에 받으므로, 확정과 함께 커밋·롤백된다. 운영은 재시작 때 미전달 이벤트를 다시 보낸다 |
| outbox 표 | `payout_outbox`: 정산 1건당 1줄(`payout_id` UNIQUE·FK), PENDING/SENT, 시도 수, 마지막 오류, 보낸 시각. 본문은 저장하지 않음, 보낸 줄은 지우지 않음 | `payout`은 바뀌지 않으므로 보낼 때 읽으면 된다. "보냄" 표시를 `payout`에 두지 않아 정산 기록을 INSERT만 하는 원칙을 지킨다 |
| 보내는 곳 | 기존 발행 Step 하나가 PENDING 줄 전부(이전 실행분 포함)를 보냄 | 가장 작은 변경. 지갑 입금은 Kafka 지연과 상관없어 맨 앞 Step이나 별도 스케줄러가 필요 없다 |
| 결과 기록 | Kafka 호출만 try로 감싸고, 보냄·실패 기록은 줄마다 별도 트랜잭션(`PayoutOutboxService`, REQUIRES_NEW) | 뒤 줄의 실패가 앞 줄의 "보냄"을 되돌리지 않는다. 보냄 기록(DB) 실패는 잡지 않아 Step이 실패한다. DB 오류를 Kafka 실패로 잘못 기록하지 않기 위해서다 |
| 실패해도 Step은 완료 | Kafka 실패가 있어도 COMPLETED. 보낸 수 = write count, 실패 수 = write skip count, 실패마다 WARN | 정산·입금은 이미 끝났다. FAILED면 #390 조회 하한에서 이 실행이 빠지고 관리자 실행이 500이 된다 |
| 멈춤 | 연속 3번 실패하면 이번 회차를 멈춤(성공하면 0으로). 스레드가 중단되면 바로 멈춤 | 브로커 전체 장애 때 줄마다 최대 120초씩 기다리지 않는다 |
| 보내는 순서 | 시도 수가 적은 줄부터, 같으면 id 순 | id 순이면 매번 실패하는 줄 3개가 맨 앞에 있을 때 연속 실패로 멈춰 뒤 줄이 영영 시도되지 않는다(공격적 재검토에서 찾음) |
| 시도 상한·잠금 | 상한 없음, 동시 실행 잠금 없음 | 장애가 길어도 포기하지 않는다. 중복 발송은 받는 쪽이 `payoutId`로 막고, 동시 실행은 #223이 막을 예정 |
| 발행 인터페이스 | Kafka용 `PayoutEventPublisher`와 지갑용 `PayoutModuleEventPublisher` 둘 | 지갑이 Kafka로 받으면(#343) 지갑용 인터페이스와 구현체를 통째로 지우면 된다. 그때까지 "도메인당 발행 인터페이스 하나" 규칙의 임시 예외다 |

## 버린 대안

- **Kafka와 Spring 이벤트를 둘 다 outbox로:** 지갑 입금이 Kafka 발송 단계에 계속 묶인다. 발송이 늦거나 Step까지 못 가면 입금도 늦는다.
- **Kafka만 outbox로 다시 보내고 Spring 이벤트는 그대로(발행 Step):** 지갑이 Kafka를 읽지 않아 다시 보내도 입금이 복구되지 않는다.
- **Kafka를 commit 트랜잭션 안에서 바로 보내기(멘토 제안의 Kafka 경로):** 보낸 뒤 DB 커밋이 실패하면 되돌릴 수 없다. 다음 실행은 같은 후보를 새 `payoutId`로 정산해 지갑 중복 키가 달라진다.
- **팀 공통 outbox·Modulith 이벤트 외부화:** 공통 의존성 추가는 팀 결정이라 지금은 정산 안에서만 처리한다.
- **응답을 기다리지 않는 발송(`send`만):** 앱 버퍼에만 있는 메시지를 "보냄"으로 적게 되어 실패를 놓친다. 한꺼번에 보내고 응답을 모아 기다리는 방식은 #304에서 묶음 단위로 바꾼다(사용자 결정).

## 건드릴 때 주의

- 같은 정산이 Kafka로 두 번 갈 수 있다(보낸 뒤 "보냄" 저장 전에 죽음, 동시 실행). 받는 쪽은 `payoutId`로 중복을 막아야 한다.
- 시스템 몫·0원 이하 제외 조건은 지갑 이벤트와 outbox 줄에 함께 걸린다. 한쪽만 바꾸면 둘이 어긋난다.
- 발행 Step은 Step 트랜잭션 안에서 Kafka 응답을 기다린다. 회차마다 DB 연결 하나를 최대 120초 × 3번 잡는다(#309 전과 같은 구조).
- 발송 중 스레드가 중단되면 그 줄만 실패로 적고 멈추며, (미확인) Spring Batch가 Step 커밋 뒤 중단 표시를 보고 STOPPED로 기록한다. STOPPED 실행도 #390 하한 기준에서 빠진다(전에는 FAILED로 빠졌다).
- 정렬(`attemptCount, id`)은 `(status, id)` 인덱스로 처리되지 않는다. PENDING 줄이 많아지면 인덱스를 다시 본다.
- 테스트에서 `payout`을 지울 때는 FK 때문에 `payout_outbox`를 먼저 지운다.
