---
issue: 627
module: product
summary: review 하위 도메인을 헥사고날로 옮기며 review가 product를 리뷰 전용 유스케이스와 product dto로만 쓰게 하고(order 장바구니 방식), 외부 분석 서비스와 주문 모듈 호출을 출력 포트 뒤로, 분석 작업자를 입력 어댑터로 옮겼다.
status: 확정
date: 2026-11-15
paths: [product/src/main/java/com/ok/product/review, product/src/main/java/com/ok/product/product/application/port/in/ReviewedProductUseCase.java, product/src/test/java/com/ok/product/ArchitectureTest.java]
related: [626, 386, 387, 439, 450]
---

> 이 문서는 #627 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

product 모듈 헥사고날 전환의 두 번째 단계다. `review` 하위 도메인을 옮기고, 앞 단계(#626)에서 예외로 둔 review → product 직접 의존을 없앤다. 바깥에서 보이는 것과 트랜잭션·잠금 동작은 바꾸지 않는다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 특성 테스트 | 옮기기 전에 분석 작업자 2개(켜지는 설정, 이전 실행이 끝나고 5초, Facade 위임)를 `ApplicationContextRunner` 테스트로 고정 | 작업자는 운영 설정에서만 켜져 기존 테스트가 하나도 거치지 않았다 |
| review → product | product에 `ReviewedProductUseCase`(port.in)와 `ReviewedProductService`를 두고, review 서비스가 이것과 product dto(`ReviewedProductResult`, `ProductCategoryCode`)만 직접 쓴다 | review의 application이 분류 타입을 알아야 한다. member처럼 출력 어댑터에서만 부르게 하면 review 전용 분류 타입과 변환이 더 생긴다. 같은 프로세스 호출이라 order의 장바구니 방식(#439)을 따랐다 |
| 분류 타입 | review의 키워드 목록·입력 DTO가 엔티티 enum `ProductCategory` 대신 `ProductCategoryCode`를 씀 | 상수 이름이 같아 키워드 파일 해석 결과가 같고, review가 product 도메인을 모르게 된다 |
| 유스케이스 단위 | 부르는 쪽 기준 7개(리뷰 작성, 리뷰 관리·조회, 키워드, 감성 집계, 관리자 시험, 작업자 2개) | product와 같은 기준. 작성은 Facade, 나머지는 서비스가 구현해 한 인터페이스로 묶지 못한다 |
| 외부 호출 | 주문 모듈 호출은 포트 `OrderApiClient` + `OrderApiHttpClient`, 분석기 인터페이스 2개는 그대로 포트로 | wallet(#450)처럼 포트 이름을 기존 클래스 이름으로 유지해 Facade와 Fake는 import·상속만 바뀐다 |
| 페이지 응답 | 하위 도메인마다 `adapter/in/web/dto`에 같은 이름·필드의 `ProductPageResponse`를 둠 (리뷰 반영) | 서로의 웹 DTO를 쓰지 않으면서 JSON과 Swagger 스키마 이름을 그대로 둔다 |
| 잠금 규칙 | 이름 규칙 밖의 `SKIP LOCKED` 분석 요청 조회도 표로 검사하고, `@QueryHints(lock.timeout=-2)` 재선언까지 확인 (리뷰 반영) | 이 조회는 이름에 `ForUpdate`가 없어 규칙이 보지 못했다. 힌트가 빠지면 작업자가 서로를 기다린다 |
| 하위 도메인 사이 규칙 | "다른 하위 도메인은 유스케이스와 dto만" (order 방식) | 위 review → product 결정과 같은 기준을 구조 시험으로 지킨다 |

## 버린 대안

- **review/adapter/out/product 어댑터:** 서비스가 product 타입을 전혀 모르게 할 수 있지만, review 전용 분류 타입과 변환이 늘어 버렸다. 감수하는 것은 review 서비스가 product dto를 아는 것이다.
- **페이지 응답을 모듈 최상위에 하나로:** 중복은 없지만 모듈 최상위 패키지는 다른 모듈에 공개하는 자리(Spring Modulith 공개 API)라 버렸다. 감수하는 것은 같은 레코드가 두 벌인 것이다.
- **스케줄러 테스트의 목을 유스케이스로 바꾸기:** 옮기기 전과 같은 테스트로 동작이 같음을 보이려고 그대로 뒀다. 의존 방향은 ArchitectureTest가 지킨다.

## 건드릴 때 주의

- `ReviewedProductUseCase`가 던지는 `PRODUCT_NOT_FOUND`는 참여 중인 review 트랜잭션을 롤백 전용으로 만든다. review에서 잡고 계속 진행하면 커밋 때 `UnexpectedRollbackException`이 난다.
- 리뷰 작성은 집계(`addReviewStats`)를 리뷰 INSERT보다 먼저 한다. 상품 행 잠금 순서라 바꾸면 동시 작성에서 데드락이 난다(기존 주석 그대로).
- 두 `ProductPageResponse`는 필드와 이름을 함께 바꾼다. 한쪽만 바꾸면 두 API의 페이지 JSON이 달라진다.
- 분석 요청 조회처럼 이름에 `ForUpdate`가 없는 잠금 조회를 더하면 `LOCK_MODE_BY_METHOD`(와 SKIP LOCKED면 `SKIP_LOCKED_METHODS`)에 적는다.
