# 환불(refund) 모듈 규칙

환불 모듈은 포트·어댑터(헥사고날) 구조다. 이 파일은 `refund/` 아래 코드에서
`.claude/rules/structure.md`, `service.md`, `api.md`의 **패키지·레이어·트랜잭션 위치·이름 규칙보다 우선한다.**
그 밖의 레포 규칙(한국어 주석, 커밋·브랜치, 테스트 스타일 `testing.md`, 예외 `exception.md`,
마이그레이션 `migration.md`, 코드 포맷 `code-style.md`, API URL·DTO·Swagger 작성법 `api.md`)은 그대로 따른다.

## 모듈 소개
- 구매자의 출고 전 취소 요청을 받고, 판매자가 승인·거절, 구매자가 철회한다. 승인 때 배송비를 정하고, 승인 뒤 지갑 환불·주문 반영·재고 복구(#413·#414)를 이어 간다.
- 테스트: 레포 루트에서 `./gradlew :refund:test` (통합 테스트는 Testcontainers MySQL·Kafka. 발행은 실제 토픽을 읽어 JSON으로 확인한다)

## 1. 패키지
```
com.ok.refund
├── RefundSchema              모듈 DB 스키마 이름
├── domain/                     도메인 모델·정책. JPA·Spring을 모른다
├── exception/                  RefundErrorCode
├── config/                     모듈 전용 설정
├── application/
│   ├── port/in/                입력 포트(*UseCase)와 그 입출력 값(*Command, *Query, *Result)
│   ├── port/out/               출력 포트(*Port)와 그 값
│   └── service/                유스케이스 구현(*Service)과 실패 기록 틀(RefundStepRunner)만
└── adapter/
    ├── in/web/                 컨트롤러, *Spec, 요청·응답 DTO(dto/)
    ├── in/event/               (#413~) 받은 메시지 → 값 검사 → 입력 포트
    ├── out/persistence/        JPA 엔티티, Spring Data 저장소, 변환, 영속 포트 구현
    ├── out/order/              주문 모듈 호출 포트 구현
    └── out/event/              (#413~) 이벤트 발행 포트 구현
```

## 2. 의존 방향
- `adapter.in → application.port.in → application.service → application.port.out ← adapter.out`
- `application`과 `adapter.out`은 `domain`을 쓴다. `domain`은 `application`·`adapter`와 JPA·Spring 같은 기술을 모른다.
  예외로 환불 에러 코드(`exception`), 공통 예외(`com.ok.common.exception`), lombok만 쓴다. 상태 규칙을 어기면 에러 코드로 알려야 하기 때문이다.
- 입력 어댑터는 입력 포트만 안다. 서비스·출력 포트·출력 어댑터를 직접 쓰지 않는다.
- 출력 어댑터는 출력 포트를 구현하고, 입력 포트·서비스·입력 어댑터를 모른다.
- 이 방향과 패키지·이름 규칙은 `ArchitectureTest`가 검사한다. 그 밖의 규칙은 아래처럼 지킨다.
  - 다른 모듈 타입 금지: `refund/build.gradle.kts`가 `:common`만 의존해서 컴파일이 안 된다
  - 잠근 행에만 `update`: 영속 어댑터가 실행 중에 검사한다
  - 실패 기록 틀을 트랜잭션 밖에서만: `RefundStepRunner`가 실행 중에 검사한다

## 3. 유스케이스와 트랜잭션
- 입력 포트(유스케이스) 하나를 서비스 하나가 구현한다. 한 유스케이스의 흐름은 그 서비스 한 클래스에서 읽힌다. 바깥 조율 클래스와 안쪽 DB 클래스로 나누지 않는다.
- 여러 서비스가 함께 쓰는 코드는 서비스 패키지에 도우미로 두지 않는다. 업무 규칙이면 `domain`(정책)에,
  DB에서 꺼내는 방법(쪽 크기·정렬 등)이면 영속 어댑터에 둔다. 서비스에는 흐름(포트 호출 → 도메인 호출 → 저장)만 남긴다.
- 트랜잭션은 서비스 안에서 **DB 작업 구간만** 연다(`TransactionTemplate`). 다른 모듈 호출(주문·상품 HTTP, 지갑 Kafka)은 그 구간 밖에서 한다. 느린 상대 때문에 DB 연결과 행 잠금을 오래 잡지 않으려고.
- 격리 수준처럼 구간마다 다른 설정이 필요하면 그 구간의 `TransactionTemplate`에 준다(예: 승인의 묶음 잠금은 READ COMMITTED).
- DB 작업만 하는 조회 유스케이스는 읽기 전용 구간 하나로 끝낸다.

## 4. 영속 (도메인과 JPA 분리)
- `domain`의 모델은 JPA·Hibernate·Spring 타입을 쓰지 않는다. 테이블 매핑은 `adapter/out/persistence`의 `*JpaEntity`가 맡고, 둘 사이 변환은 `*Mapper`가 한다.
- 저장은 명시적으로 한다. 서비스는 도메인 상태를 바꾼 뒤 영속 포트를 불러야 DB에 반영된다.
  서비스는 변경 감지(값을 바꾸면 저장하라는 말 없이 저장되는 JPA 기능)에 기대지 않는다.
  변경 감지는 영속 어댑터의 `update` 안에서만 쓴다(포트를 부르지 않으면 반영되지 않음).
  - 새 행: `saveNew`가 저장하고 ID·생성 시각이 채워진 도메인을 돌려준다.
  - 기존 행: `update`는 같은 트랜잭션에서 쓰기 잠금으로 불러온 행에만 쓴다(아니면 예외). 한 트랜잭션에서 여러 번 불러도 된다.
- 잠금 조회는 그 트랜잭션에서 그 행의 첫 조회여야 한다. 먼저 읽어 둔 값이 있으면 잠금은 걸려도 옛 값으로 판단한다.
- 제약 이름·SQLState 같은 DB 지식(진행 중 중복, 교착 판별)은 영속 어댑터 안에 둔다.

## 5. 다른 모듈 연동
- 다른 모듈 호출은 모두 출력 포트 뒤에 둔다. `application`은 HTTP 클라이언트·Kafka 타입을 쓰지 않는다.
- 다른 모듈 타입(enum·DTO)을 import하지 않는다. 값(문자열·숫자)으로 받는다.
- 승인 뒤 단계 호출(주문·재고)은 실패 기록 틀(`RefundStepRunner`)로 부르고, 트랜잭션 밖에서만 부른다.
- 발행: 서비스는 발행 출력 포트만 부르고, `adapter/out/event`의 구현체가 그 포트를 구현한다. 토픽 이름과 메시지 클래스는 어댑터 안에만 둔다.
- 수신: `adapter/in/event`가 받은 값을 먼저 검사한다. 없는 환불, 끝난 환불, 금액이 다른 메시지는 입력 포트에 들어가기 전에 거르고 기록한다.

## 6. 이름
| 역할 | 이름 | 위치 |
|---|---|---|
| 입력 포트 | `*UseCase` | `application/port/in` |
| 입력 값 / 조회 조건 / 결과 | `*Command` / `*Query` / `*Result` | 그 값이 지나가는 포트 옆 |
| 유스케이스 구현 | `*Service` (입력 포트 하나당 하나) | `application/service` |
| 출력 포트 | `*Port` (예: `RefundPersistencePort`, `OrderQueryPort`) | `application/port/out` |
| 영속 어댑터 / JPA 엔티티 / Spring Data 저장소 / 변환 | `*PersistenceAdapter` / `*JpaEntity` / `*JpaRepository` / `*Mapper` | `adapter/out/persistence` |
| 다른 모듈 호출 어댑터 | `*Adapter` (예: `RefundOrderHttpAdapter`) | `adapter/out/<상대 모듈>` |
| 컨트롤러 / 문서 인터페이스 / 요청·응답 | `*Controller` / `*ControllerSpec` / `*Request`·`*Response` | `adapter/in/web` |
| 도메인 모델 | 접미사 없이 도메인 이름 (`Refund`) | `domain` |

- 스프링 빈으로 등록되는 클래스(서비스·어댑터·컨트롤러·Spring Data 저장소)는 이름에 `Refund`가 들어가게 짓는다. 모든 모듈이 한 앱(monolith)으로 뜰 때 다른 모듈의 같은 이름 클래스와 빈 이름이 겹치면 앱이 시작되지 않는다.

## 7. 테스트
- 도메인: 순수 단위 테스트
- 서비스: 출력 포트를 Mockito로 대체한다. 상태를 바꾸는 유스케이스는 영속 포트에 넘긴 값을 확인한다(저장 호출 자체가 요구사항)
- 영속 어댑터: 실제 DB로 직접 테스트한다(잠금·변환·중복 감지는 서비스 경로로 다 만들 수 없어서).
  실제 DB로 만들기 어려운 예외 경우(예: 알 수 없는 DB 오류를 그대로 던지는지)만 Mockito(가짜 객체를 만들어 주는 테스트 도구)로 추가로 확인한다.
- 웹·전체 흐름: 통합 테스트(`AbstractIntegrationTest` + 주문 Fake)
- 구조: `ArchitectureTest`
