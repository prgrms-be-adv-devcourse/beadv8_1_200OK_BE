---
issue: 817
module: member
summary: 관심상품(찜)을 product가 아닌 member의 새 하위 도메인 wish에 상품 ID만 두고, 상품 존재도 검사하지 않아 member→product 의존을 만들지 않음. 상품 상세에 찜 여부를 붙일 때 product로 옮길지 다시 봄
status: 확정
date: 2026-11-20
paths: [member/src/main/java/com/ok/member/wish/domain/MemberProductWish.java, member/src/main/java/com/ok/member/wish/application/service/WishService.java, member/src/main/resources/db/migration/member/V202611201240__member__create_member_product_wish_table.sql]
related: [816]
---

> 이 문서는 #817 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다. API 동작(멱등, 되살리기, 동시 등록, 페이징)은 이슈 #817 본문과 코드 주석에 있어 여기에 되풀이하지 않는다.

## 배경

찜 등록·해제·목록만 만든다. 목록은 상품 ID와 찜한 시각만 돌려주고, 상품 카드는 프론트가 #816 API로 붙인다. 상품 상세에 찜 여부를 표시하는 일은 product가 member의 찜을 어떻게 알지 먼저 정해야 해서 범위 밖으로 뒀다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 둘 곳 | member 안 새 하위 도메인 `wish` (사용자 결정) | 이슈와 #816(다른 담당)이 이미 "member는 ID만 저장, product는 카드 조회"로 나뉘어 열려 있어 담당 범위 안에서 끝난다. 계정을 다루는 `member` 하위 도메인과 성격이 달라 패키지를 나눴다 |
| 상품 존재 검사 | 하지 않음 (사용자 결정) | 검사하면 위 결정으로 피한 member→product 의존이 다시 생긴다. 장바구니도 옵션 ID만 저장하고 검사하지 않는다 |

## 버린 대안

- **product 모듈의 하위 도메인으로 두기:** 찜 행이 상품과 같은 스키마에 있어, 숨김·삭제 상품을 조인으로 거를 수 있다. 목록이 카드까지 한 번에 돌려줄 수 있어 #816이 필요 없어질 수 있고, 상세의 찜 여부도 모듈 안 조회로 끝난다. 기술적으로는 더 깔끔하지만, 이슈 두 개와 담당을 다시 나눠야 해서 이번에는 고르지 않았다.
- **member의 `member` 하위 도메인에 섞기:** 아끼는 것은 패키지 하나뿐이고, 계정 집합에 성격이 다른 코드가 섞인다.
- **order의 장바구니 옆:** "나중에 살 것"이라는 의미만 가깝다. 상품 데이터와 멀다는 점은 member와 같다.

## 건드릴 때 주의

- 상품 상세에 찜 여부를 붙이는 작업에서 위치를 다시 본다. product가 member에 묻는 방식(동기 호출, 이벤트로 만든 사본)을 고르기 전에, 찜을 product로 옮기는 안과 비교한다. 옮기면 `member_product_wish`를 product 스키마로 옮기는 마이그레이션이 필요하고, #816이 찜 목록에 쓰이던 이유가 사라진다.
- 존재 검사가 없어서 숨김·삭제 상품과 없는 ID의 찜이 남는다. 목록 개수에는 들어가지만 카드 조회에서 빠진다. 찜 개수 상한을 둘 때는 이런 찜이 사용자가 화면에서 해제할 수 없는 자리를 차지한다는 점을 먼저 푼다.
