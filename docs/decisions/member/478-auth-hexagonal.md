---
issue: 478
module: member
summary: auth를 헥사고날 구조로 옮겨 서비스가 자기 출력 포트(저장소, 토큰 발급, 회원 조회)만 알게 하고, 다른 하위 도메인 호출은 adapter.out에서만 하도록 구조 규칙을 강화했다. auth의 서버 분리는 결정하지 않았다.
status: 확정
date: 2026-11-11
paths: [member/src/main/java/com/ok/member/auth, member/src/test/java/com/ok/member/ArchitectureTest.java]
related: [425, 475, 476, 477, 479, 505]
---

> 이 문서는 #478 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

member 모듈을 하위 도메인별로 헥사고날 구조로 옮기는 마지막 단계다(seller #476, member #477 다음). auth의 컨트롤러가 서비스 구현을 직접 주입받고, JWT 서명 기술이 application에 있고, `AuthService`가 member·seller 유스케이스를 직접 불렀다. 동작, API, DB 스키마는 그대로다.

- #425의 로그인 구조(트랜잭션 없는 `LoginFacade`의 bcrypt 비교, 발급 트랜잭션 안의 재확인)는 바꾸지 않는다.
- 옛 레이어 구조 규칙 정리(`ArchitectureTest`, `docs/CONVENTION.md`의 auth 예시)는 #479다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 회원 조회 포트가 돌려줄 타입 | auth의 `AuthAccount`(member의 `MemberAuthAccount`와 같은 필드). 로그인 조회는 기존 `LoginCredential` (사용자 결정) | 포트가 member 타입을 노출하면 application이 다른 하위 도메인에 의존해 강화한 규칙에 걸린다 |
| 회원·판매자 호출 | `MemberAuthAdapter` 하나가 `MemberCredentialReader`, `SellerApprovalReader`를 구현 (이슈 계획대로) | 둘 다 같은 member 모듈이다. 어댑터는 값만 옮기고 탈퇴·정지 판단은 `AuthService`에 남긴다 |
| 하위 도메인 사이 접근 규칙 | 기존 규칙을 "`port.in`·`dto`는 자기 하위 도메인, 다른 하위 도메인의 `adapter.out`, 모듈 최상위 패키지만 의존할 수 있다"로 교체 (사용자 결정, 최상위 예외는 리뷰 반영) | 새 조건이 기존 조건을 포함해, 둘 다 두면 같은 위반이 두 번 보고된다. 모듈 최상위는 다른 모듈에 공개하는 클래스 자리(CLAUDE.md)라 막지 않는다 |
| 저장소 구현 | `RefreshTokenJpaRepository`가 포트를 상속하고 `findForUpdateById`를 `@Lock`과 함께 다시 선언 (사용자 결정) | 레포의 헥사고날 모듈이 모두 이 방식이다. 재선언 누락은 오류 없이 잠금 없는 조회가 되므로, wallet의 #505 규칙을 member `ArchitectureTest`에도 옮겼다 |
| 토큰 발급 | `AccessTokenIssuer` 포트와 `adapter/out/token/JwtAccessTokenIssuer`. application의 `oauth2.jwt`·`nimbusds` 의존을 막는 규칙 추가 (사용자 결정) | 서명 기술을 서비스에서 떼어 낸 결과가 다시 무너지지 않게 한다 |
| `PasswordEncoder` | application에 그대로 둠 (사용자 결정) | member의 가입·관리자 서비스도 직접 쓴다. auth만 포트로 빼면 기준이 갈린다 |
| `TokenRoleMapper`, `TokenHasher`, `RefreshTokenService` | `application/service`로 옮기기만 함 (사용자 결정) | 이동 커밋은 내용을 바꾸지 않는다. 도메인 규칙으로 다시 분류하는 일은 범위 밖이다 |
| Refresh Token 형식 상수 | 바이트 수와 길이를 함께 `TokenReissueCommand`(`REFRESH_TOKEN_BYTES`, `REFRESH_TOKEN_LENGTH`)에 두고, `RefreshTokenService`가 그 바이트 수로 발급 (리뷰 반영, 사용자 확인) | 요청 DTO(`adapter.in`)가 `RefreshTokenService`를 참조해 입력 어댑터 규칙에 걸렸다. `domain`도 입력 어댑터가 쓸 수 없어 dto에 둔다. 길이는 바이트 수에서 계산해, 바이트 수를 바꿔도 요청 검증이 따라간다 |
| 회원 정보 변환 테스트 | `MemberAuthAdapterTest`를 둔다 (리뷰 반영, 사용자 확인) | boolean 세 개를 위치로 옮겨 순서가 바뀌어도 컴파일된다. 같은 타입 필드가 여럿인 변환은 단위 테스트로 확인한다(`testing-strategy.md`). 처음에는 통합 테스트로 충분하다고 봤다 |
| #425 로그인 구조 | `LoginFacade`가 `@Transactional`을 갖거나 auth의 출력 포트·어댑터에 의존하면 실패하는 구조 규칙 추가 (리뷰 반영, 사용자 확인) | 주석으로만 지키던 전제다. 깨지면 커넥션 데드락이 다시 생긴다 |
| 이벤트 발행 구현체 규칙 | `EVENT_PUBLISHING_SUBDOMAINS`(seller, member)에만 적용 (구현 중 정함) | auth는 이벤트를 발행하지 않아 검사 대상이 없다. 빈 규칙을 통과시키지 않는 #477 결정은 그대로 둔다 |
| 옛 레이어 순서 규칙 | `withOptionalLayers(true)`로 빈 레이어 허용, 삭제는 #479 (구현 중 정함) | controller·repository 패키지가 남지 않아 "레이어가 비었다"로 실패한다. 기준선은 위반 0건이라 새 키로 바꿨다 |
| 어댑터 위치·이름 | `adapter/out/<대상>`, `<대상><용도>Adapter`로 `docs/DEVELOPMENT.md`에 적음 (사용자 결정) | 다른 하위 도메인을 부르는 첫 출력 어댑터다. 다음에 위치를 다시 고민하지 않게 한다 |
| auth의 서버 분리 | 결정하지 않음 (사용자와 논의) | #475의 근거는 모듈 단위 분리다. auth만 떼면 로그인마다 member를 원격 호출하고 #425의 같은 트랜잭션 재확인이 사라진다. 얻는 것(서명 키 격리, 로그인 부하 분리)은 아직 겪는 문제가 아니다 |

## 버린 대안

- **회원 타입:** member의 `MemberAuthAccount`를 그대로 노출하는 안은 규칙 예외가 생겨 버렸다. 감수하는 것은 같은 필드의 DTO가 두 벌이라는 점이다.
- **저장소:** 포트를 상속하지 않는 어댑터 클래스가 JPA 저장소에 위임하는 안은 포트 이름이 자유롭고 `@Lock` 위치가 명시적이지만, 위임 코드가 늘고 레포 관례와 달라 버렸다.
- **비밀번호 확인:** `PasswordVerifier` 포트는 `PasswordEncoder`를 직접 쓰는 member 서비스 두 곳(`MemberSignupService`, `AdminAccountService`)까지 바꿔야 해 버렸다. `PasswordEncoder`가 이미 Spring 인터페이스이고 구현은 `PasswordEncoderConfig` 한 곳에서 정해 얻는 것이 작다. 검토 이슈는 만들지 않았고, 필요해지면 member 모듈 전체를 함께 바꾼다.
- **길이 상수:** 리터럴(43)만 두는 안은 바이트 수와 연결이 끊겨 버렸다. 입력 포트에 두는 안은 포트가 발급 형식을 알게 되어 버렸다. 감수하는 것은 발급 바이트 수가 서비스가 아니라 입력 DTO에 있다는 점이다.
- **빈 규칙:** `allowEmptyShould(true)`는 다른 하위 도메인에서 규칙이 아무것도 검사하지 않고 통과하는 것을 다시 허용해 버렸다. 옛 레이어 규칙을 지금 지우는 안은 #479 범위라 버렸다.
- **서버 분리 대비:** "어댑터만 바꾸면 분리된다"는 설명은 버렸다. 원격 호출로 나누면 비밀번호 해시를 API로 내보내지 않도록 확인 위치를 옮기고, 트랜잭션 재확인과 장애 처리를 다시 정해야 한다. 더 흔한 형태는 auth가 아이디·비밀번호 해시·상태를 소유하는 방식인데, 이건 이번 구조로 대비되지 않는다.

## 건드릴 때 주의

- `LoginFacade`에 `@Transactional`을 붙이지 않고 저장소·출력 포트를 직접 부르지 않는다(#425). `AuthService.findLoginCredential`, `issueLoginTokens`는 비밀번호 확인 없이 토큰을 내주므로 입력 포트에 넣지 않고 package-private으로 둔다. 그래서 `LoginFacade`와 `AuthService`는 같은 `application/service`에 있어야 한다.
- `MemberCredentialReader`는 호출하는 쪽 트랜잭션에 참여한다는 전제로 `issueLoginTokens`가 같은 트랜잭션에서 회원을 다시 읽는다. 구현을 원격 호출로 바꾸면 이 보장이 사라진다.
- 발급 바이트 수는 `TokenReissueCommand.REFRESH_TOKEN_BYTES`에 있다. `RefreshTokenService`에 따로 상수를 다시 두면 요청 검증 길이와 어긋난다.
- `AuthAccount`, `LoginCredential`은 `toString`에서 해시를 가린다. 필드를 더할 때 함께 고친다.
- auth가 이벤트를 발행하게 되면 `ArchitectureTest`의 `EVENT_PUBLISHING_SUBDOMAINS`에 더한다.
- 서버 분리를 다시 검토할 신호: 소셜 로그인·SSO·MFA처럼 인증이 따로 커질 때(기성 IdP와 비교), 로그인이 몰릴 때 회원 API가 함께 느려질 때(rate limit·확장 먼저), 서명 키 격리 요구가 생길 때(KMS 서명 먼저), 요청마다 토큰 폐기 확인이 필요할 때(공유 저장소 먼저), 소유 팀이나 배포 주기가 갈릴 때. member 모듈의 모듈 단위 분리가 먼저다.
