---
issue: 745
module: product
summary: 운영자가 DB를 열지 않고 태그·동의어 보강 후보를 찾도록, 검색어 로그에서 기간 안 0건 검색어를 공백 없앤 검색어로 묶어 횟수 순 상위 N개를 관리자 API로 보여 줌
status: 확정
date: 2026-11-19
paths: [product/src/main/java/com/ok/product/product/adapter/in/web/ProductSearchAdminController.java, product/src/main/java/com/ok/product/product/adapter/out/persistence/ProductSearchLogJpaRepository.java, product/src/main/java/com/ok/product/product/application/service/ProductSearchLogService.java]
related: [744, 746]
---

> 이 문서는 #745 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.
>
> 구현 세션의 대화는 이 문서를 쓴 세션에 없다. 이유는 커밋 메시지, 코드 주석, 이슈 본문, 테스트 시나리오 문서에 적힌 것만 옮겼다.

## 배경

0건 검색어는 동의어나 태그가 빠진 곳을 바로 보여 준다. 검색어 로그(#744)를 쌓기만 하면 DB를 직접 열어야 하므로 관리자 API로 연다. 범위는 조회 API 하나다. 화면과 태그 0개 상품 조회(#746)는 범위 밖이다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 위치 | `GET /api/v1/admin/product-search/zero-result-keywords`, 관리자 검색 전용 컨트롤러 | 같은 화면에서 볼 태그 0개 상품 조회(#746)를 같은 경로·모양으로 더하려고 한다 |
| 묶는 기준 | 공백을 없앤 검색어(`normalized_keyword`) | "샤인 머스켓"과 "샤인머스켓"처럼 띄어쓰기만 다른 검색어를 한 항목으로 본다 |
| 정렬 | 0건 횟수 내림차순, 같으면 마지막 검색 시각 내림차순, 그다음 검색어 | 자주 못 찾은 것부터 고치고, 같은 순서가 매번 나오게 한다 |
| 항목 내용 | 0건 횟수, 마지막 검색 시각, 가장 최근 원문 하나 | 묶음 기준은 공백이 없어 읽기 어렵다. 운영자가 실제 입력을 봐야 한다 |
| 원문 고르기 | 창 함수(`COUNT OVER`, `ROW_NUMBER`)로 요청당 쿼리 한 번 | `GROUP BY`만으로는 묶음 안의 원문 하나를 고를 수 없다 |
| 기간 | 날짜 단위, 시작일·종료일 모두 포함. 시작일이 보존 기간보다 이르면 400 | 이미 지운 날이 0건으로 보여 실제보다 적게 센 결과가 나간다. 날짜 단위는 #744의 날짜 단위 삭제와 맞춘다 |
| 개수 | 기본 20, 1~100 밖이면 400 | 한 번에 보는 후보 수에 상한을 둔다 |
| 인덱스 | `(result_count, searched_at)` 추가 | 검색 시각 인덱스만으로는 기간 안의 모든 검색을 읽어야 한다 |

## 버린 대안

- **(미확인) 보존 기간 밖 시작일을 잘라서 응답:** 호출자가 잘린 줄 모르고 적게 센 숫자를 믿게 된다. 그래서 거절하고, 오류 메시지에 조회 가능한 첫 날을 담는다.

## 건드릴 때 주의

- 네이티브 쿼리라 테이블 이름을 `ProductSchema.NAME`으로 직접 한정한다. 엔티티의 catalog 지정이 적용되지 않는다.
- 기간 판정은 서버 `Clock`의 날짜를 기준으로 한다. 보존 기간(`product.search.log.retention`)을 바꾸면 조회 가능 시작일도 함께 바뀐다.
- #746이 이 컨트롤러에 태그 0개 상품 조회를 더할 때 권한(`ADMIN`)과 응답 모양을 맞춘다.
