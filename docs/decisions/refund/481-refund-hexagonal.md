---
issue: 481
module: refund
summary: 환불 모듈을 헥사고날로 바꾸고 도메인을 JPA에서 분리해, 유스케이스마다 서비스 하나·트랜잭션은 DB 구간만·저장은 명시적으로 한다
status: 확정
date: 2026-11-07
paths: [refund/CLAUDE.md, refund/src/main/java/com/ok/refund/application/service/DecideRefundService.java, refund/src/main/java/com/ok/refund/application/port/out/RefundPersistencePort.java, refund/src/main/java/com/ok/refund/adapter/out/persistence/RefundPersistenceAdapter.java, refund/src/main/java/com/ok/refund/domain/Refund.java, refund/src/test/java/com/ok/refund/ArchitectureTest.java]
related: [323, 412, 413, 414]
---

> 이 문서는 #481 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

환불의 남은 작업은 대부분 바깥 연결이다. #413에서 지갑과 Kafka로 처음 메시지를 주고받고, #414에서 상품 재고 HTTP 호출이 생기며, 이후 모듈별 서버 분리가 예정돼 있다.
기존 구조(`controller`/`service`/`repository`)에는 Kafka 입구·출구를 둘 자리가 정해져 있지 않았고, 주문 호출은 인터페이스 뒤에 숨겼지만 저장은 서비스가 Spring Data 리포지토리를 직접 써서 연결 방식이 한 모듈 안에서 제각각이었다.
또 `Refund`가 JPA 엔티티라 "집계는 상태를 바꾸기 전에", "잠금 조회는 첫 조회로" 같은 JPA 동작 때문에 생긴 순서 규칙이 주석과 테스트로만 지켜지고 있었다.
범위: 패키지 구조, 도메인 분리, 트랜잭션 방식, 구조 테스트, 환불 전용 규칙 문서. 기능·API·DB 스키마는 바꾸지 않는다.
범위 밖: Kafka 어댑터(#413), 상품 호출(#414).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 구조 | 정석 헥사고날: `domain` / `application`(port.in, port.out, service) / `adapter`(in, out) | 바깥 연결이 바뀌어도 어댑터만 고치고 업무 규칙은 그대로 두려고. 다른 모듈 구조를 근거로 삼지 않고 환불의 사정으로 정함 (사용자 결정) |
| 규칙 문서 | 환불 전용 `refund/CLAUDE.md`를 두고, 그 범위에서 공통 레이어 규칙보다 우선 | 공통 규칙에 억지로 맞추면 헥사고날이 안 된다. 모듈별 서버 분리 때 규칙을 모듈과 함께 가져가려고 (사용자 결정) |
| 유스케이스 구현 | 입력 포트 하나 = 서비스 하나. Facade(조율)와 Service(DB)로 나누지 않음 | 한 유스케이스의 흐름을 한 클래스에서 읽게 (사용자 결정) |
| 트랜잭션 | `@Transactional` 대신 서비스 안 `TransactionTemplate`으로 DB 작업 구간만 묶음 | 한 클래스로 합치면 `@Transactional`은 메서드 전체를 묶어 주문 HTTP 호출까지 트랜잭션에 들어간다. 같은 클래스 안 호출은 프록시를 거치지 않아 `@Transactional`이 무시되기도 한다 |
| 승인 흐름 | 조회 구간 → 주문 호출(구간 밖) → 승인 구간(READ COMMITTED, 묶음 잠금) | 결정은 승인 구간에서 잠그고 다시 읽은 값으로만 한다. 그 사이 철회 등으로 바뀌면 409로 멈추고 아무것도 저장하지 않는다 |
| 도메인 분리 | `Refund`(순수 자바) + `RefundJpaEntity`(칸 매핑) + `RefundMapper` | 업무 로직이 변경 감지(값을 바꾸면 말없이 저장됨)·자동 flush(조회 직전에 바뀐 값을 DB에 먼저 보냄)에 기대지 않게, 저장 시점을 코드에 드러내려고 |
| 저장 | `saveNew`(새 행, ID·생성 시각이 채워진 도메인을 돌려줌) / `update`(같은 트랜잭션에서 잠금 조회로 불러온 행에만, 아니면 예외) | 새 행은 ID 생성과 진행 중 중복(UNIQUE) 감지가 필요하고, 수정은 잠근 행에만 해야 덮어쓰기가 생기지 않는다 |
| 서비스들이 함께 쓰는 코드 | 업무 규칙(취소로 세는 상태)은 `domain` 정책, DB에서 꺼내는 방법(쪽 크기·정렬)은 영속 어댑터. 서비스 패키지에 도우미를 두지 않음 | 처음엔 서비스 패키지에 정적 도우미로 두었으나, 규칙·흐름·기술이 섞여 정석이 아니라는 지적으로 옮김 (사용자 결정) |
| 페이징 타입 | 서비스·포트에서 Spring Data `Page`만 허용 | 이슈의 "Spring Data 금지"는 DB 도구(`JpaRepository` 등)를 막으려던 것이고, `Page`는 결과를 담는 그릇이다 (사용자 결정) |
| 구조 검사 | `ArchitectureTest`로 의존 방향·이름·위치·`@Transactional` 금지·서비스끼리 호출 금지 등을 빌드 때 검사 | 규칙을 사람 기억이 아니라 빌드로 지키려고. 위반 코드를 넣어 실패하는 것을 확인함 |

## 버린 대안

- **구조:** 기존 세 칸 구조를 유지하고 Kafka만 추가하는 것은, 입구·출구 자리와 메시지 검사 위치가 정해지지 않아 버렸다. 감수하는 것은 파일 수 증가와, 팀의 다른 모듈과 구조가 다르다는 점.
- **도메인:** JPA 엔티티를 도메인으로 그대로 쓰는 것은 파일이 적지만, 순서 규칙이 계속 주석에 의존해 버렸다. 감수하는 것은 칸이 늘 때 도메인·JPA 엔티티·변환 세 곳 수정, `update` 누락 시 저장 안 됨(상태를 바꾸는 경로마다 저장 값을 테스트로 확인).
- **저장 메서드:** `save` 하나(merge)는 잠그지 않은 행도 덮어쓰고 새 행의 중복 감지와 섞여 버렸다.
- **트랜잭션:** Facade와 Service로 클래스를 나눠 `@Transactional` 경계를 만드는 방식은 흐름이 두 파일로 갈라져 버렸다.
- **페이징:** 환불 전용 페이지 타입으로 감싸는 것은 원칙에는 더 맞지만, 결과 그릇일 뿐이라 바꿀 곳만 늘어 버렸다.

## 건드릴 때 주의

- 상태를 바꾼 뒤 `refundPersistencePort.update`를 부르지 않으면 응답은 성공인데 DB는 그대로다. 새 경로를 만들면 저장 값 테스트를 함께 붙인다.
- `update`는 같은 트랜잭션에서 `findByIdForUpdate`로 불러온 행에만 쓸 수 있다. 같은 환불을 한 트랜잭션에서 두 번 잠금 조회하면 도메인 사본이 둘 생기고, 옛 사본을 나중에 `update`하면 앞의 변경이 되돌아간다(실행으로 확인). 지금 경로는 행마다 한 번만 잠근다.
- 다른 모듈 호출(주문·상품 HTTP, 지갑 Kafka)은 `TransactionTemplate` 구간 안에 넣지 않는다. 승인 뒤 단계 호출은 `RefundStepRunner`로 부르고, 그 틀은 바깥 트랜잭션이 있으면 예외를 던진다.
- `delivery` 등 다른 모듈의 구조를 근거로 환불 규칙을 바꾸지 않는다. 기준은 `refund/CLAUDE.md`다.
