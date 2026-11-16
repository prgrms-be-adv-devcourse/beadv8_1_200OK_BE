---
issue: 587
module: member
summary: 판매자 출고지 우편번호를 신청 때 받아 승인 때 보내고 이후 변경은 프로필에서 바뀔 때만 delivery에 Kafka로 보내며, 별도 outbox를 만들지 않고 #560의 회원 outbox에 본문 칸을 더해 판매자 하위 도메인이 유스케이스로 기록하게 함. 판매자 승인 스프링 이벤트는 지움
status: 확정
date: 2026-11-15
paths: [member/src/main/java/com/ok/member/seller/domain/SellerProfile.java, member/src/main/java/com/ok/member/seller/application/service/SellerProfileService.java, member/src/main/java/com/ok/member/seller/adapter/out/member/MemberOutboxAdapter.java, member/src/main/java/com/ok/member/member/application/service/MemberOutboxService.java, member/src/main/java/com/ok/member/member/adapter/out/event/MemberMessagePayloadJson.java, member/src/main/java/com/ok/member/member/adapter/out/event/KafkaMemberMessageSender.java]
related: [560, 558]
---

> 이 문서는 #587 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

delivery가 배송비 계산에 쓸 판매자 출고지 사본을 갖도록(#558), 판매자가 상점 프로필에서 출고지 우편번호를 등록·수정하면 토픽 `member.seller-origin-postal-code`로 보낸다. 토픽·키·값은 delivery와 합의했다.
범위 밖: delivery 쪽 저장과 배송비 계산, 판매자 권한 회수 때의 삭제 메시지.
함께 한 일: 받는 모듈이 없는 판매자 승인 스프링 이벤트(`SellerApprovedEvent`) 제거.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 처음 받는 시점 | 판매자 신청 때 필수로 받아 승인 때 보냄. 이후 변경은 프로필 (사용자 결정) | 상품 등록은 SELLER 권한만 확인해(`ProductController`) 프로필을 등록하기 전에도 팔 수 있다. 프로필 때만 받으면 승인부터 프로필 등록까지 delivery가 출고지를 모른다. 신청서 값은 처음 값 기록이고 고치지 않는다(상호명을 신청서·프로필에 따로 두는 것과 같은 구조). delivery의 처음 요청(신청 때 받아 승인 때 발행)과도 같다 |
| 필수 여부 | 신청·프로필 등록·수정 요청 모두 필수, 두 컬럼은 NULL 허용 | 프로필 수정이 전체 덮어쓰기라 화면이 매번 모든 값을 보낸다. 컬럼 전에 만든 프로필은 값이 없어, 다음 수정 때 채우고 그때 처음 보낸다(일괄 발행 불필요) |
| 발행 조건 | 승인 때 신청서 값(값이 없는 예전 신청은 보내지 않음), 프로필 등록은 항상, 프로필 수정은 우편번호가 바뀔 때만 | delivery가 `occurredAt` 기준으로 덮어써 결과는 같고, 상호명만 고친 수정까지 보내면 메시지만 는다. 바뀌었는지는 `SellerProfile.update`가 돌려준다 |
| `sellerId` | 회원 ID 그대로 | 판매자는 회원이 승인받은 역할이라 회원 하나에 판매자 하나다. member 프로필이 `member_id`로 판매자를 찾고, delivery·product도 로그인 회원 ID를 `sellerId`로 쓴다(코드로 확인). 상점 여러 개, 직원 계정, 상점 양도가 생기면 별도 ID를 다시 본다 |
| `occurredAt` | `LocalDateTime`(한국 시간, 시간대 표기 없음), 마이크로초로 자름 | #560 메시지에는 시각이 없어 정산 → 지갑 메시지(`createdAt`)와 모양을 맞췄다. DB `DATETIME(6)`과 정밀도가 같아야 delivery가 저장한 시각과 비교할 때 나노초 차이로 같은 변경을 더 새것으로 보지 않는다 |
| 형식 오류 응답 | 422 (`INVALID_ORIGIN_POSTAL_CODE`, 요청 검증은 `validation-failed`) | 이슈에는 400이었으나 요청 값 검증 실패는 422가 팀 규칙이다(`api.md`) |
| outbox | #560의 `member_outbox`를 그대로 쓰고 본문 칸(`payload`)만 더함 (사용자 결정) | 우편번호는 바뀌는 값이라 기록한 시점의 값을 저장해야 한다. 판매자 ID가 회원 ID라 `member_id` 외래키와 30초 발송 작업·실패 시 멈춤 정책이 그대로 맞는다 |
| 판매자가 줄을 남기는 길 | seller의 출력 어댑터(`MemberOutboxAdapter`)가 member의 `MemberOutboxRecordUseCase`를 부름 | outbox가 `member` 하위 도메인에 있고, ArchitectureTest는 하위 도메인끼리 출력 어댑터에서 상대 유스케이스로만 부르게 한다(`MemberAuthAdapter`와 같은 길). 판매자 서비스는 `SellerEventPublisher`만 안다 |
| 프로필 동시 수정 | `SellerProfile`에 `@Version`을 두고 수정 때 flush로 검사해 나중 커밋을 409(`SELLER_PROFILE_CONCURRENT_MODIFICATION`)로 거부, 검사를 통과한 뒤에 outbox 기록 (멘토링 피드백) | delivery는 `occurredAt`이 늦은 메시지로만 덮어써, member에서 같은 판매자의 변경이 차례로 커밋될 때만 결과가 맞다. 수정은 모든 칸을 덮어써서, 두 탭에서 한쪽은 출고지를, 다른 쪽은 상호명만 고쳐 동시에 저장하면 나중 요청이 출고지를 되돌리면서 "바뀌지 않음"으로 보아 메시지를 남기지 않는다. 그러면 판매자가 출고지를 다시 바꿀 때까지 두 모듈 값이 다르다. 판매자 신청과 같은 방식(`flushDetectingConflict`)이다 |
| 같은 트랜잭션 보장 | 기록 유스케이스를 `Propagation.MANDATORY`로 | 따로 커밋되면 롤백된 프로필 변경이 나가거나 커밋된 변경이 안 나간다. 트랜잭션 없이 부르면 예외가 나게 해 실수를 막는다 |
| 본문 만들기·읽기 | `adapter/out/event`의 `MemberMessagePayloadJson`이 메시지 클래스로 쓰고 읽음(포트 `MemberMessagePayloadWriter`) | 메시지 모양은 받는 쪽과의 약속이라 메시지 클래스가 있는 어댑터에 둔다. 보낼 때는 JSON 문자열이 아니라 메시지 객체를 보낸다. 문자열이면 Kafka 공통 직렬화가 따옴표로 한 번 더 감싸고, 객체로 보내야 시각 모양이 다른 모듈 메시지와 같아진다 |
| 판매자 승인 이벤트 | 스프링 발행·포트 메서드·common 클래스·테스트를 지움 (사용자 결정, 지갑 담당과 합의) | 받는 모듈이 없고, 분리 뒤에는 스프링 이벤트가 member 앱 밖으로 나가지 않으며, 팀이 스프링 이벤트를 모두 걷어 내기로 했다. 필요해지면 지갑 출금 작업 때 메시지 약속을 정해 outbox로 보낸다 |

## 버린 대안

- **상품 등록 때 출고지 등록 여부 확인:** 프로필 없이는 상품을 못 올리게 막으면 빈틈이 없지만, product가 member에 물어봐야 해서 분리를 앞두고 product → member 동기 호출이 새로 생긴다. member가 느리거나 죽으면 상품 등록도 실패하고, 이미 올린 상품의 빈틈은 그대로다.
- **프로필 등록·수정 때만 보내기(처음 구현):** 승인부터 프로필 등록까지 출고지가 비어, 그동안 올린 상품의 배송비를 출고지 없이 계산하게 된다.
- **outbox를 별도 하위 도메인으로 떼기:** 표·발송 작업·Kafka 발송기를 `outbox` 하위 도메인으로 옮기고 토픽·키·본문을 일반화하는 안. 이름은 더 맞지만 방금 머지한 #560 코드를 통째로 옮겨 PR이 커진다. member 밖에서도 outbox가 필요해지면 그때 뗀다.
- **판매자 하위 도메인에 outbox를 하나 더:** 표·발송 작업·테스트가 두 벌이 되고 메시지 종류가 늘 때마다 또 는다.
- **본문 없이 보낼 때 프로필을 다시 읽기:** 저장 칸이 필요 없고 늘 최신 값이 나가지만, 회원 하위 도메인의 발송 작업이 판매자 데이터를 읽어야 해 하위 도메인 의존이 반대로 생긴다.
- **outbox 표에 `topic` 칸 추가:** 종류(`event_type`)로 토픽이 정해져 지금은 필요 없다.
- **출고지 상세주소까지 받기(보내지 않더라도 프로필에 저장):** 판매자 주소는 일단 우편번호만 받는다(사용자 결정). 개인정보보호법은 목적에 필요한 만큼만 수집하게 하는데, 지금은 상세주소를 쓰는 기능이 없어 수집 목적을 댈 수 없다. 개인 판매자면 출고지가 집 주소일 수 있다. 배송비 계산은 우편번호로 충분하고(도서산간도 우편번호 단위), delivery는 송장을 발행하지 않고 판매자가 입력한 송장 번호로 조회만 한다. 송장 발행·집하 요청, 출고 후 반품 회수, 구매자 화면의 출고지 표시처럼 쓸 기능이 생기면 그 이슈에서 수집 목적을 적고 프로필에 주소를 더한다. 메시지로 보내야 하면 필드를 추가로 맞춘다(필드 추가는 기존 받는 쪽을 깨지 않는다). 감수하는 것은 그때 이미 등록한 판매자에게 주소를 다시 받아야 한다는 점이다.
- **읽지 못하는 본문의 줄을 격리하고 건너뛰기(리뷰 봇 제안):** #560의 "실패하면 멈춤"은 회원 ID만 보낼 때 정한 것이라 본문이 생긴 지금 다시 봤다. 본문은 검증된 값으로 우리 직렬화기가 만든 JSON이라 읽기 실패는 버그이고, 건너뛰면 그 판매자의 출고지가 delivery에 조용히 빠진다. 막힌 채로 ERROR(10번 초과)를 남겨 사람이 고치게 한다. 감수하는 것은 버그 하나가 회원가입 메시지까지 막는다는 점이다.
- **승인 이벤트를 Kafka로 미리 만들기:** 받는 쪽 없이 메시지 약속을 정하게 되고, 이미 승인된 판매자를 채우는 문제(백필)는 Kafka 보관 기간 때문에 미리 보내도 풀리지 않는다.

## 건드릴 때 주의

- 이 작업 전에 승인된 판매자는 신청서와 프로필 모두 우편번호가 비어 있어, 다음 프로필 수정 때까지 delivery에 출고지가 없다. delivery는 배송 설정이 없는 판매자에게 기본값을 쓰는 방식(`SellerShippingSettingService`)으로 출고지가 없는 경우도 다뤄야 한다.
- 승인 때 발행은 낙관적 락 검사(flush)를 통과한 뒤에 한다. 순서를 바꾸면 다른 관리자가 먼저 처리해 409가 나는 승인도 줄을 남기려 하다 같은 트랜잭션에서 롤백된다(결과는 같지만 불필요한 작업).
- 프로필 수정에서 outbox 기록은 버전 검사(flush) 뒤에 한다. `SellerProfile`의 `@Version`을 빼거나 수정을 부분 수정으로 바꾸면 위 동시 수정 문제를 다시 본다.
- `MemberOutboxRecordUseCase`는 반드시 프로필 저장 트랜잭션 안에서 불러야 한다. 서비스 메서드의 `@Transactional`을 빼면 `IllegalTransactionStateException`이 난다.
- 메시지 필드를 바꾸면 이미 쌓인 PENDING 줄의 본문이 옛 모양이다. 보낼 때 메시지 클래스로 다시 읽으므로 필드 이름을 바꾸거나 지우면 그 줄이 읽기 실패로 막히고, 실패 시 멈춤 정책 때문에 뒤 줄도 모두 기다린다. 필드를 바꾸기 전에 PENDING 줄이 0건인지 확인한다.
- 본문은 `VARCHAR(1000)`이고 넘치면 기록 단계에서 예외가 난다(잘라 저장하면 깨진 JSON이 나가서).
- 판매자 권한 회수가 생기면 delivery 사본을 어떻게 지울지(삭제 메시지 또는 tombstone) 다시 정한다. 지금은 우편번호를 비울 수 없어 삭제 메시지가 없다.
- `docs/CONVENTION.md`와 `.claude/rules/structure.md`의 이름 예시에 지운 `SpringSellerEventPublisher`가 남아 있다. 규칙은 원문부터 고쳐야 해서 이 작업에서는 바꾸지 않았다.
