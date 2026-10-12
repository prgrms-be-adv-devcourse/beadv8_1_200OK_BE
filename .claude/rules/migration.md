---
paths:
  - "src/main/resources/db/migration/*.sql"
---

# DB 마이그레이션 (Flyway)

`src/main/resources/db/migration`의 SQL 파일을 작성할 때 따르는 규칙이다.
사람용 원문: `docs/CONVENTION.md` 8절.

## 파일 작성

- 스키마 변경은 항상 `db/migration`에 SQL 파일로 추가한다.
- 파일명은 `V{yyyyMMddHHmm}__{도메인}__{설명}.sql`이다. 구분자는 밑줄 두 개, 설명 안의 단어 구분은 밑줄 한 개다.
  - 버전은 파일을 만드는 시점의 시각(12자리)이다. 시각을 쓰면 순번(`V2`, `V3`)과 달리 브랜치끼리 버전이 겹치지 않는다.
  - 도메인은 모듈 이름(`member`, `order`, `common` 등)이다.
  - 설명은 `create_*_table`, `add_*_to_*`, `add_index_*`처럼 동작으로 시작한다.
- 엔티티를 추가하거나 수정하면 대응하는 마이그레이션을 반드시 함께 추가한다. `ddl-auto`가 `validate`라 없으면 앱이 기동하지 않는다.

```
// O
V202610091242__wallet__create_wallet_table.sql

// X: 도메인과 설명 사이 구분자가 밑줄 하나, 설명이 동작으로 시작하지 않는다
V202610091620__member_create_member.sql
```

## 적용된 파일과 병합 순서

- 이미 적용된 마이그레이션은 수정하지 않는다(체크섬이 달라져 앱이 기동하지 않는다). 변경이 필요하면 새 버전 파일을 추가한다. 로컬에서만 쓴 파일이면 `data/mysql`을 지우고 다시 적용해도 된다.
- dev 프로파일은 `out-of-order`를 허용한다. 운영은 허용하지 않는다.
- 새 마이그레이션의 버전은 `main`에 이미 있는 가장 최신 버전보다 뒤여야 한다. 병합 전에 `main`을 합쳐 확인하고, 뒤처졌으면 아직 병합되지 않은 내 파일의 버전을 현재 시각으로 바꾼다.
- 이벤트 발행 기록 테이블 마이그레이션(`...common__create_event_publication_table.sql`)은 Spring Modulith가 쓰므로 지우지 않는다.

**이유**: 개발 환경은 여러 브랜치의 병합 시점이 달라질 수 있어 순서를 허용한다. 운영은 순서대로 적용되므로, 운영 DB에 이미 적용된 버전보다 오래된 파일이 나중에 올라가면 오류가 난다.

## 테이블 옵션

