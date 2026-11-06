# 정산 패키지 구조 (헥사고날)

정산(`payout`) 모듈의 클래스를 만들거나 옮길 때 따르는 규칙이다. `structure.md`의 레이어 패키지(`controller`, `service`, `repository`, `event`)와 발행 인터페이스 위치 규칙 대신 이 파일을 따른다. 역할별 접미사와 모듈 간 규칙은 `structure.md` 그대로다.
결정과 이유: `docs/decisions/payout/441-payout-hexagonal.md`, `469-payout-arch-test-gaps.md`.

## 패키지

| 패키지 | 두는 것 |
|---|---|
| `domain` | 엔티티(JPA 어노테이션 그대로), 정책(`PayoutPolicy`) |
| `application/port/in` | 입력 포트(`*UseCase`)와 그 값(`*Command`, `*Query`, `*Result`) |
| `application/port/out` | 출력 포트(`*Repository`, `*QueryRepository`, 발행 인터페이스)와 그 값(`*Row`, 발행 값 `SellerPayoutCreated`) |
| `application/service` | 유스케이스 구현(`*Service`), 검증기, 조회 결과 변환(`PayoutQueryResultMapper`) |
| `adapter/in/web` | 컨트롤러, `*Spec`, 요청·응답 DTO(`dto/`) |
| `adapter/in/event` | Spring·Kafka 리스너, Kafka 메시지 DTO(`dto/`) |
| `adapter/in/batch` | 정산 Job 설정, 시작기(`PayoutJobRunner`), 스케줄러 |
| `adapter/out/persistence` | Spring Data JPA 저장소(`*JpaRepository`), Querydsl 조회 |
| `adapter/out/event` | 이벤트 발행 구현체 |
| `config`, `exception` | 모듈 전용 설정, `PayoutErrorCode` |

- 위 표에 없는 하위 패키지를 만들지 않는다. 꼭 필요하면 `ArchitectureTest`의 `정산_클래스는_정해진_패키지에만_있다` 허용 목록과 관련 규칙, `ArchitectureRuleEdgeTest`를 함께 고친다.
- `com.ok.payout` 밖(예: `com.ok.relay`)에 정산 모듈 클래스를 두지 않는다. `PayoutApplication`만 예외다.
- 값은 그 값이 지나가는 포트 옆에 둔다. delivery처럼 `application/dto`에 모으지 않는다.

**이유**: 값을 한곳에 모으면 dto ↔ port.out 순환이 생긴다. 패키지 규칙은 패키지 이름으로 검사하므로, 목록에 없는 하위 패키지를 거치면 규칙을 피할 수 있다(#469에서 실행으로 확인).

## 의존 방향

- `adapter.in` → `application.port.in` → `application.service` → `application.port.out` ← `adapter.out`. 서비스는 JPA 저장소, JDBC, Kafka, Spring Batch를 모른다.
- `port.in`은 `port.out`을 모른다. 조회 Row → Result 변환은 서비스(`PayoutQueryResultMapper`)가 한다.
- 컨트롤러와 리스너는 `port.in`만 안다. 서비스 구현체, `port.out`, `domain`을 직접 쓰지 않는다.
- 배치를 뺀 입구(web, event)는 배치 중 `PayoutJobRunner`만 쓴다. 배치는 `domain` 중 `PayoutPolicy`만 쓴다.
- `com.ok` 중 `payout`과 `common`만 안다.
- `common.event`의 이벤트 클래스는 `adapter/in/event`, `adapter/out/event`에서만 쓴다.
- 발행 도구(`ApplicationEventPublisher`, `KafkaOperations`)는 `adapter/out/event`에서만 쓴다.

**이유**: 입구와 외부 연결이 서비스를 거쳐서만 만나야 연동 방식(Spring 이벤트 → Kafka, 모듈 분리)을 바꿔도 서비스가 바뀌지 않는다. 이 규칙은 정산 `ArchitectureTest`가 검사한다.

## 주의

- `adapter/in/web`은 경로가 `controller`가 아니라 `api.md`가 자동으로 로드되지 않는다. 직접 읽고 따른다.
- 저장소 포트에 메서드를 더하면 쿼리 애노테이션(`@Query`, `@Modifying`, `@EntityGraph`)은 `adapter/out/persistence`의 `*JpaRepository`에 `@Override`로 다시 선언한다. 포트에는 Spring Data를 두지 않는다.
