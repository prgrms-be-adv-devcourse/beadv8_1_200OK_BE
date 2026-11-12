---
issue: 479
module: member
summary: member 하위 도메인 세 개의 헥사고날 전환을 마무리하며 옛 레이어 규칙과 freeze 기준선을 지우고, 새 하위 도메인이 검사에서 빠지지 않는 경계 규칙과 Kafka 발행 도구 규칙을 더했다. 컨벤션 원문은 헥사고날 모듈 안내와 예시만 고쳤다.
status: 확정
date: 2026-11-12
paths: [member/src/test/java/com/ok/member/ArchitectureTest.java, docs/CONVENTION.md, .claude/rules/structure.md]
related: [425, 475, 476, 477, 478]
---

> 이 문서는 #479 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

member 헥사고날 전환(#475)의 마지막 단계다. #476~#478 동안 옛 레이어 규칙(`controller → service → repository → domain`)과 헥사고날 규칙이 `ArchitectureTest`에 함께 있었고, 컨벤션 원문의 예시는 member의 옛 트리였다. 운영 코드는 바꾸지 않는다(테스트와 문서만).

전환 전체에서 정한 것은 이전 기록에 있고 여기서 되풀이하지 않는다.

- 하위 도메인마다 헥사곤을 두고 같은 규칙을 목록으로 적용: #476, #477
- 하위 도메인 사이 호출은 `adapter.out`에서만, 비밀번호 확인 포트를 두지 않은 이유, auth 서버 분리 보류: #478
- 비밀번호 비교를 auth(`LoginFacade`)에 두는 이유: #425. bcrypt를 트랜잭션 밖에서 하고, 발급 트랜잭션에서 같은 해시인지 다시 확인한다. member가 비교까지 맡으면 이 재확인에 쓸 해시를 auth가 받지 못해 #425 구조를 다시 짜야 한다. 그래서 member는 해시를 담은 `MemberLoginCredential`을 넘기고 비교는 auth가 한다(#477).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 하위 도메인 목록 | `HEXAGONAL_SUBDOMAINS`를 `SUBDOMAINS`로 이름만 바꿔 유지하고, "member 클래스는 목록의 하위 도메인, 모듈 최상위, `config`에만 있다" 규칙 추가 (사용자 결정) | 하위 도메인별 레이어 규칙, 하위 도메인 사이 규칙, 빈 규칙 실패 검사는 목록이 있어야 한다. 목록만 두면 새 하위 도메인을 빠뜨렸을 때 어떤 규칙에도 걸리지 않고 통과한다 |
| freeze | 기준선(`archunit_store`)과 `freeze(...)`를 모두 지움 | 기준선 5개가 모두 위반 0건이었다. order·wallet도 freeze 없이 운영한다 |
| 옛 레이어 규칙 | 레이어 순서, `..event..`·`..service..`·`..controller..`·`..repository..` 기준 규칙을 지우고, 남길 뜻은 모듈 전체 헥사고날 규칙으로 바꿈(`Service`는 `application.service`, 엔티티는 `domain`, 이벤트 클래스는 `adapter`) | 옛 패키지가 남지 않아 대상이 없다. order `ArchitectureTest`와 같은 모양으로 맞춰 모듈 사이에서 규칙을 비교하기 쉽게 한다 |
| Kafka 발행 | 발행 도구 기준을 `ApplicationEventPublisher` 또는 `KafkaOperations`로 넓혀, `adapter.out`에서만 쓰고 출력 포트를 구현하게 함 (사용자 결정) | Kafka로 옮기면 `KafkaTemplate`을 쓰는 클래스를 발행 구현체로 알아보지 못해, 서비스가 직접 써도 통과한다. `KafkaOperations` 기준이라 상위 타입으로 받아도 걸린다(refund·payout과 같다) |
| 발행 도구 규칙 범위 | 하위 도메인별이 아니라 `com.ok.member..` 전체 | 모듈 최상위와 `config`도 발행 도구를 쓰면 안 된다 |
| 발행 구현체 규칙 범위 | #478의 `EVENT_PUBLISHING_SUBDOMAINS`(seller, member) 목록을 지우고 모듈 전체(`com.ok.member.*.adapter.out..`)에 걸고, 대상이 없으면 실패 (PR 리뷰 반영, 사용자 확인) | 목록이면 auth가 발행을 시작하고 목록에 더하지 않았을 때 검사를 건너뛴다. 다른 하위 도메인의 출력 포트를 구현하는 것은 하위 도메인 사이 규칙이 막는 것을 임시 클래스로 확인했다 |
| 이벤트 클래스 규칙 | `onlyBeAccessed` 대신 `onlyHaveDependentClassesThat`(의존 전체) (구현 중 정함) | `onlyBeAccessed`는 호출·필드 접근만 봐서, 서비스가 이벤트 타입을 필드로만 들고 있으면 통과하는 것을 임시 클래스로 확인했다. 같은 파일의 하위 도메인 사이 규칙도 같은 이유로 의존 기준이다 |
| 규칙 이름 | `헥사고날_` 접두어를 빼 order·wallet과 같은 이름으로 | 옛 규칙과 구분하려던 접두어라 이제 필요 없다 |
| 컨벤션 원문 범위 | 2절 첫머리에 "헥사고날 모듈은 레이어 패키지·발행 인터페이스·DTO 위치 대신 `DEVELOPMENT.md`의 모듈별 구조를 따른다"를 넣고, member 옛 트리 예시를 product(product/review)로 바꿈. `structure.md`에 같은 내용 반영 (사용자 결정) | 레이어 구조로 남은 모듈(media, product)이 있어 레이어 규칙은 그대로 유효하다. 어느 모듈이 헥사고날인지는 `CLAUDE.md` 한 곳에만 두어 목록이 두 곳에서 어긋나지 않게 한다 |

## 버린 대안

- **하위 도메인 목록:** order처럼 `com.ok.member.*.` 와일드카드로 바꾸는 안은 새 하위 도메인을 자동으로 검사하지만, 하위 도메인별 규칙은 목록이 필요해 두 방식이 섞이고 빈 규칙 실패 검사가 약해져 버렸다.
- **Kafka 규칙:** 출력 포트 구현만 보는 안은 서비스가 `KafkaTemplate`을 직접 써도 통과해 지금의 Spring 이벤트 규칙보다 약해 버렸다. refund처럼 Kafka 라이브러리 전체를 이벤트 어댑터로 묶는 안은 아직 Kafka를 쓰지 않는 모듈에 미리 걸 근거가 없어서 버렸다.
- **컨벤션 원문:** 2절을 헥사고날 기준으로 다시 쓰고 레이어 구조를 예외로 내리는 안은 새 모듈의 기본 구조를 바꾸는 팀 결정이라 이 이슈 범위 밖이다.

## 건드릴 때 주의

- 새 하위 도메인을 만들면 `SUBDOMAINS`에 더한다. 이벤트 발행 구현체 규칙은 모듈 전체에 걸려 있어 따로 등록하지 않는다.
- 발행 구현체 규칙은 대상이 없으면 실패한다. member의 모든 이벤트 발행을 없애면 이 규칙도 함께 정리한다.
- 하위 도메인 밖에 둘 수 있는 곳은 모듈 최상위(다른 모듈에 공개하는 클래스)와 `com.ok.member.config`뿐이다. 다른 하위 패키지가 필요하면 경계 규칙의 허용 목록을 함께 고친다.
- 컨트롤러·저장소 위치 규칙은 하위 도메인 안만 검사한다. 모듈 최상위나 `config`에 두는 것은 리뷰에서 본다.
- 컨벤션 2절의 하위 도메인 예시는 product다. product가 헥사고날로 바뀌면 하위 도메인이 있는 다른 레이어 모듈로 바꾸거나, 그런 모듈이 없으면 예시를 지운다.
