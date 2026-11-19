---
issue: 795
module: order
summary: 주문 outbox 커밋 직후 발송을 afterCommit 콜백 대신 Facade가 서비스 커밋 뒤 시작하고, 발행 구현체는 출력 포트로 outbox만 저장하게 해 adapter.out이 입력 포트를 부르지 않게 함
status: 확정
date: 2026-11-20
paths: [order/src/main/java/com/ok/order/order/application/service/OrderFacade.java, order/src/main/java/com/ok/order/order/adapter/out/event/OutboxOrderEventPublisher.java, order/src/main/java/com/ok/order/order/application/service/OrderOutboxRelayFacade.java, order/src/test/java/com/ok/order/ArchitectureTest.java]
related: [666, 715, 794, 796]
---

> 이 문서는 #795 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

#666부터 주문은 메시지를 outbox에 남길 때 `OrderOutboxRecorder`가 트랜잭션 동기화(`afterCommit`)를 등록해 커밋 직후 발송을 시작했다. 이 구조에는 세 문제가 있었다.

- 발행 구현체(`adapter.out`)가 `OrderOutboxRecordUseCase`(`application.port.in`)를 불러 출력 어댑터가 입력 포트로 되돌아왔다. `ArchitectureTest`는 AdapterOut → Application을 통째로 허용해 이를 잡지 못했다.
- `afterCommit`은 DB 커넥션을 돌려주기 전에 돈다. 그 안의 거절 처리와 로그가 커넥션을 쥔 채 실행됐고, 예외가 나면 이미 커밋된 요청이 오류 응답이 됐다(#715, #794).
- 발송이 어댑터 뒤에 숨어, 어느 진입점이 메시지를 보내는지 코드에서 보이지 않았다.

범위 밖: 보낼 메시지, 토픽, 키, 재발송·stale·거절 정책(#794 그대로). 배송의 같은 정리는 #796.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 발송 시작 위치 | 진입점이 부르는 Facade(`OrderFacade`)가 `@Transactional` 서비스가 돌아온 뒤 `OrderOutboxRelayFacade.sendAsync`를 부름 | 서비스 트랜잭션이 끝나 커넥션을 돌려준 뒤라 거절 처리가 커넥션을 쥐지 않는다(`open-in-view: false`). 외부 호출은 Facade에서 한다는 `service.md` 규칙과도 맞는다 |
| 줄 ID 전달 | 발행 포트 메서드가 남긴 outbox 줄 ID를 돌려주고, 서비스가 결과로 올림. 0~1건은 `Optional<Long>`, 여러 건(자동 구매확정)은 `AutoConfirmPurchaseResult.outboxIds` | Facade가 보낼 줄을 알아야 한다. 확정해도 모두 환불된 상품은 보내지 않아 확정 수와 줄 수가 다르다 |
| 발행 경로 | 결제 요청(주문 생성), 결제 완료, 구매확정, 자동 구매확정, 결제 만료 요청, 환불 되돌리기 6개. 다섯 유스케이스의 구현을 `OrderService`에서 `OrderFacade`로 옮김 | `OrderEventPublisher`를 부르는 곳을 모두 찾았다. 진입점(리스너·스케줄러·컨트롤러)은 유스케이스 포트만 알아 바뀌지 않는다 |
| 대기열 거절 | Facade가 `TaskRejectedException`을 잡아 `order.outbox.send.dropped{reason=rejected}`만 셈, 로그 없음 | 줄은 PENDING이라 재발송이 보낸다. 지표 이름과 태그는 #794 그대로이고, 두 사유의 카운터를 `OrderOutboxRelayFacade` 한곳에 둔다 |
| 남긴 시각 | Facade가 서비스가 돌아온 뒤 공용 `Clock`으로 잼 | stale 판단은 대기열에서 기다린 시간이 기준이다. 발송 쪽과 같은 `Clock`이어야 한다(#794) |
| 발행 구현체 | `OutboxOrderEventPublisher`가 `OrderOutboxRepository`(출력 포트)로 저장만 함. `OrderOutboxRecordUseCase`·`OrderOutboxRecorder` 삭제 | 배송·회원 발행 구현체와 같은 모양이다. 이름이 실제 동작(outbox 저장)을 말한다 |
| 같은 트랜잭션 보장 | 구현체 클래스에 `@Transactional(propagation = MANDATORY)` | 주문 변경과 따로 커밋되면 롤백된 변경의 메시지가 나가거나 커밋된 변경의 메시지가 안 나간다. 트랜잭션 밖 호출은 `IllegalTransactionStateException` |
| 구조 검사 | `ArchitectureTest`에 두 규칙: adapter.out은 application.port.in에 의존하지 않음, 입력 포트를 구현한 서비스 메서드는 같은 클래스 안에서 `OrderEventPublisher`를 부르지 않음 | 진입점은 입력 포트만 알아 어느 구현이 불리는지 정적으로 보이지 않으므로 구현 쪽에서 막는다. 옛 모양에 돌려 보면 두 규칙 모두 실패한다(입력 포트 구현 5곳) |

## 버린 대안

- **`afterCommit` 유지하고 거절 처리만 가볍게:** 커넥션을 쥔 채 도는 문제와 출력 어댑터 → 입력 포트 의존이 그대로 남는다.
- **`@TransactionalEventListener(AFTER_COMMIT)`로 발송(배송 방식):** 콜백 시점이 `afterCommit`과 같아 커넥션 문제가 같고, 발송이 이벤트 뒤에 숨는다.
- **서비스마다 발송용 Facade를 새로 만들기:** 진입점 6개가 모두 같은 일(서비스 호출 뒤 발송)을 하므로 기존 `OrderFacade`의 private 메서드 하나로 충분하다.
- **포트 메서드가 `void`를 유지하고 Facade가 줄을 다시 조회:** 어느 줄이 방금 남은 것인지 다시 찾아야 하고 같은 키의 PENDING 줄이 여럿이면 틀린다.

## 건드릴 때 주의

- 메시지를 남기는 서비스 메서드를 더하면 Facade에서 줄 ID로 `sendAsync`를 불러야 바로 나간다. 빠뜨려도 재발송이 `min-age`(기본 10초) 뒤 보내므로 잃지는 않지만 늦다. 테스트는 재발송을 꺼 두므로 Kafka에서 메시지를 읽는 통합 테스트는 유스케이스 포트(Facade)로 불러야 한다.
- Facade 메서드를 다른 트랜잭션 안에서 부르면 커밋 전에 발송이 시작된다. 발송 쪽은 아직 보이지 않는 줄을 건너뛰고 재발송이 보낸다. 진입점에 `@Transactional`을 붙이지 않는다.
- 발송 호출은 서비스 호출의 보상 `try` 밖에 둔다. 주문 생성에서 안에 두면 발송 쪽 예외가 커밋된 주문을 취소한다.
