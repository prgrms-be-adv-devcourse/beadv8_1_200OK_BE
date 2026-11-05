---
issue: 425
module: member
summary: 로그인을 트랜잭션 없는 LoginFacade와 AuthService의 짧은 트랜잭션으로 나눠 요청당 커넥션을 1개만 쥐게 해 동시 로그인 커넥션 풀 데드락을 없앰
status: 확정
date: 2026-11-05
paths: [member/src/main/java/com/ok/member/auth/service/LoginFacade.java, member/src/main/java/com/ok/member/auth/service/AuthService.java, member/src/test/java/com/ok/member/auth/AuthLoginConcurrencyIntegrationTest.java]
related: [374]
---

> 이 문서는 #425 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

로그인 성공 요청이 커넥션 풀 크기(10) 이상 동시에 오면 모든 요청이 connectionTimeout(30초)까지 멈추고 일부는 500으로 끝났다. 9개까지는 정상이고 10개에서 갑자기 멈췄다.

원인: `AuthService.login`은 bcrypt 비교 동안 커넥션을 잡지 않으려고 `@Transactional(propagation = NOT_SUPPORTED)`였다. 그런데 `NOT_SUPPORTED`는 트랜잭션만 없을 뿐 트랜잭션 동기화 범위는 열어 둔다. 그래서 첫 조회(`findByLoginId`)가 연 `EntityManager`와 그 커넥션이 메서드가 끝날 때까지 남았다. 그 상태에서 판매자 승인 조회와 Refresh Token 저장이 새 트랜잭션으로 두 번째 커넥션을 요구해, 요청 하나가 커넥션 2개를 동시에 쥐었다. 풀이 첫 커넥션으로 가득 차면 모두가 서로를 기다린다.

이 원인은 동시성 통합 테스트로 확인했다. 수정 전 코드에서 Hikari가 `total=10, active=10, idle=0, waiting=10`으로 타임아웃을 냈고, 수정 후 통과했다.

범위 밖: 풀 크기, Tomcat 요청 스레드 수, `connectionTimeout` 값 조정. 컨벤션 문서 수정과 ArchUnit 규칙 추가.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 수정 방식 | 로그인 흐름을 트랜잭션 없는 `LoginFacade`로 옮기고, DB 작업은 `AuthService`의 짧은 트랜잭션으로 호출 (사용자 결정) | 영향이 로그인에만 머물고, 커넥션을 잡는 범위가 코드 구조에 드러난다. 오래 걸리는 작업은 트랜잭션 밖 Facade에서 한다는 기존 Facade 컨벤션과 같은 모양이다 |
| 회원 조회 | `AuthService.findMemberByLoginId`(읽기 트랜잭션)가 `Optional<Member>` 엔티티를 그대로 반환 (사용자 결정) | 조회가 끝나면 커넥션을 바로 반환한다. `Member`는 연관관계가 없어 트랜잭션 밖에서 읽어도 지연 로딩 문제가 없다. 별도 DTO는 만들지 않았다 |
| 토큰 발급 | `AuthService.issueTokens`(`@Transactional`) 하나에서 판매자 승인 조회와 Refresh Token 저장을 함께 처리 (사용자 결정) | 커넥션을 한 번만 받고, 로그인과 재발급이 같은 방식으로 토큰을 만든다. `reissue`는 같은 클래스의 `@Transactional` 메서드를 부르지 않도록 private `createTokens`를 공유한다 |
| 실패 경로 | bcrypt 비교와 탈퇴·정지 확인을 Facade에서 트랜잭션 밖으로. 없는 아이디도 더미 해시와 비교하는 동작은 유지 (사용자 결정) | 실패 경로도 bcrypt 동안 커넥션을 쥐고 있었다. 응답 시간으로 가입 여부를 짐작하기 어렵게 하는 기존 대응은 구조를 바꿔도 깨지면 안 된다 |
| 재발 방지 | 동시성 통합 테스트만 둔다 (사용자 결정) | 운영 코드에서 `NOT_SUPPORTED`를 쓰던 곳이 이 한 곳뿐이었다. 대신 `LoginFacade` Javadoc에 `NOT_SUPPORTED`를 쓰면 안 되는 이유와 #425를 적었다 |
| 테스트 방식 | 공유 테스트 컨텍스트의 실제 풀 크기를 읽어 그 2배를 동시에 로그인 | 처음에는 테스트에서만 풀을 2로 줄이려 했으나, `testing-strategy.md`가 통합 테스트에서 설정을 바꿔 컨텍스트를 새로 띄우는 것을 금지한다. 로그인 성공 요청만 보낸다(실패 경로 검증은 별도 장치가 필요해 비용 대비 효과가 작다고 봤다) |
| 설정값 | 풀 크기·스레드 수·`connectionTimeout`은 이번에 바꾸지 않음 (사용자 결정) | 수정 후에는 요청당 커넥션이 1개라 풀 크기를 넘는 요청은 대기열에서 차례로 처리된다. 남은 것은 튜닝 문제라 실제 부하 지표를 보고 따로 정한다. 버그 수정과 설정 변경이 섞이면 문제가 생겼을 때 원인을 가리기 어렵다 |

