---
issue: 560
module: member
summary: 회원가입을 Kafka(member.member-registered)로도 보내되, Modulith를 걷어 낼 예정이라 event_publication 대신 member 전용 outbox 표와 30초 주기 발송으로 커밋 후 발행을 보장함
status: 확정
date: 2026-11-15
paths: [member/src/main/java/com/ok/member/member/adapter/out/event/OutboxMemberEventPublisher.java, member/src/main/java/com/ok/member/member/application/service/MemberOutboxRelayFacade.java, member/src/main/java/com/ok/member/member/adapter/in/scheduler/MemberOutboxRelayScheduler.java, member/src/main/java/com/ok/member/member/domain/MemberOutbox.java, member/src/main/java/com/ok/member/member/adapter/out/event/KafkaMemberMessageSender.java]
related: [535, 309]
---

> 이 문서는 #560 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

모듈을 따로 배포하면 스프링 이벤트는 다른 프로세스에 닿지 않는다. member가 내는 모듈 간 이벤트 중 #535 뒤에도 받는 곳이 있는 것은 장바구니가 받는 `MemberRegisteredEvent` 하나라, 이것만 Kafka로 옮긴다.
범위 밖: 장바구니 쪽 `@KafkaListener` 전환, 탈퇴·출고지 우편번호 같은 새 메시지.
`SellerApprovedEvent`는 #535 뒤 받는 모듈이 없지만 발행을 그대로 둔다. 지갑 담당이 지우지 말라고 요청했다. 출금이나 판매자 권한 회수를 만들 때 지갑이 판매자 상태를 따로 보관하는 방법을 고르면 승인 이벤트를 다시 받아야 하기 때문이다(회수 이벤트만으로는 "지금 판매자인가"를 알 수 없음). Kafka로 옮길지는 그때 받는 쪽과 함께 정한다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 커밋 후 발행 보장 | member 전용 outbox 표(`member_outbox`) + 주기 발송. 이슈의 선택지 (c) | 팀이 Spring Modulith를 걷어 내기로 해(사용자 결정) `event_publication`·`@ApplicationModuleListener`·재발행 API에 기댈 수 없다. 회원 저장과 같은 트랜잭션에 줄을 남겨 롤백되면 안 나가고 커밋되면 반드시 나간다 |
| 줄을 남기는 곳 | 기존 발행 포트의 구현체(`OutboxMemberEventPublisher`, 원래 `SpringMemberEventPublisher`) | 가입 서비스는 이미 `MemberEventPublisher`만 부르므로 서비스를 고치지 않고 어댑터만 바꾼다(이슈 본문 방향) |
| 본문 저장 | 저장하지 않음. 종류와 `member_id`만 | 지금 메시지는 회원 ID뿐이라 보낼 때 만들면 된다. payout outbox와 같은 방식 |
| 발송 주기 | 30초 cron, 한 번에 100줄 (설정값) | 장바구니는 메시지가 늦어도 처음 쓸 때 `getOrCreateCart`로 만들어 결과가 같다. 보낼 줄이 없으면 `(status, id)` 인덱스 조회 한 번이다. 운영 서버가 09~18시에만 떠 있어 특정 시각이 아니라 짧은 주기로 돈다. 사용자는 부하를 걱정해 5초 대신 30초를 골랐다(사용자 결정) |
| 발송 중 트랜잭션 | `MemberOutboxRelayFacade`는 트랜잭션 없이, 줄 읽기·결과 저장만 `MemberOutboxService`의 짧은 트랜잭션 | 브로커 확인을 기다리는 동안(최대 10초) DB 커넥션을 잡지 않는다. 결과를 줄마다 따로 저장해 뒤 줄 실패가 앞 줄의 "보냄"을 되돌리지 않는다 |
| 실패 처리 | 시도 수·원인(원인 사슬 맨 안쪽까지)을 줄에 남기고 그 실행을 멈춤. 예외는 올리지 않고 WARN, 10번을 넘으면 ERROR | 실패는 대개 브로커 장애라 뒤 줄도 10초씩 기다리다 실패할 뿐이다. 멈추면 같은 회원의 뒤 메시지가 앞 메시지보다 먼저 나가지 않는다. 같은 줄이 계속 실패하면 뒤 줄이 막히므로 약 5분(10번)이 넘으면 사람이 보게 ERROR로 올린다 |
| 저장 확인 대기 | 10초(refund와 같음). Kafka 기본 120초를 쓰지 않음 | 스케줄러 스레드를 다른 스케줄 작업(Refresh Token 정리)과 함께 쓴다. 실패해도 30초 뒤 다시 보내므로 오래 기다릴 이유가 없다 |
| 메시지 약속 | 토픽 `member.member-registered`, 키 `memberId`, 값 `{"memberId": N}`, 최소 한 번 전달 | 이름 규칙 `<보내는 모듈>.<이벤트>`(#339). 키를 회원으로 두어 같은 회원의 메시지(앞으로 생길 탈퇴 등)가 한 파티션에서 순서대로 간다. 장바구니는 `insertIfAbsent`라 중복에 안전하다 |
| 전환 순서 | 스프링 이벤트는 그대로 두고 Kafka를 더한 member 쪽을 먼저 머지. 장바구니가 옮긴 뒤 스프링 발행과 common 이벤트 클래스를 지움 | structure.md는 양쪽을 같은 시점에 바꾸라고 하지만, 그 이유(클래스가 달라지면 전달이 소리 없이 끊김)가 "더하기만 하는" 전환에는 생기지 않는다. 장바구니 담당자와 합의했다(사용자 결정) |
| 여러 대 실행 | 잠금 없음 | member는 아직 한 대(#442)이고 받는 쪽이 중복에 안전하다 |
| 발송 스레드 | 전용 실행기를 두지 않고 공용 스케줄러 스레드에서 돈다 | 공용 스레드(Boot 기본 1개)라 Kafka 장애 때 한 번에 최대 약 70초를 붙잡아 monolith의 다른 모듈 정기 작업이 밀린다(코드 리뷰 지적). 하지만 모듈별로 분리하면 member 앱에서 같이 쓰는 작업은 매시 정각의 Refresh Token 정리뿐이라, 분리가 가까운 지금 전용 실행기(#417 통로, 공통 설정 수정과 겹침 방지 코드)를 더하지 않는다(사용자 결정) |
| `member_id` 외래키 | 둠 | 같은 모듈 안의 참조라 migration.md 규칙을 따른다 |

## 버린 대안

- **Modulith `event_publication`을 outbox로 쓰기(이슈 선택지 a):** 한 번 이 방식으로 구현했다(커밋 후 리스너 + 실패 기록을 5분마다 재제출). 표와 마이그레이션이 필요 없고 분리 뒤에도 모듈 스키마에 생기지만, Modulith를 걷어 내면 장치가 통째로 사라진다. 그 밖에도 리스너를 고르는 데 Modulith 내부 API(`events-core`의 `TargetEventPublication`)가 필요했고, 기록이 common 이벤트 클래스를 직렬화해 클래스를 지우기 전에 미완료 기록을 비워야 했다.
- **Modulith 이벤트 외부화(선택지 b):** 같은 이유로 버렸다.
- **커밋 직후 바로 보내고 실패분만 주기 재발송:** 평소 지연이 거의 0이지만 커밋 후 리스너가 하나 더 필요하다. 장바구니는 지연을 견디므로 단순한 주기 발송을 골랐다. 바로 반영돼야 하는 메시지가 생기면 outbox 표와 발송 작업은 그대로 두고 이 방식을 더할 수 있다.
- **실패한 줄을 건너뛰고 다음 줄 계속 보내기(payout처럼 시도 수 적은 줄부터):** 한 줄이 계속 실패해도 뒤 줄이 막히지 않지만, 같은 회원의 메시지 순서가 뒤집히고 브로커 장애 때 줄마다 10초씩 기다린다. 감수하는 것은 같은 줄이 계속 실패하면 뒤 줄이 모두 기다린다는 점이다.
- **사가:** 회원가입 뒤 장바구니 생성이 실패해도 가입을 되돌릴 이유가 없어 보상할 단계가 없다.

## 건드릴 때 주의

- 본문이 있는 메시지(출고지 우편번호 등)를 더하면 "실패하면 멈춤" 정책을 다시 본다. 값이 잘못된 줄 하나가 뒤 줄을 모두 막을 수 있고, 수정마다 나가는 메시지는 받는 쪽이 `occurredAt` 같은 값으로 오래된 것을 버려야 한다. 본문을 저장하지 않는 지금 구조도 그때 바뀐다.
- `OutboxMemberEventPublisher`는 가입 서비스의 트랜잭션 안에서 불려야 한다. 트랜잭션 밖에서 부르면 outbox 줄과 회원 저장이 따로 커밋된다.
- 장바구니 전환 뒤 스프링 이벤트를 지울 때 common의 `MemberRegisteredEvent`를 먼저 지우면 장바구니가 컴파일되지 않는다. 장바구니 리스너가 먼저 빠져야 한다.
- 테스트는 `member/src/test/resources/module-test.yml`에서 발송 스케줄러를 끈다. 켜 두면 30초마다 같은 줄을 함께 보내 메시지 건수를 세는 테스트가 흔들린다.
- 가입을 거치는 테스트는 `member_outbox` 줄이 외래키로 회원을 참조하므로 회원보다 먼저 지워야 한다.
- 분리하기 전(monolith)에 Kafka가 죽으면 이 발송이 공용 스케줄러 스레드를 붙잡아 다른 모듈의 `@Scheduled` 작업(리뷰 분석 등)이 밀린다. 분리가 늦어지거나 monolith로 계속 운영하게 되면 #417 방식의 전용 실행기로 옮긴다.
- 서버를 여러 대로 늘리면 같은 줄을 두 서버가 함께 보낼 수 있다. 받는 쪽이 중복에 안전하지 않은 메시지를 더하기 전에 잠금(`SELECT ... FOR UPDATE SKIP LOCKED` 등)을 정한다.

## 후속 (2026-11-15)

- 판매자 승인 스프링 이벤트(`SellerApprovedEvent`)는 #587에서 지웠다. 위 "배경"에 적은 "발행을 그대로 둔다"를 바꾼 것이다. 분리 뒤에는 스프링 이벤트가 member 앱 밖으로 나가지 않아 남겨 둬도 지갑이 받을 수 없고, 팀이 스프링 이벤트를 모두 걷어 내기로 해서다. 지갑 담당과 합의했고, 지갑 출금 작업 때 필요해지면 메시지 약속을 정해 이 outbox로 보낸다.
- 출고지 우편번호(#587)도 이 outbox를 쓴다. 바뀌는 값을 보내야 해서 `member_outbox`에 본문(`payload`) 칸을 더했다. 위 "본문 저장: 저장하지 않음"은 회원가입 메시지에만 해당한다. 판매자 하위 도메인은 `MemberOutboxRecordUseCase`로 줄을 남긴다. 자세한 이유는 `docs/decisions/member/587-seller-origin-postal-code.md`에 있다.
