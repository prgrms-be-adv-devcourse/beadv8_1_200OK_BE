---
issue: 469
module: payout
summary: 정산 구조 테스트의 우회 경로(새 하위 패키지·다른 입구 경유·정산 밖 패키지)를 막고, 입력 포트가 출력 포트를 모르도록 조회 결과 변환을 서비스로 옮김
status: 확정
date: 2026-11-06
paths: [payout/src/test/java/com/ok/payout/ArchitectureTest.java, payout/src/test/java/com/ok/payout/ArchitectureRuleEdgeTest.java, payout/src/main/java/com/ok/payout/application/service/PayoutQueryResultMapper.java]
related: [441, 447]
---

> 이 문서는 #469 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

#441로 정산이 헥사고날 구조가 되었고 `ArchitectureTest`가 의존 방향을 지킨다. PR #447 리뷰에서 이 테스트를 통과하면서 규칙을 어길 수 있는 길이 지적되었고, 이 작업에서 각 경로를 가짜 클래스로 실제 돌려 통과하는 것을 확인한 뒤 막았다.

- 허용 목록이 `adapter..`·`application..`처럼 넓어서 `adapter.in.shared` 같은 새 하위 패키지를 거치면 패키지 이름을 지정한 규칙들이 보지 못했다.
- 구조 테스트는 `com.ok.payout`, `com.ok.common.event`만 읽어서, 정산 모듈 안 `com.ok.relay` 같은 패키지의 클래스가 출력 포트를 구현해도 기존 규칙 25개가 모두 통과했다.
- 입력 포트의 `*Result`가 출력 포트의 `*Row`를 받아 스스로 변환해서 port.in이 port.out을 알았다. ADR 441은 이를 허용했지만 delivery 모듈은 서비스에서 변환한다.
- ADR 441의 "관리자 실행 API는 `PayoutJobRunner`를 부른다"는 사람만 지키고 있었다.

범위 밖: 서비스가 config를 거쳐 JDBC·EntityManager에 닿는 경로(#441부터 있었고 현재 위반 코드 없음)는 다루지 않았다. 동작·API·스키마는 바꾸지 않았다. ADR 441은 고치지 않고 이 문서에 쓴다(머지된 ADR은 고치지 않는다).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 조회 결과 변환 위치 | `application/service/PayoutQueryResultMapper`(package-private)로 옮기고 "입력 포트는 출력 포트를 모른다" 규칙 추가 | 조회 Row 모양이 바뀌어도 입구가 보는 port.in이 바뀌지 않게 하고 delivery와 구조를 맞춘다 (사용자 결정) |
| 변환을 별도 클래스로 | `PayoutService` 안이 아니라 Mapper 클래스 | 조정 건(`adjustmentId != NO_ADMIN_ADJUSTMENT_ID`) 분기를 확인하는 기존 테스트를 그대로 옮길 수 있다 |
| 허용 목록 단위 | 실제 쓰는 하위 패키지까지 나열(in: web·event·batch, out: persistence·event, application: port.in·port.out·service) | 레이어 단위로는 `adapter.in.shared`를 거치는 우회가 실제로 통과했다. 새 입구·출구를 만들 때 한 줄 추가하는 비용을 받아들인다 (사용자 확인) |
| 다른 모듈 의존 | `com.ok` 중 payout과 common만 안다 | MSA 분리 때 다른 모듈 내부 타입에 묶이지 않게 한다 |
| 입구 → 배치 | 배치를 뺀 모든 입구(web·event)는 배치 중 `PayoutJobRunner`만 안다 | web만 대상으로 하면 web → event → `PayoutJobConfig` 경로가 실제로 통과했다. 기존 "배치를 뺀 입구는 입력 포트만 안다"와 범위를 맞춘다 (사용자 확인) |
| 정산 밖 패키지 | 정산 모듈 빌드 결과 폴더 전체를 `ClassFileImporter`로 읽어 `com.ok.payout` 밖 클래스를 막는 `@Test` (`PayoutApplication`만 예외) | `@AnalyzeClasses`는 패키지 기준이라 모듈 안 다른 패키지를 못 본다. `@ArchTest` 필드로 두면 같은 가져오기 범위가 적용되어 의미가 없다 |
| 정산 구조 규칙 문서 | `payout/CLAUDE.md`에 두고, 레포 `CLAUDE.md`·`DEVELOPMENT.md`는 가리키기만 함 | ADR 441이 남긴 "`structure.md`의 레이어 규칙은 정산과 맞지 않는다(문서 반영은 팀 확인 후)"를 정리한다. 공통 문서는 팀 전체가 보므로 정산 내용은 정산 폴더 한 곳에 둔다. 레이어 규칙을 근거로 적은 정산 주석도 이 문서로 바꿨고, 레이어 규칙("발행 인터페이스는 `*Command`를 받는다")을 따라 붙인 발행 값 이름 `SellerPayoutCreatedCommand`는 `SellerPayoutCreated`로 바꿨다. `*Command`는 port.in의 입력 값에만 쓴다 (사용자 결정) |
| 규칙 자체의 검증 | `ArchitectureRuleEdgeTest`: 규칙마다 일부러 어긴 가짜 클래스를 넣고 위반이 잡히는지 확인 | 규칙을 느슨하게 고쳐도 다른 테스트는 통과하므로, 규칙이 실제로 잡는지를 계속 확인한다 |

## 버린 대안

- **변환 위치:** 그대로 port.in에 두기(ADR 441 허용)는 delivery와 달라서 버렸다.
- **허용 목록:** `adapter.in..`·`adapter.out..` 레이어 단위는 새 하위 패키지 우회가 남아 버렸다.
- **정산 밖 패키지:** `@AnalyzeClasses` 범위만 `com.ok`로 넓히는 방법은 다른 모듈 클래스까지 정산 규칙을 받게 되어 버렸다. (미확인) 이 경우 어떤 규칙이 실패하는지는 돌려 보지 않았다.
- **입구 → 배치:** web만 대상은 event 경유 우회로 버렸다. 리스너가 배치 내부를 써야 하는 날이 오면 규칙을 고쳐야 하는 비용을 감수한다.
- **엣지 확인:** 한 번만 돌려 보고 지우는 방식은 규칙이 나중에 느슨해져도 알 수 없어 버렸다.

## 건드릴 때 주의

- 새 입구·출구 하위 패키지(예: `adapter.in.grpc`)를 만들면 `정산_클래스는_정해진_패키지에만_있다`의 허용 목록에 함께 추가해야 한다. "배치를 뺀 입구" 규칙들은 자동으로 적용된다.
- `archunit.properties`의 `failOnEmptyShould=false` 때문에 가져온 클래스가 0개여도 규칙이 통과한다. 모듈 전체 검사 `@Test`는 대표 클래스(`PayoutApplication`, `PayoutService`)가 들어 있는지 먼저 확인한다. 빌드 출력 위치를 바꾸면 이 테스트를 확인한다.
- 엣지 테스트의 가짜 클래스(`EdgeFake*`)는 test 소스에만 두고 Spring 애노테이션을 붙이지 않는다. 붙이면 빈이 되어 앱 테스트에 섞인다.
- 규칙이 못 잡는 것: 다른 클래스의 `static final` 컴파일 시점 상수만 읽는 경우(값이 복사되어 의존이 남지 않는다), port.in → service 하위 패키지 → port.out 같은 간접 경로.
