---
issue: 485
module: payout
summary: 대기 건너뛰기 API를 설정으로 평소 꺼 두고, 실행 유형 enum을 domain/type에 두어 스냅샷에 남기며, 잘못된 Job 파라미터는 시작 전에 거절하고 운영 자동 실행을 끔(#245)
status: 확정
date: 2026-11-07
paths: [payout/src/main/java/com/ok/payout/adapter/in/batch/PayoutJobRunner.java, payout/src/main/java/com/ok/payout/adapter/in/batch/PayoutJobParametersValidator.java, payout/src/main/java/com/ok/payout/domain/type/PayoutRunType.java, payout/src/test/java/com/ok/payout/ArchitectureTest.java, common/src/test/resources/application-test.yml, monolith/src/main/resources/application-prod.yml]
related: [389, 245, 441, 469, 390]
---

> 이 문서는 #485 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

대기 건너뛰기 API(#224)는 발표용으로 운영에도 등록되어(#269) 실행하면 15일 대기 없이 실제 입금까지 나간다. 스냅샷에는 어느 경로로 실행됐는지 남지 않았다(세미 3차 멘토 리뷰). 첫 구현 PR #406은 헥사고날 전환(#441) 전 구조라 닫고, #389를 #485로 다시 올렸다.

헥사고날 구조에서는 배치 입구가 `domain` 중 `PayoutPolicy`만 쓸 수 있어서, 입구가 정하는 실행 유형 enum을 어디 둘지가 새 문제였다. 같은 문제는 `PayoutPolicy` 예외, `PayoutEventType` 문자열 우회에 이어 세 번째다. 엔티티가 JPA 엔티티 그대로라(#441) 엔티티와 값이 `domain` 한 칸에 있기 때문이다.

범위 밖: monolith에서 스위치 켜기, `PayoutEventType`을 `domain/type`으로 옮기기(관리자 조정 API 검증이 함께 바뀜), 엔티티·도메인 분리(#441 결정).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 실행 유형 enum 위치 | `domain/type/PayoutRunType` 하나. 입구 중에는 배치만 `domain.type`을 쓸 수 있고(application·엔티티는 원래 domain을 씀), `domain.type`에는 enum만 둔다 | enum을 port.in과 domain에 두 벌 두지 않고 입구·엔티티 모두 타입으로 막는다. 웹·이벤트 입구는 `api.md`(컨트롤러는 domain 타입 금지)대로 계속 막는다 (사용자 결정) |
| 실행 유형을 정하는 곳 | `PayoutJobRunner.runScheduled()` / `runManual()` / `runSkippingWait()` 메서드 | 컨트롤러가 enum을 몰라도 되고, SKIP_WAIT를 스위치 확인 없이 넘기는 길이 없다 |
| 스위치 위치 | `payout.skip-wait-run.enabled`(기본 false)를 정산 설정에만 둠 | monolith는 사라지고 모듈별 독립 서버로 간다 (사용자 결정) |
| 꺼졌을 때 응답 | 409 `SKIP_WAIT_RUN_DISABLED`, `PayoutJobRunner`에서 확인 | 404는 "없는 주소"와 구분되지 않고, 501은 "아직 안 만든 기능"이라 뜻이 다르다 |
| 잘못된 Job 파라미터 | Job 시작 전 검사기. 예외는 `PayoutErrorCode`(`MISSING_PAYOUT_REQUESTED_AT`, `INVALID_PAYOUT_RUN_TYPE`) | 잘못 시작된 실행이 FAILED 정산 기록으로 남지 않게 한다. 정산 배치 경로는 정산 에러 코드를 쓴다(exception.md) (사용자 결정) |
| 운영 자동 실행 | 운영 `spring.batch.job.enabled`를 false로 (#245를 같은 PR에서 처리) | 켜져 있으면 빈 파라미터 자동 실행이 검사기에 걸려 운영 앱이 시작되지 않는다(실행으로 확인). `BATCH_JOB_ENABLED`는 남기고 "켜면 앱이 안 켜짐" 경고를 적음 (사용자 결정) |
| 기존 스냅샷 | 마이그레이션에서 `SCHEDULED`로 채운 뒤 DEFAULT 삭제 | 기존 행의 실제 경로는 알 수 없다(개발 데이터). 값을 빠뜨린 INSERT가 조용히 정기 실행으로 기록되지 않게 한다 |
| 테스트에서 스위치 켜기 | 공용 `application-test.yml`에 `optional:classpath:module-test.yml` import 한 줄, 값은 `payout/src/test/resources/module-test.yml` | 공용 테스트 설정에 정산 값을 쌓지 않는다(모듈 독립 방향). 인프라 담당 확인 대상 (사용자 결정) |

## 버린 대안

- **enum 위치:** port.in에 두고 엔티티는 문자열로 저장하는 안은 엔티티 칸을 타입으로 막지 못해 버렸다. port.in·domain에 같은 enum을 두고 서비스가 바꾸는 안은 같은 목록을 두 번 적어야 해 버렸다.
- **잘못된 파라미터:** Tasklet 안에서 FAILED로 끝내는 기존 방식은 잘못 시작된 실행이 정산 실패 기록에 섞여 버렸다.
- **스위치 위치:** monolith 설정에도 키를 넣어 환경 변수로 켜는 안은 monolith가 사라질 예정이라 버렸다.
- **`BATCH_JOB_ENABLED` 삭제:** 변수를 없애고 false로 고정하는 안은 검토했지만 남기기로 했다.

## 건드릴 때 주의

- `spring.batch.job.enabled`를 어느 프로필에서든 true로 켜면 앱 시작이 실패한다. Spring Batch 6.0.5는 검사기보다 JobInstance를 먼저 만들어, 두 번째 시작부터는 "Cannot find any job execution" 예외로 실패한다.
- `domain/type`에 enum이 아닌 클래스나 정산의 다른 패키지·JPA·Spring을 아는 코드를 두면 구조 테스트가 실패한다. 웹·이벤트에서 실행 유형이 필요하면 문자열로 받아 서비스에서 바꾼다.
- 대기 건너뛰기 스위치는 같은 이름의 환경 변수로도 켜진다. 로컬 docker-compose는 `.env`를 통째로 넘기므로 monolith도 켜질 수 있다.
- Spring Batch `JobParameter.equals`는 이름만 비교한다. Job 파라미터 값을 검증하는 테스트는 `verify(..).start(job, params)`가 아니라 값을 꺼내 비교한다.
