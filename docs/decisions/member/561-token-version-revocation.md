---
issue: 561
module: member
summary: 비밀번호를 바꾸면 회원 토큰 버전을 올려 기존 Access·Refresh Token을 즉시 거부하고, Access 쪽은 Redis 최소 버전과 장애 시 통과로 처리
status: 확정
date: 2026-11-18
paths: [member/src/main/java/com/ok/member/member/application/service/MemberPasswordFacade.java, member/src/main/java/com/ok/member/member/application/service/TokenVersionResyncFacade.java, member/src/main/java/com/ok/member/auth/application/service/AuthService.java, common/src/main/java/com/ok/common/config/TokenVersionValidator.java, common/src/main/java/com/ok/common/persistence/redis/RedisTokenVersionStore.java, common/src/main/java/com/ok/common/security/TokenVersionStore.java]
related: [83, 425, 437, 562, 581]
---

> 이 문서는 #561 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

Access Token 검증은 서명·만료·발급자·대상만 보고 회원 상태를 보지 않아, 비밀번호를 바꿔도 탈취된 Access Token은 최대 30분, Refresh Token은 만료까지 계속 쓰였다. 이 작업은 비밀번호 변경 API와, 그 전에 발급된 두 토큰을 즉시 거부하는 장치를 함께 만든다. 버전을 올리는 동작이 하나도 없으면 장치를 확인할 수 없어 한 PR로 묶었다(이슈 결정).