## 버린 대안

- **Hibernate 커넥션 해제 모드:** `hibernate.connection.handling_mode`를 문장마다 커넥션을 돌려주는 모드로 바꾸는 방법은 코드 수정이 거의 없지만, 모놀리스 전 모듈의 커넥션 동작이 바뀌고 "트랜잭션 밖에서는 커넥션을 쥐지 않는다"는 의도가 설정 뒤에 숨는다.
- **`AuthService`의 클래스 단위 `readOnly`를 떼고 `login`을 트랜잭션 없이 두기:** "서비스 클래스에 `readOnly`를 선언한다" 컨벤션을 어기고, 같은 클래스 안에서 트랜잭션 메서드를 부를 수 없어 결국 빈을 나눠야 한다.
- **컨벤션 문서·ArchUnit으로 `NOT_SUPPORTED` 금지:** 다른 모듈의 재발을 막을 수 있지만 사용처가 한 곳뿐이라 이번에는 두지 않았다. 감수하는 것은 다른 곳에서 같은 실수가 다시 나올 수 있다는 점이다.
- **테스트 전용 작은 풀(별도 컨텍스트):** 테스트 컨텍스트 공유 규칙과 충돌해 버렸다. 감수하는 것은 공유 컨텍스트 풀 크기에 따라 동시 요청 수가 달라진다는 점이다.

## 건드릴 때 주의

- `LoginFacade`에 `@Transactional`이나 `@Transactional(propagation = NOT_SUPPORTED)`를 붙이지 않는다. 앞의 것은 bcrypt 동안 커넥션을 잡고, 뒤의 것은 이번 데드락을 다시 만든다.
- 로그인 경로에서 커넥션을 쥔 채로 다른 트랜잭션을 여는 코드를 넣지 않는다. 요청 하나가 동시에 쥐는 커넥션이 2개가 되는 순간 같은 데드락이 생긴다. `AuthLoginConcurrencyIntegrationTest`가 이를 잡는다.
- `findMemberByLoginId`가 엔티티를 트랜잭션 밖으로 넘기는 것은 `Member`에 연관관계가 없다는 전제다. 연관관계를 추가하면 Facade에서 지연 로딩 예외가 날 수 있다.
- 더미 해시는 회원가입과 같은 `PasswordEncoder`로 만들어야 비교 비용이 같아진다. 인코더 알고리즘이나 강도를 바꾸면 함께 점검한다.
- (미확인) 수정 후 동시 로그인의 다음 병목은 커넥션이 아니라 bcrypt의 CPU 사용량으로 보인다. 풀 크기를 조정할 때 함께 본다.
