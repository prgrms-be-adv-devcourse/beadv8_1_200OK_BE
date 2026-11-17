---
issue: 666
module: order
summary: 주문의 Kafka 발행(토픽을 저장하는 outbox, 커밋 직후 발송 + 30초 재발송)과 수신(주문 전용 오류 처리기, DB 실패 기록 표) 기반을 order 하위 도메인 안에 만들고, 메시지 이슈는 토픽·DTO·리스너만 더하게 함
status: 확정
date: 2026-11-16
paths: [order/src/main/java/com/ok/order/order/application/service/OrderOutboxRecorder.java, order/src/main/java/com/ok/order/order/application/service/OrderOutboxRelayFacade.java, order/src/main/java/com/ok/order/order/adapter/out/kafka/KafkaOrderMessageSender.java, order/src/main/java/com/ok/order/order/adapter/in/event/OrderKafkaConsumerConfig.java, order/src/main/java/com/ok/order/order/adapter/in/event/OrderMessageFailureRecoverer.java]
related: [599, 600, 601, 602, 603, 604, 656, 560, 579, 648]
---

> 이 문서는 #666 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

주문은 다른 모듈과 Spring 이벤트로만 주고받았고 Kafka로 보내거나 받는 코드가 없었다. 팀은 모듈 간 알림을 Kafka로 옮기고 Spring Modulith를 걷어 내기로 했다(회원 ADR 560). 첫 메시지 이슈(#600 구매확정, #601 회원가입)에 기반까지 넣으면 상대 모듈 담당이 메시지 모양과 상관없는 outbox·오류 처리 구조까지 리뷰해야 해서, 기반만 따로 만든다.

범위 밖: 실제 메시지와 리스너(#600~#604, #656), 남은 실패 기록의 자동 재처리, 팀 공통(`common`) outbox·오류 처리기로 합치기.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 둘 곳 | `order` 하위 도메인의 기존 레이어(`domain`, `application`, `adapter.in.event`·`in.scheduler`, `adapter.out.persistence`·`out.kafka`) (사용자 결정) | 다른 모듈과 같은 배치다. 회원은 outbox를 `member` 하위 도메인에 두고 `seller`가 그 입력 포트를 쓰며, 지갑·정산은 하위 도메인 없이 기존 패키지에 둔다. 장바구니 리스너는 `order.adapter.in.event`의 `containerFactory` 이름 상수만 쓰고, 순환 규칙은 패키지 단위라 `cart.adapter.in.event → order.adapter.in.event`와 기존 `order.application.service → cart.application.port.in`은 순환이 아니다. `docs/DEVELOPMENT.md`의 order 구조와도 어긋나지 않는다 |
| 다른 모듈 구현 재사용 | 하지 않음. 회원 outbox·지갑 실패 기록을 참고해 주문 안에 만듦 | 도메인 모듈끼리는 코드 의존을 걸지 않고, `common`에는 공통 부품이 없다(`common-kafka.yaml` 설정만 있음). `common`으로 합치는 일은 팀 결정이 필요하다 |
| 보내는 방식 | outbox 표(`order_outbox`) (#599 결정 ①) | 주문 변경과 보낼 메시지를 한 트랜잭션에 저장해 커밋되면 반드시 나가고 롤백되면 나가지 않는다 |
| outbox에 담는 것 | 메시지 종류 대신 토픽 이름, 키, 본문(JSON) | 발송기가 메시지 종류를 몰라도 되어(종류별 `switch` 없음) 메시지를 더할 때 발송기를 고치지 않는다. 기반만으로 테스트 토픽에 보내 확인할 수 있다 |
| 기록 | `OrderOutboxService.save`를 `Propagation.MANDATORY`로. 트랜잭션 밖에서 부르면 예외 | 따로 커밋되면 롤백된 변경의 메시지가 나가거나 커밋된 변경의 메시지가 안 나간다 |
| 발송 시점 | 커밋 직후 다른 스레드(`@Async`)에서 방금 남긴 줄을 보내고, 실패한 줄은 30초마다 재발송 (사용자 결정) | 평소 지연이 거의 없다. 주문 생성 → 지갑 결제처럼 사용자가 결과를 기다리는 메시지도 이 기반을 쓰므로 회원처럼 주기만 두면 최대 30초가 늦다. 다른 스레드에서 보내 요청이 브로커 확인을 기다리지 않게 한다 |
| 재발송이 실패하면 | 시도 횟수·원인을 줄에 남기고 그 회차를 멈춤, 10번을 넘으면 `[ORDER_OUTBOX_STUCK]` ERROR | 실패는 대개 브로커 장애라 뒤 줄도 실패할 뿐이고, 멈추면 같은 키의 뒤 줄이 앞 줄보다 먼저 나가지 않는다(회원과 같음) |
| 저장 확인 대기 | 10초 상한 | 재발송이 공용 스케줄 스레드에서 돈다. Kafka 기본(120초)까지 기다리면 결제 대기 정리 같은 다른 정기 작업이 밀린다 |
| 본문 보내기 | 저장한 JSON을 트리(`JsonNode`)로 읽어 보냄 | 문자열 그대로 보내면 JSON 직렬화기가 따옴표로 한 번 더 감싸 받는 쪽이 객체가 아니라 문자열을 받는다 |
| 보낸 줄 | 지우지 않음 (사용자 결정) | 무엇을 언제 보냈는지 추적한다. PENDING만 인덱스 `(status, id)`로 찾아 조회 비용은 늘지 않는다 |
| 동시 발송 | 잠금 없음 | 커밋 직후 발송과 재발송, 여러 서버가 같은 줄을 함께 보낼 수 있다. 받는 쪽이 업무 키로 중복을 거른다 |
| 받다가 실패하면 | 주문 전용 `containerFactory`의 오류 처리기가 1·2·4초 다시 시도한 뒤 `order_message_failure`에 원문을 남김. 값이 틀린 메시지(`IllegalArgumentException`)와 JSON을 읽지 못한 메시지는 바로 기록. 기록마저 실패하면 같은 메시지를 다시 받음 (#599 결정 ②, 사용자 결정) | 지갑(#579)과 같은 방식. 끝내 실패해도 버리지 않고 기록하므로 정산·환불(약 31초)처럼 오래 붙잡을 필요가 없다. 배송 상태처럼 다시 오지 않는 메시지를 잃지 않는다 |
| 실패 기록 구분 | 컨슈머 그룹 + 토픽 + 파티션 + 오프셋으로 한 번만 남김 | 주문 안에서도 리스너마다 그룹이 달라(#648) 같은 메시지를 두 리스너가 받을 수 있다. 그룹은 리스너 스레드의 `KafkaUtils.getConsumerGroupId()`로 얻는다 |
| 남은 실패 기록 | 사람이 `[ORDER_MESSAGE_FAILED]` 로그와 표를 보고 처리 (사용자 결정) | 결제 결과(만료 요청으로 다시 옴)·회원가입(장바구니는 담을 때 만들어짐)은 다른 복구 길이 있고, 버그로 실패한 메시지를 자동으로 반복 처리하지 않는다 |
| 문서 | `docs/DEVELOPMENT.md`의 order 구조 설명은 고치지 않음 | 기존 레이어 안에 두어 구조 설명과 어긋나지 않는다. 공용 문서라 다른 PR과 충돌하기 쉽다 |

## 버린 대안

- **outbox에 메시지 종류를 담고 발송기가 `switch`로 토픽을 고르기(회원 방식):** 표만 보고 어떤 일인지 알 수 있지만, 메시지를 더할 때마다 발송기에 분기를 더해야 하고 메시지가 없는 기반만으로는 테스트하기 어렵다. 대가로 토픽 이름을 바꿀 때 이미 쌓인 PENDING 줄은 옛 이름으로 나가므로 바꾸기 전에 PENDING이 0건인지 확인한다.
- **주기 발송만(회원 방식, 30초):** 코드가 적지만 사용자가 결제 결과를 기다리는 메시지에서 지연이 생긴다.
- **커밋 뒤 요청 스레드에서 바로 보내기:** 구매확정 API 같은 요청이 브로커 확인(최대 10초)을 기다린다.
- **Spring Modulith 외부화(`@Externalized`)나 `event_publication` 재사용:** 팀이 Modulith를 걷어 내기로 해 장치가 통째로 사라진다.
- **받다 실패하면 로그만 남기고 넘기기(정산·환불 방식):** 코드가 적지만 DB가 31초 넘게 멈추면 메시지를 잃는다.
- **주문 모듈에 하위 패키지 `messaging`을 새로 두기:** 기반 코드가 한 곳에 모이지만 팀 다른 모듈에 없는 배치라 리뷰어가 낯설고, `docs/DEVELOPMENT.md`의 order 구조와 어긋난다.
- **`common`에 팀 공통 outbox·오류 처리기:** 세 모듈이 각자 구현한 것을 바꿔야 하고 모든 모듈이 리뷰해야 한다. 옮길 때는 `OrderOutbox*`, `OrderMessage*`, `OrderKafkaConsumerConfig`, `KafkaOrderMessageSender`를 함께 옮기면 된다.

## 건드릴 때 주의

- 메시지를 보내는 서비스는 반드시 트랜잭션 안에서 `OrderOutboxRecordUseCase.record`를 부른다. 밖에서 부르면 `IllegalTransactionStateException`이 난다.
- 커밋 직후 발송은 앞 줄이 실패해 남아 있어도 기다리지 않는다. 같은 키의 메시지를 짧은 간격으로 이어 보내는 기능을 더하면 순서가 뒤집힐 수 있으니 그때 다시 본다. 지금 주문 메시지는 같은 키가 몇 분 이상 간격을 둔다.
- 토픽 이름을 바꾸기 전에 그 토픽의 PENDING 줄이 0건인지 확인한다. outbox는 저장한 이름 그대로 보낸다.
- 토픽 선언 빈과 오류 처리 관련 빈 이름은 `order`로 시작한다. monolith에서 다른 모듈의 같은 이름 빈과 겹치면 앱이 뜨지 않는다.
- 리스너는 `containerFactory = OrderKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY`와 리스너마다 다른 그룹(`order.<용도>`), 읽을 타입(`spring.json.value.default.type`)을 지정해야 이 오류 처리가 붙는다.
- 리스너에 `@Transactional`을 붙이지 않는다(#657). 리스너가 부르는 서비스가 자기 트랜잭션을 연다.
- 테스트는 `order/src/test/resources/module-test.yml`에서 재발송 스케줄러를 끈다. 켜 두면 30초마다 같은 줄을 함께 보내 메시지 건수와 상태가 흔들린다. 테스트 전용 토픽·리스너는 `com.ok.testsupport`에 두어 모듈 테스트에는 올라가지 않는다.
- 재발송은 Spring 기본 스케줄 스레드를 같이 쓴다. 브로커가 멈춘 날 한 번에 최대 약 10초(한 줄 실패 뒤 멈춤)를 쓴다.