범위 밖: 관리자 정지(다음 PR에서 같은 장치로 버전을 올린다), 탈퇴, 전체 로그아웃, 재사용 탐지(#562), 분리 앱의 Redis 설정.

모듈 분리(MSA)를 앞두고 있어, 다른 서비스가 member DB나 API에 매 요청 기대지 않는 것을 판단 기준으로 삼았다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 폐기 방식 | 토큰을 지우지 않고 회원 `token_version`을 올려 비교 | member → auth 이벤트 없이 처리한다. 팀이 스프링 이벤트를 걷어 내기로 해서 "이벤트로 Refresh Token 삭제" 계획을 버렸다(이슈 결정) |
| Refresh 쪽 | 세션 행에 로그인 당시 버전을 저장하고 재발급에서 회원 버전과 비교. 회전해도 버전 유지 | 재발급은 이미 DB 트랜잭션 안이라 저장소가 더 필요 없다. 회전 때 지금 버전으로 바꾸면 폐기 전에 연 세션이 한 번의 재발급으로 살아난다 |
| Access 쪽 | 토큰에 `ver` 클레임, Redis에 최소 버전, common 검증기가 비교 | 모든 서비스가 같은 규칙으로 검사하고, 분리 뒤에도 member를 부르지 않는다 |
| 기록 시점 | 비밀번호 변경 트랜잭션이 커밋된 뒤(Facade에서 서비스가 반환한 뒤) | 커밋 전에 남기면 롤백된 변경 때문에 그 회원의 토큰이 모두 거부된다. TransactionSynchronization 대신 Facade 순서로 보장해 이벤트 없이 끝낸다 |
| 폐기된 Access 응답 | `invalid-token` (사용자 결정) | `token-expired`면 클라이언트가 재발급을 시도하지만 같은 세션의 Refresh Token도 거부되어 한 번 헛돈다 |
| 커밋 전 스냅샷을 읽은 로그인 | 발급은 막지 않고 다음 요청에서 거부 | 로그인의 회원 재조회는 잠금 없는 읽기다. 버전 비교로 다음 재발급·요청에서 끊기므로 로그인에 잠금을 더하지 않았다 |
| Redis 장애 중 | 통과(fail-open) + 검증기 안의 브레이커: 실패하면 10초 건너뛰고 요청 하나로 시험 (사용자 결정) | 이슈 결정(#437 JWKS와 같은 기준). 그대로 두면 `timeout: 1s` 때문에 모든 인증 요청이 1초씩 늦고 요청마다 스택 트레이스가 쌓인다. Resilience4j가 없어 `AtomicLong` 하나로 만들었다 |
| 검증기가 잡는 예외 | `DataAccessException`만. 앱 종료 중 연결 팩토리가 멈춰 나는 `IllegalStateException`은 저장소(`RedisTokenVersionStore`)가 `DataAccessException`으로 바꿔 올림 | 그 밖의 예외는 버그라 500으로 크게 드러낸다. 모든 `RuntimeException`을 통과시키면 버그 동안 폐기 검사가 조용히 꺼지는데, 지금은 이를 알릴 알림 규칙(Alertmanager)이 없다. 알림이 생기면 "넓게 잡고 지표를 따로 세기"를 다시 검토한다 |
| 저장소 인터페이스 | `CacheStore`를 쓰지 않고 common에 `TokenVersionStore`를 새로 둠 | `CacheStore.get`은 장애를 빈 값으로 바꿔 "기록 없음"과 구분할 수 없고, 그 문서가 실패를 알려야 하는 동작은 넣지 않는다고 정했다. 나중에 Kafka 구현으로 바꿀 자리도 된다 |
| Redis 장애 뒤 | DB에서 최근 31분 변경분을 30초마다 다시 씀 (사용자 결정) | DB가 원본이고 Redis는 사본이다. 장애 중 쓰기 실패, AOF `everysec` 유실, 클러스터 장애 조치, 볼륨 손실을 같은 방법으로 사람 손 없이 메운다 |
| 쓰기 규칙 | 지금 값보다 높을 때만 씀(Lua), 같거나 낮으면 무시 | 처음에는 "버전이 거의 동시에 두 번 오르면 낮은 값이 남아도 둘 다 탈취 토큰보다 높아 괜찮다"고 받아들였다. 코드 리뷰에서 두 변경 **사이에** 발급된 토큰이 빠진다는 것이 드러나 막았다. 늦게 도착한 다시 채우기도 같은 규칙으로 막힌다 |
| 검사 끄기 | `jwt.token-version-check.enabled`, 기본 켜짐 | 꺼진 채 배포되지 않게 한다. "저장소 빈이 없으면 기동 실패"는 Redis 빈이 모든 앱에 항상 생겨 의미가 없어 넣지 않았다 |
| 키 | `member:token-version:v1:{memberId}`, 값은 숫자 문자열 | 분리 뒤 서비스 간 계약이 되어 Kafka 토픽처럼 버전을 붙였다. JSON이 아니어야 다른 서비스가 직렬화 설정과 상관없이 읽는다 |

## 버린 대안

- **장애 중 DB 대체 조회:** Redis 장애 때 member DB의 `token_version`을 대신 읽으면 모놀리스에서는 싸고 정확하다. 서비스를 나누면 다른 서비스는 member API를 HTTP로 불러야 해 장애 동안 모든 인증 요청이 member로 몰리고, Redis 장애가 로그인까지 멈추는 장애로 번진다. 배포 형태마다 보안 동작이 달라지는 것도 피했다(사용자 결정). 감수하는 것은 장애 동안 폐기된 Access Token이 최대 30분 통과하는 것이다. Refresh Token은 계속 막힌다.
- **Kafka 브로드캐스트 + 인스턴스 메모리:** member outbox로 변경을 방송하고 각 인스턴스가 메모리에 들면 요청 경로에 네트워크가 없고 장애 중에도 받은 폐기는 유지된다. relay 주기(30초)만큼 늦고, 한 행 실패에 다른 메시지와 함께 막히며, 새 인스턴스는 시작할 때 31분치를 다시 읽어야 하고(인스턴스별 컨슈머), 이슈의 "이벤트 없이" 결정을 다시 합의해야 한다. 옮길 조건: Redis 조회 시간이 인증 지연에서 눈에 띄거나(`token.version.lookup` 지표), 버전을 올리는 동작이 늘어 장애 중 폐기 유지가 중요해질 때. `TokenVersionStore` 구현만 바꾸면 된다.
- **요청마다 DB만 조회:** 모놀리스에서는 단순하지만 평소에도 모든 인증 요청이 DB를 한 번 더 치고, 분리 뒤에는 위 대체 조회와 같은 문제가 상시 생긴다.
- **Access Token 수명 단축만:** 코드가 거의 없지만 즉시 거부가 아니라 완료 조건을 지킬 수 없고 재발급이 늘어 member DB 부하가 커진다. 장애 중 노출 시간을 줄이는 보조 수단으로는 별도로 검토할 만하다.
- **outbox로 Redis 쓰기:** "커밋 뒤 put 전에 앱이 죽는" 구멍까지 막지만 relay만큼 늦고 구조가 커진다. 그 구멍은 다시 채우기가 30초 안에 메운다.
- **장애 후 시험 없이 10초 뒤 전부 재조회:** 그 1초 동안 들어온 요청이 모두 타임아웃을 기다린다. 시험 요청 하나만 보낸다.

## 건드릴 때 주의

- 최소 버전은 **내려가면 안 된다.** `RedisTokenVersionStore`의 Lua 스크립트를 일반 `SET`으로 바꾸면 연속 변경이나 다시 채우기가 값을 낮춰 폐기된 토큰이 다시 통과한다.
- Refresh 세션의 `token_version`은 회전에서 바꾸지 않는다.
- Redis 기록은 반드시 커밋 뒤에 한다. `MemberPasswordFacade`에 트랜잭션을 붙이거나 서비스 안에서 기록하면 롤백된 변경이 남는다. 기록 실패는 어떤 예외든 잡는다. 놓치면 이미 바뀐 비밀번호에 500이 나간다.
- `MemberService.findPasswordHash`와 `changePassword`는 이어 부르면 현재 비밀번호 확인 없이 비밀번호를 바꿀 수 있어 입력 포트에 넣지 않았다(LoginFacade와 같은 이유, #425).
- 토큰 버전을 올리는 동작을 더할 때(정지, 전체 로그아웃)는 `Member`에서 버전과 `token_version_changed_at`을 함께 올리고, 커밋 뒤 같은 기록 경로를 탄다. 변경 시각을 빼면 다시 채우기가 그 회원을 보지 못한다.
- 검증기는 다른 검증을 모두 통과한 토큰에만 Redis를 부른다(`JwtConfig`의 `DependentValidator`). 목록에 그냥 넣으면 만료된 토큰마다 Redis를 부른다.
- 분리 앱은 `common-redis.yaml`을 가져와 같은 Redis를 봐야 한다. 접속하지 못해도 앱은 뜨고 경고 한 줄만 남은 채 Access 폐기 검사가 빠진다.
- 통합 테스트는 다시 채우기 스케줄러를 끈다(`module-test.yml`, `MemberModuleTest`). 켜면 테스트가 지운 Redis 키를 되살린다.
- (미확인) 실제 Redis 컨테이너를 멈췄을 때의 동작은 단위 테스트(가짜 저장소)와 접속할 수 없는 포트로만 확인했다. 테스트 Redis 컨테이너는 JVM 전체가 함께 써 멈출 수 없었다.
