---
issue: 476
module: member
summary: member를 Kafka 전환에 대비해 하위 도메인별로 헥사고날 구조로 옮기기 시작했다. seller를 먼저 옮기고, 다른 하위 도메인은 seller의 유스케이스 포트와 dto만 쓰게 했다.
status: 확정
date: 2026-11-07
paths: [member/src/main/java/com/ok/member/seller, member/src/main/java/com/ok/member/auth/service/AuthService.java, member/src/test/java/com/ok/member/ArchitectureTest.java]
related: [475, 477, 478, 479]
---

> 이 문서는 #476 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

모듈을 서버별로 나누면 Spring 이벤트를 쓸 수 없어 Kafka로 바꿔야 한다. 연동 방식이 바뀌어도 비즈니스 코드가 바뀌지 않게, member 모듈을 헥사고날 구조로 옮긴다. 전체 계획은 #475이고 하위 도메인마다 이슈를 나눴다(seller #476, member #477, auth #478, 마무리 #479).

- 이번 이슈는 seller만 옮긴다. 동작, API, DB 스키마는 그대로다.
- auth는 `AuthService`가 seller를 부르는 부분(주입 타입)만 바꾼다. member·auth 하위 도메인은 레이어 구조로 남는다.
- Kafka 전환 자체는 범위 밖이다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 전환 단위 | 하위 도메인마다 이슈·PR 하나 (사용자 결정) | member 전체를 한 PR로 옮기면 리뷰 범위가 너무 크다. seller는 다른 하위 도메인이 거의 의존하지 않아 먼저 옮기기 쉽다 |
| 입력 포트 개수 | 서비스 단위로 두고 auth가 쓰는 조회만 뗌: `SellerApplicationUseCase`, `SellerApprovalCheckUseCase`, `SellerProfileUseCase` (사용자 결정) | auth를 다른 서버로 나누면 이 호출이 원격 호출이 되는 경계라 따로 둔다. 신청자·관리자 기능은 지금 나눌 근거가 없어 필요해지면 그때 나눈다 |
| auth용 포트 이름 | `SellerApprovalCheckUseCase` (사용자 결정) | `*Query`는 `service.md`에서 조회 조건 DTO 이름이라 겹치지 않게 `Check`로 짓는다 |
| auth용 포트 구현 | 별도 서비스 없이 `SellerApplicationService`가 함께 구현 (사용자 결정) | 서비스 클래스는 나누지 않는다는 결정과 같다. auth를 옮길 때(#478)도 auth는 인터페이스만 알아 구현 위치와 무관하다 |
| 하위 도메인 사이 규칙 | 다른 하위 도메인은 옮긴 하위 도메인의 `port.in`과 `dto`만 사용, ArchUnit으로 검사 | 하위 도메인을 나눌 때 유스케이스 호출만 바꾸면 되게 한다 |
| 출력 포트 구현 | `*JpaRepository`가 `JpaRepository`와 포트를 함께 상속 | delivery와 같은 방식이라 실행 객체가 Spring Data 프록시 그대로이고 낙관적 락·중복 키 처리 동작이 바뀌지 않는다 |
| 통합 테스트의 저장소 | `*JpaRepository`를 주입 (사용자 결정) | 데이터 준비·정리에 포트에 없는 메서드(`deleteAll` 등)를 쓴다. 테스트를 위해 포트를 넓히지 않는다 |
| 커밋 순서 | 패키지 이동만 하는 커밋을 먼저 두고, 로직 변경은 그 뒤에 (사용자 결정) | 이동과 변경이 섞이면 리뷰에서 바뀐 줄을 찾기 어렵다. 첫 커밋에서 기존 `ArchitectureTest` 규칙의 대상에서 seller를 빼 모든 커밋에서 테스트가 통과하게 한다 |
| 문서 반영 시점 | 구조가 처음 바뀌는 이 PR에서 `DEVELOPMENT.md`, `CLAUDE.md`를 고침 (사용자 결정) | 마지막에 몰아서 고치면 그 사이 머지된 코드와 문서(`CLAUDE.md`의 헥사고날 모듈 목록, `api.md` 자동 로드 안내)가 어긋난다 |
| `CLAUDE.md` 수정 방식 | 헥사고날 모듈 목록에 member 한 줄만 추가 (사용자 결정) | 다른 모듈도 동시에 전환 중이라 같은 목록에 한 줄씩 더한다. 문단을 다시 쓰면 다른 PR과 충돌하고 다른 모듈 항목이 빠질 수 있다 |

## 버린 대안

- **입력 포트:** 신청자용·관리자용·auth용으로 나누는 안은 지금 나눌 근거가 없어 버렸다. 감수하는 것은 `SellerApplicationUseCase`에 신청자와 관리자 메서드가 함께 있다는 점이다.
- **auth용 포트 이름:** `SellerApprovalQueryUseCase`처럼 `Query`를 붙이는 안은 조회 조건 DTO 접미사와 겹쳐 버렸다.
- **통합 테스트:** 포트에 `deleteAll` 같은 정리용 메서드를 추가하는 안은 테스트를 위해 포트를 넓히는 것이라 버렸다.
- **ArchUnit 규칙:** 기존 레이어 규칙을 헥사고날 규칙으로 바꿔 쓰는 안은 member·auth가 아직 레이어 구조라 쓸 수 없다. 그래서 seller만 기존 규칙 대상에서 빼고 `seller_` 규칙을 따로 두었다.

## 건드릴 때 주의

- `*JpaRepository`에서 다시 선언한 `findById`, `saveAndFlush`를 지우면 `*JpaRepository` 타입으로 부르는 곳(통합 테스트, `MemberModuleTest`)에서 모호한 호출 컴파일 오류가 난다. 포트와 `JpaRepository`가 같은 메서드를 다른 제네릭 형태로 물려주기 때문이다.
- `ArchitectureTest`의 `SELLER` 상수와 `resideOutsideOfPackage(SELLER)`는 레이어 규칙에서 seller를 빼려고 둔 것이다. member·auth를 옮기면 같은 방식으로 빼고, 모두 옮기면 #479에서 레이어 규칙과 함께 지운다.
- freeze된 레이어 규칙은 설명(`because`)을 바꾸면 저장된 기준선과 맞지 않아 실패한다(`allowStoreCreation=false`). 그래서 이번에는 freeze 규칙을 고치지 않았다.
- `adapter/in/web`은 경로가 `controller`가 아니라 `.claude/rules/api.md`가 자동으로 로드되지 않는다. 직접 읽고 따른다.
- 메서드 계약 Javadoc은 입력 포트로 옮겼고 서비스 구현에는 `@Override`만 있다.
