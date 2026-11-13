---
issue: 83
module: member
summary: 만료 Refresh Token이 쌓이던 문제를 재발급 시 같은 행을 갱신(행 = 세션)하고 만료 행을 매일 묶음 삭제하는 방식으로 해결. 4가지 방식을 부하 테스트로 비교해 골랐다
status: 확정
date: 2026-11-14
paths: [member/src/main/java/com/ok/member/auth/application/service/RefreshTokenService.java, member/src/main/java/com/ok/member/auth/application/service/RefreshTokenCleanupService.java, member/src/main/java/com/ok/member/auth/adapter/out/persistence/RefreshTokenJpaRepository.java, member/src/main/java/com/ok/member/auth/adapter/in/scheduler/RefreshTokenCleanupScheduler.java, member/src/main/java/com/ok/member/auth/domain/RefreshToken.java]
related: [49, 478]
---

> 이 문서는 #83 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

재발급할 때마다 쓴 토큰 행을 지우고 새 행을 넣었지만, 만료된 행을 지우는 코드는 없었다. 그래서 로그아웃하지 않고 떠난 세션의 행이 `refresh_token`에 계속 쌓였다.

범위 밖으로 둔 것:
- 재사용 탐지(쓴 토큰이 다시 오면 세션 폐기)
- token_version을 이용한 즉시 반영(비밀번호 변경, 정지, 탈퇴)
- 이슈 본문의 "회원당 Refresh Token 상한 정책"

앞의 두 가지는 앞으로 할 작업으로 정해져 있다. 그래서 이번 구조가 그 작업을 막지 않는지를 함께 따졌다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 정리 방식 | 행 = 로그인 세션. 재발급하면 같은 행의 해시, 만료 일시, `rotated_at`을 UPDATE하고, 만료 행은 cron으로 정리 | 4가지 방식을 같은 조건(만료 2분, 회원 400명, 8분 부하 + 3분 관찰, 로컬 Docker)에서 비교했다. 이 방식은 재발급 p95가 기존과 비슷했다(32.2 → 37.1ms). 최대 행 수는 가장 적었고(기존 10,071 → 3,512), 관찰이 끝났을 때 남은 행은 0이었다. 행 수가 재발급 횟수가 아니라 유효 기간 안의 로그인 수로 제한된다 (사용자 결정) |
| 같은 토큰 동시 재발급 | 해시로 id만 잠금 없이 찾고 → 기본 키 행을 `PESSIMISTIC_WRITE`로 잠근 뒤 → 해시를 다시 비교 | 행을 지우지 않고 해시만 바꾸므로, 잠금 뒤에 해시를 다시 비교하지 않으면 같은 토큰이 두 번 쓰인다. 해시로 바로 잠그면 없는 해시일 때 인덱스 구간이 잠겨 다른 토큰 저장을 막는다 |
| 정리 DELETE | `WHERE expires_at < :now ORDER BY expires_at, id LIMIT :batchSize`를 짧은 트랜잭션으로 반복 | `idx_refresh_token_expires_at`(#49)를 범위로 읽어 만료된 구간만 잠근다. 한 번에 다 지우면 긴 트랜잭션이 잠금과 undo 로그를 오래 쥔다 |
| 정리 기준 시각 | 시작할 때 한 번 정한 `now` | 정리하는 동안 새로 만료되는 행을 쫓느라 끝나지 않는 일을 막는다. 같은 기준으로 지우므로 서버 여러 대에서 겹쳐 돌아도 결과가 같아, 분산 락(ShedLock)을 두지 않았다 |
| 정리 주기·묶음 크기 | 매일 04:00(Asia/Seoul), 1,000개. `auth.refresh-token.cleanup.cron`, `batch-size`로 바꿈 | 행 갱신 방식에서는 정리할 행이 버려진 세션뿐이라 하루 한 번이면 충분하다. 운영에서 늘리거나 끌 수 있게 설정으로 뺐다(cron `-`이면 꺼짐) |
| 쓴 토큰 해시 | `previous_token_hash`를 두지 않음. 쓴 토큰은 없던 토큰과 같은 401 | 재사용 탐지는 별도 이슈에서 토큰 형식 `<family id>.<secret>`으로 설계한다. family id(PK)로 행을 찾으면 쓴 해시를 따로 둘 필요가 없다 (사용자 결정: "83은 그대로 마무리하고 재사용 탐지 이슈에서 변경") |
| `rotated_at` 컬럼 | 추가(NULL 허용) | 재사용 탐지에서 네트워크 재시도를 위한 유예 시간 판단에 쓸 예정이다. 로그인 직후 행과 재발급된 행을 구분한다 |

## 버린 대안

- **스케줄 삭제(쓴 행에 `used_at` 표시 후 배치 삭제):** 쓴 행까지 남겨 최대 42,862행으로 기존보다 4배 많았다. 재사용 탐지에는 쓴 해시가 남는 점이 유리하지만, family id 방식이면 그 장점이 필요 없다.
- **Redis(Hash + TTL, 회원별 ZSET, Lua):** 가장 빨랐다(p95 15.6ms, 처리량 737 req/s). 버린 이유는 세 가지다.
  - 영속성을 꺼 둔 Redis라 재시작하면 모든 세션이 사라진다.
  - 회원과 세션 데이터가 DB와 Redis로 나뉘어, 탈퇴·정지와 함께 처리하기 어렵다.
  - Lua 스크립트로 원자성을 따로 맞춰야 한다.
- **발급 시 그 회원의 만료 행 삭제(lazy):** 다시 로그인하지 않은 회원의 행은 영원히 남았다(관찰 종료 시 54,186행). 발급 경로의 DELETE가 겹쳐 p99가 406ms로 가장 나빴다.
- **`INSERT ... ON DUPLICATE KEY UPDATE`:** 세션을 구분할 자연 키가 없어 쓰지 않았다. 행 갱신은 잠근 엔티티의 변경 감지로 한다.
- **로그인 시 회원 행 상한(오래된 세션 삭제):** 이번에는 넣지 않았다. 행 갱신으로 행 수가 로그인 수로 제한되어 급하지 않다. 다중 기기 정책과 함께 정해야 한다.

## 건드릴 때 주의

- `RefreshTokenRepository.findIdByTokenHash`가 엔티티가 아닌 id만 돌려주는 것은 의도다. 엔티티를 먼저 읽으면 영속성 컨텍스트에 남는다. 그러면 뒤의 잠금 조회가 다른 요청이 바꾼 해시를 다시 읽지 않아, 같은 토큰이 두 번 재발급된다. `AuthReissueIntegrationTest`의 동시 재발급 테스트가 이를 지킨다.
- 잠금 뒤 해시 재비교(`filter(locked -> locked.getTokenHash().equals(tokenHash))`)를 빼면 안 된다.
- 정리 DELETE의 `ORDER BY expires_at, id`를 `ORDER BY id`로 바꾸면 PK를 처음부터 훑게 된다. 그러면 만료되지 않은 행까지 잠근다. JPQL은 DELETE에 ORDER BY·LIMIT을 쓸 수 없어 네이티브 쿼리다.
- 정리 서비스(`RefreshTokenCleanupService`)에는 트랜잭션을 걸지 않는다. 묶음마다 `RefreshTokenService.deleteExpiredBatch`가 짧은 트랜잭션을 연다. 바깥에 트랜잭션을 걸면 묶음으로 나눈 의미가 사라진다.
- 부하 테스트 수치는 로컬 Docker 한 대에서 잰 상대 비교다. 절대 성능으로 읽지 않는다.
