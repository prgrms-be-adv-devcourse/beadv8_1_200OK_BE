---
issue: 659
module: product
summary: 외부 검색엔진 없이 MySQL ngram FULLTEXT와 자모 분해 컬럼으로 구매자 상품 검색·정렬을 붙이고, 검색 컬럼은 상품과 1:1 테이블에 같은 트랜잭션으로 저장
status: 확정
date: 2026-11-16
paths: [product/src/main/java/com/ok/product/product/domain/SearchTextNormalizer.java, product/src/main/java/com/ok/product/product/domain/ProductSearch.java, product/src/main/java/com/ok/product/product/application/service/ProductSearchIndexer.java, product/src/main/java/com/ok/product/product/application/service/ProductItemDictionary.java, product/src/main/java/com/ok/product/product/application/service/ProductSearchBackfillService.java, product/src/main/resources/db/migration/product/V202611161356__product__create_product_search_table.sql]
related: [670]
---

> 이 문서는 #659 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

구매자 상품 목록에 키워드 검색이 없었다. 띄어쓰기가 다르거나("샤인 머스켓") 자모 하나가 틀려도 찾게 하는 것이 목표다. 작업은 두 PR로 나눴다. 첫 PR은 검색 컬럼 저장과 백필이고(사용자에게 보이는 변화 없음), 둘째 PR은 검색·정렬 API와 검증·1글자 검색·타임아웃이다. 검색 SQL과 1글자 규칙의 코드는 둘째 PR의 `BuyerProductQueryJdbcRepository`, `BuyerProductQueryJdbcConfig`, `ProductSearchKeyword`, `product-search/single-char-items.json`에 있다.

