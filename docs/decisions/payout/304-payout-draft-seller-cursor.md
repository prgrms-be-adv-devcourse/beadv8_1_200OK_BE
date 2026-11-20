---
issue: 304
module: payout
summary: draft가 판매자를 정산후보 표가 아니라 스냅샷 항목에서 판매자 번호 순 커서로 고르게 함 (스냅샷에 seller_id 복사, LEFT JOIN)
status: 확정
date: 2026-11-19
paths: [payout/src/main/java/com/ok/payout/adapter/out/persistence/PayoutQuerydslRepository.java, payout/src/main/java/com/ok/payout/adapter/in/batch/PayoutJobConfig.java, payout/src/main/java/com/ok/payout/domain/PayoutSnapshotItem.java, payout/src/main/resources/db/migration/payout/V202611181713__payout__add_seller_id_to_payout_snapshot_item.sql]
related: [90, 534, 291]
---

> 이 문서는 #304 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

정산 대량 처리 측정(판매자 10만 명, 후보 150만 줄)에서 draft가 Job 전체의 64%(7,342초)를 차지했다. 원인은 "다음 판매자 100명" 조회가 회차마다 남은 정산후보 전체를 판매자별로 묶고 정렬한 것이다. 1,001번 실행해 45억 줄을 읽었다(측정으로 확인).

이 문서는 #304의 세 결정 중 결정 1(draft 판매자 고르기)만 다룬다. 결정 2(verify 스냅샷 번호 검사 → DB 규칙)와 결정 3(snapshot 저장)은 다음 PR에서 따로 낸다(사용자 결정, 11/18). 판매자마다 따로 저장·확정하는 구조(#90)와 주문별 다시 시도(#534)는 바꾸지 않았다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 판매자를 어디서 고르나 | 정산후보 표가 아니라 이번 스냅샷 항목(`payout_snapshot_item`)에서 고름 | 스냅샷은 이번 배치 대상만 담아 쌓인 과거 후보를 읽지 않음. 후보 가져오기도 같은 이유로 스냅샷에서 출발해, 판매자의 지난 정산 후보를 꺼냈다 버리던 것이 없어짐 |
| 스냅샷에 무엇을 복사하나 | 판매자 번호(`seller_id`) 1칸 + 인덱스 `(snapshot_id, seller_id)` | draft를 빠르게 하는 데는 판매자만 필요함. 처음 안은 판매자·주문·주문상품·돈 종류 4칸이었으나 1칸으로 줄임(사용자 결정). 나머지는 결정 3에서 필요하면 그때 넣음 |
| 기존 스냅샷 줄 | 마이그레이션에서 정산후보의 판매자로 채우고 NOT NULL | 정산후보는 `@Immutable`이라 복사한 값이 어긋나지 않음. `candidate_id` 외래키가 있어 채우지 못하는 줄이 없음(확인). null보다 값을 채우는 쪽을 선호(사용자) |
| 고르는 순서 | 판매자 번호 순, "지난 회차 마지막 판매자 다음"부터 100명(커서) | 인덱스 순서대로 읽다 100명이 차면 멈춰 회차마다 그 근처만 읽음. 처리 순서가 "가장 먼저 들어온 후보 순"에서 바뀌지만 금액은 순서와 상관없음(사용자 결정) |
| 커서를 어디에 두나 | draft Step 실행 기록(ExecutionContext, `draftLastSellerId`) | tasklet은 회차마다 새로 불려 지역 변수로 못 넘김. 실행 기록은 회차마다 커밋되어 멈췄을 때 어디까지 했는지 배치 기록에서 보임 |
| 이미 처리한 줄 빼기 | 그대로 둠(판매자 draft·시스템 draft·실패 기록에 없는 줄만) | 번호가 앞으로만 가 없어도 끝나지만, 손으로 restart해 커서를 이어 쓸 때 같은 판매자를 두 번 처리하지 않게 하는 안전장치. 100명 범위만 검사해 비용이 작음 |
| 이미 처리한 줄 빼는 방식 | `NOT EXISTS` 대신 `LEFT JOIN ... IS NULL`, 두 조회가 같은 조건을 한 곳(`undraftedSnapshotItems`)에서 씀 | 측정 DB EXPLAIN(10만 명, 5만 명 draft 뒤): MySQL이 `NOT EXISTS`를 "처리한 줄 전체를 모아 중복 제거"로 바꿔 회차마다 0.7~0.9초, 처리할수록 늘어남. `LEFT JOIN`은 UK로 한 줄씩 찾아 9ms |
| PR 나누기 | 결정마다 PR, 중간 PR은 `Ref #304` | 결정 1만으로 10만 명 draft가 7,342초 → 2,175초로 줄어 먼저 냄. 결정 3은 방향(한 트랜잭션 유지 vs 나눠 확정)을 다시 정해야 함(사용자 결정) |

## 버린 대안

- **스냅샷 4칸 복사:** 판매자 외 3칸은 draft 속도에 쓰지 않아 디스크·마이그레이션만 늚.
- **커서를 tasklet 필드에:** `@StepScope`라 Step마다 새로 만들어지긴 하지만 값이 배치 기록에 남지 않아 멈춘 지점을 볼 수 없음.
- **이미 처리한 줄 빼기 조건 삭제:** 커서만으로도 끝나지만 restart 때 중복 처리를 막을 장치가 사라짐.
- **`NOT EXISTS` 유지:** 결과는 같지만 처리한 판매자가 늘수록 회차마다 느려짐(위 EXPLAIN).
- **"가장 먼저 들어온 후보 순" 유지:** 그 순서로 고르려면 남은 후보 전체를 정렬해야 해서 원래 문제가 그대로 남음.

## 건드릴 때 주의

- 판매자 고르기(`findUndraftedSellerIds`)와 후보 가져오기(`findUndraftedCandidates`)는 반드시 같은 조건(`undraftedSnapshotItems`)을 써야 함. 어긋나면 판매자는 뽑히는데 후보가 0건이 되어 같은 판매자를 계속 뽑음 → 서비스가 `UNSETTLED_CANDIDATES_NOT_FOUND`로 멈춤.
- tasklet은 고른 판매자가 0명이면 끝내고, 아니면 커서를 저장한 뒤 계속함. 다른 반환 경로를 더하면 커서를 저장하는지 확인.
- 스냅샷 항목을 만드는 곳(`PayoutSnapshotItem.create`)이 판매자를 복사함. 결정 3에서 스냅샷을 DB 안 복사(`INSERT ... SELECT`)로 바꾸면 `seller_id`도 함께 복사해야 함(NOT NULL이라 빠지면 실패).
- 다시 측정할 때는 측정 DB와 측정용 Kafka를 함께 비움. 측정 사이에 지갑이 읽는 토픽·그룹이 바뀌면(#679) 이전 측정 메시지를 지갑이 측정 중에 처리해 시간이 섞임(11/18 겪음).
