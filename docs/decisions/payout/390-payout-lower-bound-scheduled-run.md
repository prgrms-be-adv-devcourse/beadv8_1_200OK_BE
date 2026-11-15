---
issue: 390
module: payout
summary: 정산 조회 하한을 마지막 정산 생성일 대신 "정산을 만든 마지막 성공 정기 실행의 requestedAt"으로 바꾸고, 그런 실행이 없으면 예전 방식으로 계산함
status: 확정
date: 2026-11-07
paths: [payout/src/main/java/com/ok/payout/application/service/PayoutService.java, payout/src/main/java/com/ok/payout/adapter/out/persistence/PayoutQuerydslRepository.java, payout/src/main/java/com/ok/payout/application/port/out/PayoutQueryRepository.java]
related: [485, 388, 291, 441]
---

> 이 문서는 #390 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

정산 Job은 매일 정산 후보를 "하한 이상, 상한 미만" 기간에서 가져온다. 상한은 실행일 − 15일(대기 기간)이고 이번에 바꾸지 않았다. 하한은 가장 최근 `payout`의 생성일 − 18일(대기 15일 + 여유 3일)이었는데, 관리자 실행이나 대기 건너뛰기 실행으로 만든 정산도 하한을 움직였다. 멘토 리뷰(1차·3차)는 Spring Batch 실행 기록의 마지막 COMPLETED 실행을 기준으로 하라고 했다. 실행 유형은 #485에서 Job 파라미터(`runType`)와 `payout_snapshot.run_type`에 남기기 시작했다.

범위 밖: 상한, 하한 밖으로 밀린 실패 후보의 재처리(#291), 멘토가 제안한 runDate 식별 파라미터와 재시작(3차 멘토 리뷰 때 실패 모아 재실행으로 대신하기로 함).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 하한 기준을 어디서 읽나 | Spring Batch 실행 기록(상태, `requestedAt`, `runType`) + `payout_snapshot` + `payout` | 스냅샷에는 성공 여부가 없고, `period_to`에서 거꾸로 계산하면 대기 건너뛰기의 미래 날짜가 섞인다 (사용자 결정) |
| 실행일과 "마지막" | 실행일 = `requestedAt` 날짜, 마지막 = 실행 ID가 가장 큰 것 | 상한도 `requestedAt` 날짜로 계산하므로 하한 = 지난 상한 − 3일로 맞물린다. 정기 실행은 하루 한 번이라 ID 순서와 날짜 순서가 같다 (사용자 결정) |
| 어떤 실행을 기준으로 보나 | 실행 유형 `SCHEDULED`만 | ⚠️ 멘토는 "마지막 COMPLETED"라 유형 제한이 없었다. 대기 건너뛰기는 `requestedAt`이 +17일이라 하한이 상한보다 뒤로 가 정산 0건이 되고, 관리자 실행까지 넣으면 개별 실행이 하한에 끼어든다. 정기 실행이 실패한 날은 조회 기간이 조금 넓어지는 것으로 받아들인다 (사용자 결정) |
| 정산 0건인 성공 실행 | 기준에서 뺌 (`EXISTS payout`) | 예전 동작 "정산이 생겨야 하한이 옮겨짐"을 지킨다. 후보가 없던 날과 검사로 전부 빠진 날 모두, 빠진 후보를 다음 날 다시 조회 범위에 넣는다 (사용자 결정) |
| `runType` 파라미터도 확인 | 스냅샷 `run_type`과 실행 기록 `runType` 둘 다 `SCHEDULED` | #485 마이그레이션이 옛 스냅샷을 모두 `SCHEDULED`로 채웠다. 실행 기록에 `runType`이 없는 #485 전 실행(예전 대기 건너뛰기 포함)을 기준에서 빼기 위해서다 |
| 기준 실행이 없을 때 | 마지막 `payout` 생성일 − 18일, 정산도 없으면 하한 없음 | 실행 기록을 지워도 하한이 통째로 사라져 전체를 조회하지 않게 한다. −15일이 아닌 −18일로 평소 계산과 맞춘다. 범위는 정산이 멈춘 기간만큼만 넓어지고 이미 정산된 후보는 "미정산" 조건으로 빠진다 (사용자 결정) |
| 코드 위치 | 출력 포트 `PayoutQueryRepository`에 메서드 하나, 구현은 `PayoutQuerydslRepository`의 `EntityManager` 네이티브 SQL 하나 | Spring Batch 표는 JPA 엔티티가 아니라 Querydsl로 볼 수 없다. 새 출력 칸을 만들면 구조 테스트와 모듈 규칙이 바뀐다 (사용자 결정) |
| 테스트 격리 | `BATCH_*` 표를 비우는 코드를 만들지 않음 | 쿼리가 스냅샷과 정산을 거쳐 실행 기록을 찾으므로, 정산·스냅샷을 비우면 남은 실행 기록은 잡히지 않는다. 통합 테스트로 확인했다 |

## 버린 대안

- **기준 위치:** `payout_snapshot.run_type`만 보는 안은 실행 성공 여부를 알 수 없어 버렸다. 배치 입구가 실행 기록을 읽어 서비스에 날짜를 넘기는 안은 하한 규칙이 두 곳에 나뉘어 버렸다. 새 `adapter/out/batch` 칸에서 Spring Batch API로 찾는 안은 "SCHEDULED이면서 성공한 마지막 실행"을 바로 찾는 기능이 없고 구조 규칙이 바뀌어 버렸다.
- **관리자 실행 포함:** 멘토 말에는 더 가깝지만 위 이유로 버렸다.
- **대체 계산 −15일 / 하한 없음:** −15일은 여유 3일이 없어 경계 근처 후보를 놓칠 수 있고, 하한 없음은 매번 전체를 조회한다.
- **`BATCH_*` 정리:** 테스트 정리 코드를 공통 표까지 넓힐 필요가 없어 버렸다.

## 건드릴 때 주의

- 네이티브 SQL이 Spring Batch 표 이름·열 이름(`BATCH_JOB_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`, `STATUS`, `PARAMETER_NAME`, `PARAMETER_VALUE`)을 직접 쓴다. Spring Batch를 올려 표 구조가 바뀌면 이 쿼리도 고쳐야 한다.
- Spring Batch 표도 `paldogotgan_payout` 스키마에 있다. monolith는 `spring.batch.jdbc.table-prefix`로 그 위치를 가리키므로 SQL의 표 이름에 `PayoutSchema.NAME`을 붙인다.
- `requestedAt`은 Spring Batch가 `LocalDateTime`을 ISO 글자로 저장한 값을 `LocalDateTime.parse`로 읽는다. 파라미터 이름이나 타입을 바꾸면 하한 조회가 깨진다.
- `PayoutJobRunner`의 `runType` 파라미터 이름·값을 바꾸면 모든 실행이 기준에서 빠져 대체 계산으로만 동작한다(오류 없이 조용히 바뀐다).
- 테스트에서 정기 실행 요청일과 정산 생성일이 같은 날이면 쿼리가 고장 나도 대체 계산이 같은 값을 낸다. 하한 기준을 확인하는 테스트는 두 날짜를 다르게 만든다.

## 후속 (2026-11-13)

- 범위 밖으로 둔 "하한 밖으로 밀린 실패 후보의 재처리"는 #291이 아니라 #549에서 했다. 실패가 1~2번인 주문의 후보는 하한 없이 상한 전까지 다시 넣고, 3번이면 멈춘다. 이 문서의 하한 계산은 바꾸지 않았다.
