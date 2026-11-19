---
issue: 793
module: delivery
summary: 배송 outbox 즉시 발송 실행기를 전용으로 두고, 대기열이 차면 기다리지 않고 버리며 min-age가 지난 작업은 보내지 않음
status: 진행 중
date: 2026-11-20
paths: [delivery/src/main/java/com/ok/delivery/config/DeliveryAsyncConfig.java, delivery/src/main/java/com/ok/delivery/adapter/in/event/DeliveryOutboxSendListener.java, delivery/src/main/java/com/ok/delivery/application/dto/DeliveryOutboxRecordedEvent.java]
related: [644, 703, 715]
---

> 이 문서는 #793 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

배송 outbox의 커밋 직후 발송(#644)은 공용 실행기(`AsyncExecutorFactory`)를 써서, 대기열이 차면 작업을 넘기는 스레드가 자리가 날 때까지 기다렸다. 작업을 넘기는 곳은 `AFTER_COMMIT` 리스너이고, 이 리스너는 커밋 뒤 DB 커넥션을 돌려주기 전에 실행된다. 요청 스레드가 커넥션을 쥔 채 기다리고, 발송 스레드는 줄 읽기·"보냄" 기록에 커넥션이 필요해 서로 기다리다 커넥션 풀이 막혀 배송 API 전체가 멈출 수 있다. 주문은 같은 문제를 부하테스트에서 겪고 #715에서 거절로 바꿨다.

대기열이 길면 오래 기다린 작업이 발송 작업(30초 주기)과 같은 줄을 함께 보내 중복이 생긴다. 주문 생성 부하테스트(2026-11-19)에서 대기열에서 오래 기다린 작업과 재발송이 겹쳐 중복이 났다. 배송은 `min-age`(10초) 덕에 그때는 0건이었지만 대기 시간이 10초를 넘으면 같은 일이 생긴다.

이 문서는 코드 부분(실행기·거절 정책·오래된 작업 건너뛰기)의 결정이다. 대기열 크기·스레드 수 부하테스트와 기본값 변경은 같은 이슈에서 따로 하므로 `status`를 `진행 중`으로 둔다. 즉시 발송을 Facade로 옮기는 일은 후속, 발송 작업 처리량 개선은 #703이다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 대기열의 역할 | 짧은 몰림을 흡수하는 지름길로만 본다. 메시지 보장은 outbox 줄(`PENDING`)과 발송 작업이 맡는다 (사용자 결정) | 즉시 발송을 못 해도 줄은 남아 있어 최대 30초 늦을 뿐 잃지 않는다. 커넥션 풀이 막혀 배송 API가 멈추는 것보다 낫다 |
| 실행기 | 공용 팩토리 대신 `DeliveryAsyncConfig`에서 `ThreadPoolTaskExecutor`를 직접 만든다. 빈 이름 `delivery-outbox-send`, MDC 데코레이터, 스레드 이름 접두사, 종료 대기 10초는 그대로 둔다 | 공용 팩토리는 다른 모듈도 쓰고 거절 정책을 고를 수 없다. 주문 `OrderOutboxRelayExecutorConfig`와 같은 방식이다 |
| 가득 찼을 때 | 예외 없이 버리고 `delivery.outbox.send.dropped{reason=rejected}`를 1 올린다. 로그는 남기지 않는다 | 주문은 넘기는 쪽(`OrderOutboxRecorder`)에서 거절 예외를 잡지만, 배송은 Spring이 리스너를 불러 잡을 곳이 없다. 예외가 올라가면 커밋된 요청이 실패 응답이 되고 같은 커밋의 다른 콜백이 실행되지 않는다. 거절 처리도 커넥션을 쥔 요청 스레드에서 돌아, 몰릴 때 건마다 로그를 쓰면 그만큼 커넥션을 오래 잡는다 |
| 오래된 작업 | 꺼냈을 때 줄을 남긴 지 `delivery.outbox.relay.min-age`가 지났으면 보내지 않고 `reason=stale`로 센다 | 발송 작업이 줄을 고르는 기준과 같은 값이라, 이 시점부터는 발송 작업이 이미 같은 줄을 집었을 수 있다. 판단에 필요한 시각을 `DeliveryOutboxRecordedEvent.recordedAt`에 담아 DB를 읽기 전에 거른다. 밀렸을 때 커넥션도 쓰지 않는다 |
| 기록 시각의 출처 | 이벤트를 내는 `OutboxDeliveryEventPublisher`가 `Clock`으로 잰다 | 리스너가 같은 `Clock`으로 경과 시간을 재야 기준이 맞는다. 줄의 `createdAt`은 JPA 감사가 `Clock` 없이 채운다 |
| 판단 위치 | 리스너(`adapter.in.event`) 본문. 유스케이스 `send`는 바꾸지 않는다 | `@Async`라 리스너 본문이 곧 대기열에서 꺼낸 뒤 실행되는 곳이고, 대기열은 이 입력 어댑터의 사정이다. 발송 작업 쪽 `min-age`도 스케줄러(입력 어댑터)가 읽어 넘긴다 |
| 대기열 길이 지표 | 따로 만들지 않고 Spring Boot가 `ThreadPoolTaskExecutor` 빈마다 붙이는 `executor.queued{name="delivery-outbox-send"}`를 쓴다 | 같은 값을 두 이름으로 내지 않는다. 통합 테스트로 지표가 붙는 것을 확인했다 |
| 설정 위치 | `app.async.executors.delivery-outbox-send`에서 `delivery.outbox.send.pool-size`·`queue-capacity`로 옮긴다. 환경 변수 이름은 그대로 둔다 | 공용 팩토리를 쓰지 않으니 `app.async`에 둘 이유가 없다. 운영 환경 변수를 바꾸지 않아도 된다 |
| 기본값 | 스레드 2, 대기열 1000을 그대로 둔다 (사용자 결정) | 값은 부하테스트로 정한다. 측정 없이 줄이면 평소 몰림에서도 즉시 발송 비율이 떨어질 수 있다 |

## 버린 대안

- **기다리기 유지:** 공용 실행기를 그대로 두면 커넥션 풀이 막히는 문제가 남는다.
- **`AbortPolicy`(주문 방식):** 배송은 리스너를 Spring이 불러 거절 예외를 잡을 곳이 없다. 커밋된 요청이 500이 되고 같은 커밋의 다른 콜백(택배사 추적 요청 등)이 실행되지 않는다.
- **대기열 0(`SynchronousQueue`):** 스레드가 모두 바쁘면 바로 버려 짧은 몰림도 흡수하지 못한다. 부하테스트 조합에 넣어 값으로 판단한다.
- **즉시 발송을 Facade로 옮기기:** 서비스 트랜잭션이 끝나 커넥션을 돌려준 뒤 넘기면 근본 원인이 사라지지만 호출하는 서비스마다 바꿔야 해 후속으로 둔다.
- **오래된 작업을 DB의 `created_at`으로 판단:** 줄을 한 번 읽어야 해 밀렸을 때 커넥션을 쓰고, 시각 기준도 `Clock`과 어긋날 수 있다.

## 건드릴 때 주의

- 거절 정책(`DeliveryAsyncConfig.DropAndCountPolicy`)에서 예외를 던지거나 로그를 남기지 않는다. 둘 다 커밋 뒤 콜백 안, 커넥션을 쥔 요청 스레드에서 실행된다.
- 리스너의 `min-age`는 `DeliveryOutboxRelayScheduler`와 같은 키·같은 기본값이어야 한다. 한쪽만 바꾸면 중복을 막는 기준이 어긋난다.
- 꺼낼 때 `min-age` 안이었어도 브로커 확인 대기(최대 10초) 동안 발송 작업이 같은 줄을 집을 수 있다. 이 겹침은 #644부터 있던 것이고 주문이 중복을 무시하는 전제(#603)로 둔다.
- 모듈 테스트(`DeliveryModuleTest`)는 `common`의 `AsyncConfig`가 걸러지므로 `MdcTaskDecorator`를 테스트 설정에서 직접 등록한다.
