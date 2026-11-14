---
issue: 531
module: product
summary: 해제가 배정보다 먼저 처리돼도 재고가 새지 않게 주문 단위 헤더 행으로 배정·해제를 줄 세우고 해제 기록을 남김
status: 확정
date: 2026-11-14
paths: [product/src/main/java/com/ok/product/product/service/ProductStockService.java, product/src/main/java/com/ok/product/product/repository/ProductStockAllocationHeaderRepository.java, product/src/main/resources/db/migration/product/V202611141444__product__create_product_stock_allocation_header_table.sql]
related: []
---

> 이 문서는 #531 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

주문 모듈은 재고 배정을 3초 안에 응답받지 못하면 해제를 보내고 주문을 취소 상태로 남긴다(#525 전에는 주문을 지웠다). 상품 모듈이 해제를 배정보다 먼저 처리하거나, 배정이 커밋되기 전에 처리하면 해제는 할 일이 없어 204로 끝난다. 그 뒤 배정이 커밋되면 취소된 주문의 재고가 영구히 잡힌다.

이번 범위는 상품 모듈에서 원인을 막는 것이다. 주문 모듈 코드는 바꾸지 않는다. 범위 밖으로 둔 것은 다음과 같다.

- 해제 요청 자체가 실패하는 경우의 재시도(주문을 "해제 대기"로 남기는 방식). 해제 실패 로그가 나오면 별도 이슈로 다룬다. 이후 주문 쪽 해제 재시도나 saga를 도입해도 헤더는 버리지 않는다. 메시지 기반 재시도는 중복과 순서 뒤바뀜이 생기므로, 상품 쪽에서 이를 막는 장치로 헤더를 그대로 쓴다.
- 이미 새어 나간 재고의 정리. 아래 "누수 탐지 쿼리" 결과를 보고 수동으로 해제한다.
- 정합성 배치, 배정 TTL, 타임아웃 값 조정.

## 결정과 이유

"재고 해제"의 뜻을 바꿨다: 주문이 배정한 재고를 모두 옵션에 돌려놓고, **그 주문의 이후 배정을 막는 일**이다. 배정보다 먼저 와도 같다.

| 결정 | 선택 | 이유 |
|---|---|---|
| 직렬화 장치 | 주문 ID를 PK로 하는 헤더 테이블 `product_stock_allocation_header(order_id, status)` | 늦게 도착하는 배정과 동시에 실행되는 배정을 한 장치로 막는다. 기존 배정 행 구조는 그대로 둔다 |
| 헤더 잠금 방법 | 배정·해제 모두 `INSERT … ON DUPLICATE KEY UPDATE`로 헤더 X 잠금을 바로 잡는다 | 중복일 때만 넘어가고 다른 오류는 그대로 실패한다. 장바구니 저장소(`CartJpaRepository`)에 같은 방식의 선례가 있다 (사용자 결정) |
| 해제 기록 | 해제는 배정 기록이 없어도 헤더를 RELEASED로 만든다 | 먼저 처리된 해제가 묘비 역할을 해 늦은 배정을 409 `ALLOCATION_ALREADY_RELEASED`로 거절한다 |
| 배정 판정 순서 | ① 헤더 upsert ② 헤더가 RELEASED면 409 ③ 배정 기록에 RELEASED가 있으면 409 ④ 배정 기록이 있으면 아무것도 하지 않음 | 헤더는 새로 만든 경우와 이미 있던 경우 모두 ALLOCATED라 멱등 판정은 배정 기록으로만 할 수 있다. ③은 헤더가 없던 과거 해제 주문을 백필 없이 막는다 (사용자 결정) |
| 헤더 upsert 위치 | 배정·해제 트랜잭션의 첫 SQL | 아래 "건드릴 때 주의" 참고 |
| 시각 | `Clock`에서 얻은 값을 `:now`로 넘긴다. `NOW(6)`를 쓰지 않는다 | 레포의 시각 규칙(`service.md`)을 따른다 (사용자 결정) |
| 쓰기 경로 | 헤더는 네이티브 쿼리로만 만들고 바꾼다. 저장소는 `JpaRepository`가 아니라 `Repository`를 상속한다 | ID를 직접 넣는 엔티티라 `save()`가 merge로 동작해 먼저 SELECT를 실행한다. 이 SELECT가 upsert보다 앞서면 첫 SQL 조건이 깨진다 |
| 같은 주문 동시 배정 응답 | 모두 204. `ALLOCATION_IN_PROGRESS`를 지웠다 | 헤더에서 줄을 서므로 배정 기록 유니크 키 충돌이 생기지 않는다. 남겨 두면 다른 제약 위반(버그)까지 "다시 요청" 409로 숨긴다 |

순환 대기가 없는 이유: 잠금 순서는 헤더 → 배정 행 → 옵션 행(옵션 ID 오름차순)이다. 각 트랜잭션은 헤더를 하나만, 첫 잠금으로 잡는다. 일부 해제는 헤더 없이 배정 행 → 옵션 행 순서라 같은 방향이다.

## 버린 대안

- **INSERT IGNORE:** 중복 키에 S 잠금을 잡고, 뒤의 `FOR UPDATE`에서 X로 올린다. 그래서 같은 주문의 배정끼리 데드락이 난다. 사용자가 MySQL에서 실험해 확인했다(배정+해제 조합은 데드락이 나지 않았다). 또 NULL 같은 다른 오류도 경고로 바꿔 삼킨다.
- **두 메서드만 READ COMMITTED:** 조회마다 새 스냅샷을 써서 첫 SQL 조건 자체가 사라진다. 다만 한 모듈 안에 격리 수준이 섞이고, 일부 해제 Facade는 REPEATABLE READ를 전제로 짜여 있다. 이번에는 넣지 않았고, 첫 SQL 조건이 자주 깨지면 다시 검토한다.
- **해제 묘비만 추가(직렬화 없음):** 확인과 INSERT 사이의 경쟁이 남는다.
- **잠금만으로 순서 맞추기:** 아직 도착하지 않은 배정은 막지 못한다.
- **정합성 배치, 배정 TTL:** 원인을 막지 않는 사후 대응이다. 배치는 모듈을 넘는 조회가 필요하고, TTL은 "확정" 상태를 새로 만들어야 해서 범위가 크다.
- **Kafka(키 = 주문 ID)로 순서 보장:** 주문 생성이 비동기로 바뀌고 outbox가 필요하다. DLQ 재처리 때문에 헤더 같은 장치가 여전히 필요하다. 이 버그만으로는 도입하지 않는다.
- **타임아웃 값 조정:** 값을 바꿔도 순서 역전은 사라지지 않는다.
- **상품 → 주문 존재 확인:** 모듈 순환 의존이 생긴다.
- **기존 주문 백필:** 배정 판정 ③으로 대신했다.

감수하는 것은 세 가지다.
- 헤더 행이 주문마다 하나씩 계속 쌓인다. 보관 기간은 정하지 않았다.
- 배정과 해제마다 SQL이 늘어난다.
- 해제가 진행 중인 배정을 기다리므로 응답이 늦어질 수 있다.

## 건드릴 때 주의

- **헤더 upsert는 반드시 첫 SQL이어야 한다.** REPEATABLE READ의 스냅샷은 잠금 없는 첫 조회 때 정해지고, upsert 같은 쓰기는 스냅샷을 만들지 않는다. upsert 앞에 일반 조회가 하나라도 있으면, 해제가 배정의 커밋을 기다린 뒤에도 낡은 스냅샷 때문에 그 배정 기록을 보지 못해 #531이 되살아난다. 다음도 같은 이유로 조건을 깬다.
  - 바깥 트랜잭션 안에서 `allocate`·`release`를 호출하는 것(REQUIRED로 합류)
  - 헤더 저장소에 `findById`·`save`를 되살리는 것
  - 지금은 이를 강제하는 런타임 검사가 없고, 주석과 재현 테스트로만 지킨다.
- **50초 한계.** 해제가 헤더를 기다리는 시간은 `innodb_lock_wait_timeout`(기본 50초)까지다. 배정이 옵션 행 잠금을 기다리느라 헤더를 그보다 오래 쥐면 해제가 롤백된다. 주문 쪽 `cancelCreatedOrder`는 해제 실패를 로그만 남기고 다시 보내지 않으므로, 이 경우 늦은 배정이 남는다.
- **주문 쪽 해제 로그.** 주문 쪽의 3초 시간 초과는 상품 쪽 트랜잭션을 멈추지 않는다. 그래서 해제가 상품 쪽에서는 끝났는데 주문 쪽에 "재고 선점 해제 실패" 로그가 남을 수 있다. 누수 여부는 탐지 쿼리로 확인한다.
- **ODKU 반환값.** Connector/J 기본 설정(`useAffectedRows=false`)은 변경 없는 갱신도 1을 돌려준다. 그래서 반환값으로 "새로 넣었는지" 판정하지 않는다.
- **컬럼 추가.** 헤더 쿼리는 컬럼을 직접 나열한다. 엔티티나 `BaseTime`에 NOT NULL 컬럼을 추가하면 이 쿼리들도 함께 고쳐야 한다.
- **스키마 이름.** 네이티브 쿼리에는 엔티티의 catalog 지정이 적용되지 않아 테이블 이름을 `ProductSchema.NAME`으로 직접 한정한다.
- **배정 판정 ③.** 헤더가 없던 과거 주문을 위한 경로라 언젠가 지울 수 있다. 다만 지우는 조건은 정하지 않았다.

## 누수 탐지 쿼리

배정이 남아 있는데 주문이 무산됐거나 없는 경우를 찾는다. 상품과 주문 스키마를 함께 읽어야 해서 코드가 아니라 운영 DB에서 직접 실행한다.

- 주문이 없는 경우: #525 전에 지워진 주문이다.
- `CANCELED`: 주문 생성 중 실패한 주문(`cancelCreatedOrder`)이다. 이번 버그로 새는 경로다.
- `PAYMENT_FAILED`: 결제 실패 뒤 해제가 실패한 주문이다.
- 방금 만든 주문은 해제가 진행 중일 수 있어 10분이 지난 배정만 본다.
- (미확인) 운영 DB에서 아직 실행하지 않았다.

```sql
SELECT a.order_id, a.product_option_id, a.quantity, a.created_at, o.status AS order_status
FROM paldogotgan_product.product_stock_allocation a
LEFT JOIN paldogotgan_order.orders o ON o.id = a.order_id
WHERE a.status = 'ALLOCATED'
  AND a.quantity > 0
  AND a.created_at < NOW() - INTERVAL 10 MINUTE
  AND (o.id IS NULL OR o.status IN ('CANCELED', 'PAYMENT_FAILED'))
ORDER BY a.created_at;
```
