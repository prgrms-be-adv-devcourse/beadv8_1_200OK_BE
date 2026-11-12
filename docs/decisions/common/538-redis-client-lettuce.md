---
issue: 538
module: common
summary: Redis 클라이언트는 Lettuce로 고르고, 분산락이 늘면 Redisson을 캐시와 구분해 따로 쓰는 방향으로 설계한다
status: 확정
date: 2026-11-13
paths: [common/src/main/java/com/ok/common/persistence/redis, common/src/test/java/com/ok/common/config/RedisConnectionTest.java, monolith/src/test/java/com/ok/ArchitectureTest.java]
related: [319, 324]
---

> 이 문서는 #538 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

- Redis에 접근하는 Java 클라이언트를 정해야 했다. Java에서 많이 쓰는 후보는 Jedis, Lettuce, Redisson이다.
- 지금 Redis를 쓰는 방식은 대부분 비동기 기반이고, 분산락을 고려하지 않아도 되는 상황이라고 판단했다(사용자 판단). 사용처와 운영 정책은 #324에서 정한다.
- 범위 밖: 분산락 구현과 Redisson 도입 시점.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 클라이언트 | Lettuce (Spring Data Redis 스타터 기본) | 비동기 중심인 현재 사용 방식에 맞고, 분산락이 필요 없어 별도 라이브러리를 더할 이유가 없다 (사용자 결정). 코드에서 다른 클라이언트를 지정한 곳이 없고 `RedisConnectionTest`가 Lettuce인지 확인한다 |
| 접근 구조 | Redis 접근 코드를 `common`의 `persistence/redis` 한 곳에 모으고 `CacheStore` 인터페이스 뒤에 둔다 | 클라이언트나 저장소가 바뀌어도 구현체만 바뀌게 하려는 것이다 (사용자 결정). 패키지 밖에서 Redis 클라이언트를 쓰면 `ArchitectureTest`가 실패한다 |
| 분산락이 늘어날 때 | Redisson을 쓰되 캐시용 Redis와 분산락용 Redis를 구분해서 쓴다 | 아래 버린 대안과 감수하는 것 때문이다 (사용자 결정) |

## 버린 대안

- **Jedis:** 동기(블로킹) 방식이라 비동기 중심인 현재 사용 방식과 맞지 않는다고 판단했다 (사용자 판단).
- **지금 Redisson 도입:** 분산락이 필요한 곳이 없는데 의존성과 연결만 늘어서 버렸다. 감수하는 것은 락이 필요해질 때 그 작업을 그때 해야 한다는 점이다.
- **Redisson 스타터로 연결 팩토리까지 교체:** `redisson-spring-boot-starter`를 추가하면 `RedisConnectionFactory`가 Redisson 구현으로 등록된다(사용자가 별도로 확인했다). 그러면 캐시 쪽 동작이 같이 바뀌므로, 캐시와 락의 Redis 접근을 하나로 합치지 않고 구분한다.
- **Redisson 도입 때 감수하는 것:** Lettuce와 Redisson이 각자 연결을 맺어 커넥션 수가 약 2배가 되고, 값 직렬화 방식이 둘 사이에서 다를 수 있다(사용자가 별도로 확인했다). 도입할 때 이 프로젝트에서 실측으로 다시 확인한다.

## 건드릴 때 주의

- Redisson을 도입하기 전에 `redisson-spring-boot-starter`가 `RedisConnectionFactory`를 바꾸는지 먼저 확인한다. 바꾼다면 캐시용 Lettuce 설정(`common-redis.yaml`)이 그대로 적용되는지, `CacheStore`의 동작이 달라지지 않는지 본다.
- 락은 `CacheStore`에 넣지 않고 별도 인터페이스와 구현으로 둔다. 캐시 값(JSON, TTL 필수)과 락은 직렬화·만료 정책이 달라질 수 있다.
- `ArchitectureTest`(monolith)의 규칙은 `org.springframework.data.redis..`와 `io.lettuce..`만 `persistence/redis` 밖에서 막는다. Redisson 패키지(`org.redisson..`)는 포함돼 있지 않으므로 도입할 때 규칙에 더한다.
- 클라이언트를 바꾸면 `RedisConnectionTest`의 Lettuce 확인 테스트도 함께 바꾼다.
