---
issue: 477
module: member
summary: member 하위 도메인을 헥사고날 구조로 옮기고, auth가 회원 저장소·엔티티 대신 MemberAuthAccountUseCase로 판단 결과만 받게 했다. 헥사고날 규칙은 하위 도메인 목록에 같은 규칙을 적용하도록 묶었다.
status: 확정
date: 2026-11-09
paths: [member/src/main/java/com/ok/member/member, member/src/main/java/com/ok/member/auth/service/AuthService.java, member/src/main/java/com/ok/member/auth/service/TokenRoleMapper.java, member/src/test/java/com/ok/member/ArchitectureTest.java]
related: [425, 475, 476, 478, 479]
---

> 이 문서는 #477 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

member 모듈을 하위 도메인별로 헥사고날 구조로 옮기는 두 번째 단계다(seller #476 다음). member 하위 도메인을 옮기고, auth가 회원을 읽던 곳(`AuthService`의 로그인·재발급, `TokenRoleMapper`)을 member의 입력 포트로 바꾼다. 동작, API, DB 스키마는 그대로다.

- auth 자체의 구조 변경은 #478이다. 이번에는 auth가 member를 부르는 방식만 바꾼다.
- #425에서 정한 로그인 구조(트랜잭션 없는 `LoginFacade`의 bcrypt 비교, 발급 트랜잭션 안의 재확인, 요청당 커넥션 1개)는 바꾸지 않는다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| auth용 유스케이스 이름 | `MemberAuthAccountUseCase` (사용자 결정) | 이슈의 `MemberAuthQueryUseCase`는 `*Query`가 조회 조건 DTO 접미사(`service.md`)라 겹친다. seller의 `SellerApprovalCheckUseCase`와 같은 이유다 |
| auth용 유스케이스 구현 | 새 서비스 없이 `MemberService`가 함께 구현 (추천안대로 진행) | seller와 같은 결정이다. 전체 계획의 `MemberAuthQueryService`는 `*QueryService` 분리 금지(`service.md`)에 걸린다. `MemberService`는 클래스가 읽기 전용 트랜잭션이라 발급 트랜잭션에 참여해 새 커넥션을 받지 않는다 |
| auth에 넘기는 값 | 엔티티·enum 대신 판단 결과(`admin`, `withdrawn`, `suspended`)와 해시를 담은 DTO | 상태 판단 규칙을 `Member` 한 곳(`isWithdrawn`, `isSuspended`, `isAdmin`)에 두고 auth는 에러 코드만 정한다. 해시는 bcrypt 확인 뒤 비밀번호가 바뀌었는지 보는 #425 재확인에 필요하다 |
| `TokenRoleMapper` 인자 | `MemberType` 대신 `boolean admin` (이슈 계획대로) | auth가 member의 enum을 모르게 한다. #478에서 auth가 자기 출력 포트를 두면 member DTO도 직접 받지 않게 된다 |
| 관리자 계정 부트스트랩 위치 | Config·Properties·Runner를 함께 `adapter/in/bootstrap` (추천안대로 진행) | 앱 시작 시 입력의 진입점이다. Runner는 Config의 `@Bean`이 만들고 package-private이라 따로 두면 public으로 바꿔야 한다 |
| 헥사고날 규칙 | 규칙 종류마다 하나를 두고 `HEXAGONAL_SUBDOMAINS` 목록에 같은 규칙을 적용(`CompositeArchRule`) (추천안대로 진행) | 하위 도메인마다 규칙을 복사하면 auth까지 세 벌이 된다. 목록에 더하기만 하면 되고 #479에서 모듈 전체 규칙으로 바꾸기도 쉽다 |
| 다른 하위 도메인 접근 규칙 | `onlyBeAccessed()` 대신 `onlyHaveDependentClassesThat()` | `onlyBeAccessed()`는 호출·필드 접근만 봐서, 타입 선언이나 클래스 참조로 엔티티를 쓰는 것을 못 잡는다. 위반을 넣어 확인했다 |
| import 정렬 범위 | 이번 전환에서 건든 파일만, 별도 `Style` 커밋으로 (사용자 결정) | 상관없는 파일까지 고치면 리뷰 범위가 넓어진다. 로직 커밋과 섞지 않는다 |

## 버린 대안

- **auth용 구현:** `MemberAuthAccountService`를 새로 두는 안은 메서드 두 개로 서비스를 늘리고 `*QueryService` 분리 금지에 걸려 버렸다.
- **`TokenRoleMapper` 인자:** `MemberAuthAccount`를 통째로 받는 안은 #478에서 auth가 member DTO를 직접 받지 않게 되므로 버렸다. 감수하는 것은 enum `switch`가 사라져 회원 유형이 늘어도 컴파일러가 알려 주지 않는다는 점이다.
- **헥사고날 규칙:** seller 규칙을 복사해 `member_` 규칙을 따로 두는 안은 하위 도메인마다 규칙이 늘어 버렸다.
- **import 정렬:** member 모듈 전체를 정렬하는 안은 이번 작업과 상관없는 파일까지 바꿔 버렸다.

## 건드릴 때 주의

- `MemberService`의 클래스 `@Transactional(readOnly = true)`를 지우거나 `REQUIRES_NEW`로 바꾸지 않는다. 인증용 조회가 발급 트랜잭션에 참여하지 못하면 요청 하나가 커넥션 2개를 쥐어 #425의 데드락이 다시 생긴다. `AuthLoginConcurrencyIntegrationTest`가 잡는다.
- `MemberAuthAccountUseCase.findLoginCredential`은 탈퇴 회원도 돌려준다. 틀린 비밀번호에 회원 상태와 관계없이 같은 응답을 주려면 상태를 비밀번호 확인 뒤에 판단해야 한다.
- 회원 유형이 늘면 `MemberAuthAccount`에 그 판단을 더하고 `TokenRoleMapper`를 함께 고친다. 컴파일러가 알려 주지 않는다.
- `MemberAuthAccount`, `MemberLoginCredential`은 `toString`에서 비밀번호 해시를 가린다. 필드를 더할 때 `toString`도 함께 고친다.
- 하위 도메인을 옮기면(#478) `ArchitectureTest`의 `HEXAGONAL_SUBDOMAINS`에 더한다. 모듈 설정(`archRule.failOnEmptyShould=false`)과 달리 헥사고날 규칙은 대상 클래스가 없으면 실패하게 했다. 옮긴 하위 도메인에 해당 클래스가 없는 규칙(예: 이벤트 발행이 없는 하위 도메인의 발행 규칙)이 실패하면, 규칙을 빼지 말고 그 규칙만 대상에서 제외하는 이유를 남긴다.
- `MemberJpaRepository`에서 다시 선언한 `findById`, `save`, `saveAndFlush`를 지우면 테스트가 이 타입으로 부를 때 모호한 호출 컴파일 오류가 난다.