- 모든 `CREATE TABLE`에 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4`를 명시한다.
- collation은 테이블에서 따로 지정하지 않는다. 특정 컬럼에 꼭 필요할 때만 그 컬럼에 지정한다.

**이유**: 엔진과 문자셋을 명시하면 DB 기본 설정이 달라도 같은 결과가 나온다. 테이블마다 collation을 지정하면 대소문자 구분 같은 비교 동작이 테이블마다 달라질 수 있다.

```sql
-- O
CREATE TABLE cart (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ...
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- X
CREATE TABLE media ( ... );                                                     -- 옵션 없음
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;       -- 테이블에 collation 지정
```

## 제약·인덱스 이름

- 모든 제약과 인덱스에 이름을 지정한다. 형식은 `{접두사}_{테이블}_{컬럼들}`이다.
- 접두사: 유니크 `uk`, 체크 `ck`, 외래키 `fk`, 인덱스 `idx`.
- 컬럼 이름은 줄이지 않고 그대로 쓴다. (`MemberDuplicateValidator`가 제약 이름으로 중복 종류를 판별한다.)

**이유**: 이름이 있어야 코드에서 제약 위반을 구별할 수 있고, 형식이 같으면 이름만 보고 대상 테이블과 컬럼을 안다.

```sql
-- O
CONSTRAINT uk_member_login_id UNIQUE (login_id),
CONSTRAINT fk_payout_item_payout_id FOREIGN KEY (payout_id) REFERENCES payout (id)
CREATE INDEX idx_payout_candidate_items_created_at ON payout_candidate_items (created_at);

-- X
UNIQUE (object_key)                                           -- 이름 없음
CONSTRAINT uk_wallet_member_type UNIQUE (member_id, type)     -- member_id를 member로 줄였다
```

## 테이블 이름

- 단수형 snake_case로 쓰고, 엔티티 이름과 대응하게 짓는다(`CartItem` → `cart_item`).

**이유**: 엔티티 이름과 1:1로 대응하면 찾기 쉽고, 단수형으로 통일하면 모듈마다 달라지지 않는다.

```sql
-- X
CREATE TABLE payout_candidate_items ( ... )   -- 복수형
```

## 외래키

- 같은 모듈 안의 엔티티 연관(`@ManyToOne` 등)에는 외래키를 둔다.
- 다른 모듈의 데이터를 가리키는 ID 컬럼에는 외래키를 두지 않는다.
- 다른 모듈 데이터의 복제본(예: order 모듈의 `market_member`)을 가리키는 컬럼은, 복제본이 이벤트로 동기화되어 참조 시점에 행이 없을 수 있으므로 외래키가 없을 수 있다.

**이유**: 같은 모듈 안에서는 DB가 정합성을 지켜 주고 조회용 인덱스도 함께 생긴다. 다른 모듈의 데이터까지 외래키로 묶으면 모듈이 DB 수준으로 결합되어 독립성을 해친다.

```sql
-- O
CONSTRAINT fk_payout_item_payout_id FOREIGN KEY (payout_id) REFERENCES payout (id)   -- 같은 모듈
member_id BIGINT NOT NULL,                                                           -- 다른 모듈 ID, FK 없음

-- X: 같은 모듈 안의 연관인데 외래키도 인덱스도 없다
cart_id BIGINT NOT NULL,
```

## 컬럼 주석 (`COMMENT`)

- 이름만으로 의미가 분명하지 않은 컬럼에 `COMMENT`를 쓴다(다른 모듈의 ID, 단위가 있는 값, 상태·플래그, 값에 의미가 있는 컬럼).
- `id`, `created_at`처럼 자명한 컬럼에는 쓰지 않는다.

**이유**: 이름만으로 알기 어려운 단위나 출처를 DB에서도 확인할 수 있게 하되, 자명한 설명으로 SQL이 길어지지 않게 한다.

```sql
-- O
product_id  BIGINT  NOT NULL COMMENT '상품 모듈 ID',
file_size   BIGINT  NOT NULL COMMENT '파일 크기 (bytes)',
purchasable BOOLEAN NOT NULL DEFAULT TRUE COMMENT '구매 가능 여부',

-- X
id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '아이디',   -- 자명한 컬럼
amount     BIGINT      NOT NULL,                                   -- 단위가 불분명한데 설명 없음
```

## SQL 포맷

- SQL 키워드는 대문자, 이름은 소문자 snake_case로 쓴다.
- `CREATE TABLE 이름 (`처럼 여는 괄호는 같은 줄에 두고, 컬럼은 4칸 들여쓴다. 괄호 위치에 맞춰 깊게 들여쓰지 않는다.
- 이 규칙은 앞으로 쓰는 파일에만 적용한다. 이미 적용된 파일은 수정하지 않는다.

**이유**: 들여쓰기가 깊으면 줄이 길어지고 테이블 이름이 바뀔 때 전체를 다시 맞춰야 한다. 4칸으로 고정하면 diff도 작다.

```sql
-- O
CREATE TABLE wallet (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- X
CREATE TABLE member
(                       -- 여는 괄호를 다음 줄에 둔다
    id BIGINT NOT NULL AUTO_INCREMENT,
```
