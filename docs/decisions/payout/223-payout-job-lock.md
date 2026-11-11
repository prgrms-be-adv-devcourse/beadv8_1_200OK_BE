---
issue: 223
module: payout
summary: 정산 Job이 정기·관리자·대기 건너뛰기 어느 경로로든 동시에 두 번 돌지 않게, ShedLock DB 잠금(도는 동안만 잠금, 5분 + 자동 연장)을 PayoutJobRunner 한 곳에 걸고 실행 중이면 409로 거절함
status: 확정
date: 2026-11-11
paths: [payout/src/main/java/com/ok/payout/adapter/in/batch/PayoutJobRunner.java, payout/src/main/java/com/ok/payout/config/PayoutJobLockConfig.java, payout/src/main/java/com/ok/payout/adapter/in/batch/PayoutJobScheduler.java, payout/src/main/resources/db/migration/payout/V202611111024__payout__create_shedlock_table.sql]
related: [309, 304, 306, 485, 224, 93]
---

> 이 문서는 #223 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

- 정산 Job은 새벽 정기 실행, 관리자 실행, 대기 건너뛰기 실행(#224·#485) 세 경로로 시작한다. 이전에는 실행 중인지 확인하는 장치가 없어 두 실행이 겹칠 수 있었다.
- 이중 정산은 이미 DB 규칙(`uk_payout_item_candidate_id`)이 막는다(실행으로 확인함). 그래서 이 작업의 동기는 이중 정산이 아니라 겹친 실행이 헛되이 FAILED가 되는 것, outbox Kafka 이중 발송 위험(#309가 #223으로 넘김), 관리자에게 "실행 중"을 알리는 것이다 (사용자 결정, 이슈 본문도 이대로 고침).
- 범위: 정산 Job만 잠근다. 다른 모듈 스케줄러와 컨벤션 문서 반영은 하지 않는다(사용자 결정). 여러 서버가 정산을 나눠 하는 것(판매자 단위 파티셔닝)은 #304에서 고려한다. 잠금이 없을 때의 동시 실행 롤백·발송 실패는 #306에 남긴다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 잠금 방식 | DB 표에 잠금을 적는 ShedLock (`shedlock-core` + `shedlock-provider-jdbc-template`만) | 운영은 서버 한 대지만 무중단 배포 때 옛 서버와 새 서버가 잠깐 겹친다. 메모리 잠금은 이 경우를 못 막고, DB 잠금은 같은 DB를 보는 모든 서버에 걸린다 |
| 거는 곳 | `PayoutJobRunner.start()`에서 `LockingTaskExecutor.executeWithLock`으로 직접 잠금 (사용자 결정) | 세 경로가 모두 지나는 한 곳이다. 어노테이션(`@SchedulerLock`)은 쓰지 않아 `shedlock-spring`·`@EnableSchedulerLock`도 넣지 않았다 |
| 잠금 유지 | `lockAtLeastFor` 0, 끝나면 바로 풂 (사용자 결정) | 관리자가 정산 직후 다시 실행할 수 있어야 한다. 한 대 운영 기준이라 끝난 직후 다른 서버 스케줄러가 다시 도는 경우는 받아들인다 |
| 최대 잠금 | `lockAtMostFor` 5분 + `KeepAliveLockProvider` 자동 연장, 총 상한 없음 (사용자 결정) | 도는 동안은 연장이 계속 늘려 5분 넘는 정산도 겹치지 않는다. 서버가 죽으면 연장도 멈춰 최대 5분 뒤 저절로 풀린다 |
| 거절 응답 | 409 `PAYOUT_JOB_ALREADY_RUNNING`. 스케줄러는 이 예외만 잡아 WARN (사용자 결정) | 관리자에게 "실행 중"을 알리고, 정기 실행이 밀린 것은 오류가 아니라 경고로 남긴다 |
| 잠금 표 | 정산 스키마의 `shedlock`, 코드에서 `PayoutSchema.NAME + ".shedlock"`, 시각 칸 `DATETIME(3)`, 잠금 시각은 DB 시각(`usingDbTime`) (사용자 결정) | monolith는 스키마가 여러 개라 이름에 스키마를 붙여야 찾는다. `TIMESTAMP`는 세션 시간대에 따라 값이 달라 서버마다 만료가 어긋날 수 있다 |
| 잠금 줄 미리 넣기 | 마이그레이션이 `payoutJob` 줄을 풀린 상태로 INSERT (사용자 확인) | 첫 잠금의 INSERT가 일시 오류로 실패하면 그 서버가 재시작 전까지 계속 거절하는 라이브러리 동작을 피한다. 실패하더라도 배포 때 서버가 안 켜져 바로 드러난다 |
| 시계 | `LockConfiguration` 생성 시각은 전역 `Clock`, ShedLock 시계(`ClockProvider`)도 같은 `Clock`으로 맞춤 (사용자 결정) | 자동 연장이 ShedLock 시계로 만료를 판단한다. 두 시계가 다르면 연장이 멈출 수 있어 "지금"의 기준을 하나로 둔다(#93) |
| 연장 스레드 | 빈으로 내놓지 않고 `PayoutJobLockConfig`의 private 필드, `destroy()`에서 종료 (사용자 확인) | `ScheduledExecutorService` 빈이 있으면 Spring `@Scheduled`가 그 스레드 하나를 다른 모듈 스케줄에도 쓴다(실행으로 확인함) |
| 설정 위치 | `config` 패키지 (사용자 결정) | 모듈 전용 설정 자리다(`PayoutKafkaConsumerConfig` 선례) |
| 의존성 승인 | 팀 관리 빌드 파일 변경은 PR 리뷰로 승인받고 팀 채널에 공지 (사용자 결정) | 의존성 두 줄이라 회의 없이 리뷰로 갈음한다 |

## 버린 대안

- **어노테이션 방식(`@SchedulerLock`):** 스케줄 메서드에 붙이는 방식이라 관리자·대기 건너뛰기 경로까지 한 잠금으로 묶으려면 결국 직접 호출이 필요하다. 감수하는 것은 잠금 코드가 `PayoutJobRunner`에 드러나는 것.
- **`lockAtLeastFor` > 0:** 짧은 정산 뒤 다른 서버의 재실행까지 막지만, 관리자도 그 시간 동안 다시 실행하지 못한다.
- **긴 `lockAtMostFor`(예: 1시간)만, 연장 없음:** 서버가 죽으면 그 시간 내내 다시 실행할 수 없고, 그보다 긴 정산은 도중에 풀려 겹친다.
- **연장에 총 상한 두기:** 멈춘 정산이 저절로 풀리지만, 정말 오래 걸리는 정산도 도중에 풀려 겹칠 수 있다. 감수하는 것은 멈춘 정산은 사람이 서버를 재시작하거나 UPDATE로 풀어야 한다는 것.
- **잠금 줄을 미리 넣지 않기:** 이름이 코드 한 곳에만 있지만, 첫 INSERT 실패 함정이 남는다.
- **두 번째 서버의 스케줄러를 끄는 스위치:** 지금은 한 대 운영이라 만들지 않았다.

## 건드릴 때 주의

- 정산 Job은 동기로 실행되어 잠금이 Job 끝까지 유지된다. Job을 비동기로 바꾸면 잠금이 바로 풀리므로 함께 바꿔야 한다.
- 정산 1건 실행 API(지금 501)를 열 때는 `PayoutJobRunner.start()`를 지나 같은 잠금을 받아야 한다.
- 잠금을 손으로 풀 때는 줄을 DELETE하지 말고 `lock_until`을 지금으로 UPDATE한다(SQL은 마이그레이션 주석). DELETE하면 줄이 있다고 기억하는 서버는 재시작 전까지 잠금을 못 잡는다(사전 점검에서 실행으로 확인함).
- 정산이 DB 응답을 기다리며 멈추면 연장이 계속되어 잠금이 풀리지 않는다. 멈춘 정산이 살아 있는 채 UPDATE로 풀면 깨어난 정산과 새 실행이 겹칠 수 있어, 서버 재시작(최대 5분 뒤 풀림)이 먼저다. 지금 DB 연결 설정에는 응답 대기 시간 제한(`socketTimeout`)이 없다(설정 파일에서 확인함).
- 잠금 이름 `payoutJob`은 `PayoutJobRunner`와 마이그레이션 두 곳에 있다. 한쪽만 바꾸면 미리 넣은 줄이 쓸모없어진다.
- 표 이름의 스키마 접두사를 빼도 정산 모듈 테스트는 통과하고 monolith 테스트만 실패한다.
- `ClockProvider.setClock`은 JVM 전체 설정이다. 다른 모듈이 ShedLock을 쓰게 되면 같은 시계를 따른다.
- 자동 연장 통합 테스트(약 16초)를 지우지 않는다. 연장을 빼도 이 테스트 말고는 모두 통과한다(변이 시험으로 확인함).
- 라이브러리 동작으로, 잠금을 잡는 중 일시 DB 오류(교착·대기 초과)는 409로 보이고, 잠금 풀기가 DB 오류로 실패하면 관리자는 500을 받고 최대 5분 동안 409가 난다.