범위 밖: 상품 태그와 `keywords` 채우기(F9), 오타 교정 제안(F7), 평가셋 회귀 테스트, 점수 하한과 오타 순위 재조정(#670).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 검색 엔진 | MySQL ngram FULLTEXT + 정규화 컬럼 | 상품 300개·검색어 665개 실험에서 앱 내장 Lucene과 recall@10 차이가 −0.007 [95% CI −0.030, +0.015]로 의미가 없었다. 오타를 잡은 것은 엔진이 아니라 자모 분해였다(자모 컬럼을 빼면 오타 recall 0.837 → 0.629). Elasticsearch는 서버 메모리 여유가 없어 쓰지 않는다 |
| 정규화 | 저장과 검색이 같은 함수(`SearchTextNormalizer`). NFC → 소문자 → 기호를 공백으로, 그리고 공백 제거본·자모 분해본 | 규칙이 한쪽만 바뀌면 같은 단어가 다른 토큰이 되어 조용히 못 찾는다. NFC는 NFD로 들어온 한글을 합치려고 거친다 |
| 규칙 버전 | `SearchTextNormalizer.VERSION`. 정규화뿐 아니라 `search_item` 규칙(품목 사전, 한 글자 표)을 바꿔도 올린다 | 올리면 다음 기동 때 백필이 옛 버전 행을 다시 계산한다. 버전이 없으면 저장된 값과 검색어 정규화가 어긋난 채 남는다 |
| 검색 컬럼 위치 | `product`와 1:1인 `product_search` 테이블 | F9가 `keywords`를 비동기로 고칠 때 상품 version이 오르면 판매자 수정이 `PRODUCT_CONCURRENT_MODIFICATION`으로 실패한다. `Product`가 검색 관심사를 모르게 하는 효과도 있다 |
| 저장 시점 | 상품 등록·수정과 같은 트랜잭션. 상태·노출 변경과 삭제는 건드리지 않음. `refresh`는 `keywords`를 지우지 않음 | "등록했는데 검색되지 않는" 상품이 생기지 않게 한다. 노출 여부는 검색할 때 `product`에서 거른다. `keywords`는 F9가 채우는 값이다 |
| 기존 상품 채우기 | 기동 시 백필. id 커서로 500개씩, 묶음마다 새 트랜잭션, 실패해도 기동은 계속 | 자모 분해와 품목 찾기가 앱 코드라 마이그레이션 SQL로 같은 값을 만들 수 없다. 처음부터 다시 찾으면 계속 실패하는 상품 하나에 같은 묶음을 끝없이 반복한다 |
| 검색 SQL | `JdbcClient`. id 한 페이지와 개수를 먼저 구하고 상품을 다시 불러와 그 순서로 맞춤 | Querydsl은 `MATCH`를 쓰려면 Hibernate 함수 등록이 더 필요하고, `@Query`는 정렬을 바꾸려면 CASE를 겹겹이 써야 한다. 응답과 품절 표시를 검색어 없는 목록과 같은 코드(`ProductSummaryResult.from`)로 만든다 |
| 점수 | `name_compact×3 + search_body + keywords + name_jamo×0.5`, 절대 하한 1.0 (사용자 결정) | 가중치는 실험 값이다. 하한 1.0은 E0에서 recall@10을 0.7%p만 잃고 평균 결과 수를 178 → 30건으로 줄였다. ngram이라 자모 조각 하나만 겹친 상품도 점수를 받는다 |
| 자모 결과 | 글자가 그대로 겹친 상품이 하나라도 있으면 자모로만 걸린 상품을 결과와 개수에서 뺀다. 없을 때만 자모 결과를 쓴다 (사용자 결정) | 상품 3,000개에서 하한 1.0을 넘은 자모 노이즈가 많았다("청포도"에 청도 복숭아, 185건 중 145건). 이 규칙으로 40건이 되고 정답 순위는 그대로다. E0에서 1위 대비 상대 하한(0.2)보다 모호한 검색어 recall이 좋았다(0.350 대 0.292) |
| 노출 조건 | `VISIBLE` + 미삭제. 판매 상태로 거르지 않음 (사용자 결정) | 기존 구매자 목록과 같게 둔다. 검색어 유무로 나오는 상품이 달라지지 않게 하기 위해서다 |
| 구매자 목록 경로 | 검색어가 없어도 같은 SQL. Querydsl 구매자 메서드는 지움 | 정렬과 품절 규칙을 두 구현에 나누면 어긋난다. 그래서 검색어 없는 목록도 품절 상품이 뒤로 간다(동작 변화) |
| 품절 | 저장하지 않고 쿼리에서 `Product.isSoldOut()`과 같은 기준으로 계산 | 저장하면 재고 배정·해제·일부 해제·재고 수정 경로가 모두 그 값을 고쳐야 하고, 하나라도 빠지면 순서만 조용히 틀어진다 |
| 정렬 | 모든 정렬 앞에 품절, 마지막 키는 `p.id DESC`. 동점은 `(rating_sum + 40.0) / (review_count + 5)` (사용자 결정) | 고유 키로 끝나야 순서가 하나로 정해져 페이지 사이 중복·누락이 없다. `rating_sum`은 1~10 척도라 별 4.0 × 2 × 리뷰 5개 = 40.0이다 |
| 1글자 검색어 | FULLTEXT 대신 `search_item` 정확 일치. `search_item`은 "가장 긴 품목 → 사람이 고른 한 글자 표"로 계산 (사용자 결정) | ngram은 두 글자 조각만 인덱싱한다. 가장 긴 품목을 먼저 골라야 배추가 "배"로 가지 않는다. 표가 있어야 "귤"로 감귤을 찾는다 |
| 검증 | 검색어는 받은 문자열 그대로 50자까지(Spec의 `@Size`). 공백·기호뿐이면 검색어 없음 | 입력 경계에서 한 번만 검증한다. 다듬은 뒤 길이를 재는 시나리오(S24)와 다르다 |
| 타임아웃 | 목록 SQL 전용 `JdbcClient`(기본 빈 후보에서 뺌)에 시간 제한. 넘으면 503, 로그에 검색어 원문을 남기지 않음 | 느린 검색이 커넥션을 오래 잡아 같은 풀을 쓰는 다른 기능을 막지 않게 한다. 기본 `JdbcTemplate`·`JdbcClient`는 다른 모듈이 쓴다 |

## 버린 대안

- **검색 엔진:** Elasticsearch는 메모리 제약과 별도 동기화(같은 트랜잭션을 잃음) 때문에 버렸다. `LIKE`는 E0에서 recall@10 0.028이었고 오타는 모두 0건이었다. 감수하는 것은 오타 검색이 상품 수가 늘수록 나빠지는 점이다(#670).
- **검색 행 저장:** 이벤트나 별도 트랜잭션으로 빼는 방식은 검색 행이 늦게 생기거나 빠지는 구간을 만들어 버렸다.
- **1글자 검색:** `LIKE '배%'`는 상품명이 산지로 시작해 맞지 않았다. "1글자 품목이 상품명에 들어 있으면 포함"은 배추가 "배", 감자가 "감"에 걸려 버렸다.
- **검색 시점의 LLM·임베딩:** 검색어 재작성과 임베딩 검색(RRF)은 E3(상품 300개, 막연한 표현 120개)에서 등록 시 보강 대비 이득이 없었다(재작성 +0.004 [−0.061, +0.075], 임베딩 −0.010 [−0.056, +0.034]). 임베딩은 띄어쓰기(−0.069)와 바른 검색어(−0.032)를 오히려 깎았다. 그래서 검색 경로에는 외부 호출을 넣지 않고, 막연한 표현은 등록할 때 한 번 보강하는 방식(F9, 범위 밖)으로 푼다.
- **점수 하한:** 1위 점수 대비 상대 하한(0.2)은 결과 수를 더 잘 줄였지만, 정답이 1위보다 훨씬 낮은 동의어·모호한 검색어가 손해를 봐 쓰지 않았다. 자모 노이즈는 위 "자모 결과" 규칙으로 거른다.
- **품절:** 상품에 품절 컬럼을 두는 방식은 위 "품절" 이유로 버렸다. 감수하는 것은 행마다 도는 서브쿼리 비용이다.

## 건드릴 때 주의

- 정규화, 품목 사전(`items.json`), 한 글자 표(`single-char-items.json`) 중 하나라도 바꾸면 `SearchTextNormalizer.VERSION`을 올린다. 안 올리면 기존 행이 옛 규칙으로 남는다.
- 마이그레이션의 `search_item` 컬럼 COMMENT("가장 긴 품목")는 지금 의미와 다르다. 의미는 `ProductSearch` 필드 주석이 맞다.
- FULLTEXT 점수는 IDF라 상품 수와 DELETE 이력에 따라 바뀐다. 점수에 기대는 통합 테스트는 `product_search`를 `TRUNCATE`로 정리해야 매번 같은 점수가 나온다. 같은 이유로 절대 하한의 의미도 운영에서 흔들릴 수 있다(미확인, #670).
- `MATCH`가 WHERE에 없어 실행 계획은 노출 상품 전체를 읽는다. 지연은 상품 수에 비례한다. 1만 건 이상이면 품절 집계 JOIN이나 FULLTEXT 후보 좁히기가 필요하다(미확인).
- 네이티브 SQL은 엔티티 catalog가 적용되지 않으므로 테이블 이름을 `ProductSchema.NAME`으로 한정한다. 검색어가 없을 때 `product_search`를 JOIN하면 검색 행이 없는 상품이 목록에서 사라진다.
- 설정 키(`product.search.min-score`, `product.search.query-timeout`)는 `application-product.yaml`, monolith `application.yaml`, product `module-test.yml` 세 곳에 둔다. monolith는 모듈 설정 파일을 읽지 않는다. JDBC 시간 제한은 초 단위라 1초 미만은 "제한 없음"이 되므로 기동 시 막는다.
- 상품을 지우는 테스트는 FK 때문에 `product_search`를 먼저 지운다.
- 기동 직후 백필이 상품을 읽은 뒤 판매자가 같은 상품을 고치면 옛 값으로 검색 행을 덮을 수 있다(미확인). 서버가 2대가 되면 백필에 ShedLock을 건다.
