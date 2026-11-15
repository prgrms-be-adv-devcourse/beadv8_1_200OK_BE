---
issue: 626
module: product
summary: product 하위 도메인을 헥사고날 구조로 옮기며 엔티티는 그대로 두고, 유스케이스를 부르는 쪽 기준으로 묶고, 저장소는 member처럼 *JpaRepository가 포트를 상속하게 했다. review는 다음 작업까지 레이어 구조로 두고 예외로 묶었다.
status: 확정
date: 2026-11-15
paths: [product/src/main/java/com/ok/product/product/application, product/src/main/java/com/ok/product/product/adapter, product/src/test/java/com/ok/product/ArchitectureTest.java]
related: [627, 476, 477, 479, 450, 531]
---

> 이 문서는 #626 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

product 모듈(하위 도메인 `product`, `review`)을 member·order와 같은 헥사고날 구조로 옮기는 첫 단계다. 이번에는 `product` 하위 도메인만 옮기고 `review`는 #627에서 옮긴다. 바깥에서 보이는 것(URL, JSON, DB 스키마, 설정 키, 에러 코드, order·refund가 부르는 내부 API)은 바꾸지 않는다. 엔티티에서 JPA를 걷어내는 일은 범위 밖이다(작업 지시에서 환불 #481이 그 작업 때문에 크게 커졌다는 이유로 뺐다).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 커밋 순서 | 특성 테스트 → 이동만 → 포트·어댑터 → ArchitectureTest → 문서 (사용자 결정) | 리뷰어가 "이름만 바뀌었는지"와 "새로 넣은 구조"를 나눠 보고, 커밋마다 테스트로 깨진 곳을 좁힌다 |
| 특성 테스트 | order가 409 본문에서 찾는 `option-not-purchasable`, `insufficient-stock` 글자를 HTTP로 고정하는 메서드를 기존 내부 API 통합 테스트에 추가 (사용자 결정) | 기존 테스트는 상태 코드만 보거나 enum에서 계산한 값과 비교해, 글자가 바뀌어도 통과했다 |
| 유스케이스 단위 | 부르는 쪽 기준 5개(구매자, 판매자, 주문 옵션 조회, 재고 배정·해제, 환불 일부 해제) (사용자 결정) | order처럼 동작마다 두면 메서드 1개짜리 인터페이스가 14개가 된다. 묶으면 컨트롤러 슬라이스 테스트도 목 타입 1개만 바뀐다 |
| 일부 해제 | `ProductStockPartialReleaseFacade`만 유스케이스를 구현하고 `ProductStockService.partialRelease`·`isPartiallyReleased`는 넣지 않으며 공개 범위도 그대로 | 겹친 환불 요청이 롤백되면 새 트랜잭션에서 다시 확인해야 해 Facade를 거쳐야 한다. `@Transactional` 메서드의 공개 범위를 바꾸면 프록시 적용 여부가 바뀔 수 있어 건드리지 않았다 |
| 저장소 포트 | 이름은 그대로(`*Repository`), `*JpaRepository`가 포트를 상속, `@Query`·`@Modifying`·`@Lock`은 `*JpaRepository`에서 `@Override`로 다시 선언 | member·order 방식을 목표로 정했다. 포트 이름을 유지해 서비스와 단위 테스트는 import만 바뀐다 |
| 통합 테스트 | 저장소 필드 타입만 `*JpaRepository`로 | `deleteAll`·`count`처럼 포트에 없는 메서드를 쓴다. DEVELOPMENT.md member 절의 방식이다 |
| 헤더 저장소 | `ProductStockAllocationHeaderJpaRepository`는 `Repository`만 상속 | 기존 이유(#531) 그대로다. `JpaRepository`의 `findById`·`save`가 upsert보다 먼저 불리면 스냅샷이 일찍 잡힌다 |
| 잠금 규칙 | 이름(`ForUpdate`)으로 찾고 `@Lock` 또는 네이티브 `FOR UPDATE`를 인정, 수정용 상품 조회만 `OPTIMISTIC_FORCE_INCREMENT` 예외 | 메서드 이름을 바꾸는 것은 이번 범위가 아니다. 예외는 "포트 이름.메서드 이름"으로 적는다(같은 이름의 다른 조회와 섞이지 않게) |
| review 예외 | review를 하위 도메인 목록 밖 허용 패키지와 하위 도메인 사이 규칙의 예외로 두고, review에는 레이어 규칙을 그대로 건다 | review는 product의 엔티티·저장소·에러 코드·웹 DTO를 직접 쓴다. 정리는 #627에서 한다 |
| freeze | 기준선과 설정을 지움 | 기준선이 모두 0건이었다. member·order도 freeze 없이 운영한다 |

## 버린 대안

- **review까지 함께 정리:** review가 product 유스케이스를 쓰게 하는 일을 이 작업에 넣는 안은 review 로직까지 바뀌어 PR이 커져 버렸다.
- **`partialRelease`를 package-private으로:** Facade만 부르게 강제할 수 있지만, 트랜잭션 메서드 공개 범위를 바꾸는 위험을 감수할 만큼의 이득이 없어 버렸다.
- **메서드 이름 바꾸기(`findForUpdate...`):** 잠금 규칙과 이름을 맞출 수 있지만 작업 지시의 "이름 개선 금지"에 걸려 버렸다.

## 건드릴 때 주의

- 서비스에서 저장소 메서드를 새로 쓰면 포트에도 선언한다. `@Query`·`@Lock`이 필요하면 `*JpaRepository`에서 `@Override`로 다시 선언해야 설정이 붙는다.
- 이름 규칙과 잠금이 어긋나는 조회를 더하면 `ArchitectureTest`의 `LOCK_MODE_BY_METHOD`에 "포트 이름.메서드 이름"으로 적는다.
- 컨트롤러 슬라이스 테스트(`ProductControllerTest`)는 `SellerProductUseCase`를 목으로 둔다. 컨트롤러가 다른 유스케이스를 쓰면 목도 함께 바꾼다.
