---
name: migration
description: Flyway 마이그레이션 SQL 파일을 이 프로젝트 컨벤션대로 새로 만든다. 테이블 생성, 컬럼·인덱스·제약 추가, 엔티티 추가·수정에 따른 스키마 변경을 할 때 사용한다.
---

# 마이그레이션 작성

규칙의 상세 내용은 `.claude/rules/migration.md`(원문 `docs/CONVENTION.md` 8절)를 따른다. 이 스킬은 작성 절차다.

## 절차

1. **변경 내용을 확인한다.** 어느 모듈(도메인)의 어떤 테이블·컬럼을 바꾸는지, 대응하는 엔티티가 무엇인지 파악한다. 불분명하면 사용자에게 묻는다.
2. **기존 파일을 수정하지 않는다.** 이미 커밋된 마이그레이션은 건드리지 않고 항상 새 파일을 추가한다.
3. **버전을 정한다.** `date +%Y%m%d%H%M`으로 현재 시각 12자리를 얻는다. `git fetch` 후 `main`과 현재 브랜치의 마이그레이션 중 가장 큰 버전보다 뒤인지 확인한다.
   ```bash
   ls src/main/resources/db/migration | sort | tail -3
   git ls-tree --name-only origin/main src/main/resources/db/migration/ | sort | tail -3
   ```
4. **파일명을 짓는다.** `V{버전}__{모듈}__{동작_설명}.sql`. 구분자는 밑줄 두 개이고 설명은 `create_*_table`, `add_*_to_*`, `add_index_*`처럼 동작으로 시작한다.
5. **SQL을 작성한다.** 아래 템플릿을 따른다.
6. **엔티티와 맞춘다.** `ddl-auto`가 `validate`이므로 엔티티의 컬럼명·타입·nullable이 SQL과 일치해야 한다.
7. **검증한다.** 통합 테스트가 Testcontainers로 마이그레이션을 적용하므로 실행해 확인한다.
   ```bash
   ./gradlew test --tests '*IntegrationTest'
   ```

## 템플릿

```sql
CREATE TABLE cart_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    cart_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL COMMENT '상품 모듈 ID',
    quantity INT NOT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_cart_item_cart_id_product_id UNIQUE (cart_id, product_id),
    CONSTRAINT fk_cart_item_cart_id FOREIGN KEY (cart_id) REFERENCES cart (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

## 체크리스트

- [ ] 테이블 이름은 단수형 snake_case이고 엔티티 이름과 대응한다.
- [ ] `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4`를 명시했고 테이블에 `COLLATE`를 지정하지 않았다.
- [ ] 모든 제약·인덱스에 `{uk|ck|fk|idx}_{테이블}_{컬럼들}` 이름이 있다. 컬럼 이름을 줄이지 않았다.
- [ ] 같은 모듈 안의 연관에는 외래키가 있고, 다른 모듈의 ID에는 외래키가 없다.
- [ ] 이름만으로 의미가 불분명한 컬럼(다른 모듈 ID, 단위, 상태·플래그)에 `COMMENT`가 있고, `id`·`created_at` 같은 자명한 컬럼에는 없다.
- [ ] 키워드는 대문자, 이름은 소문자 snake_case이고, 여는 괄호는 같은 줄, 컬럼은 4칸 들여쓰기다.
- [ ] 엔티티를 추가·수정했다면 대응하는 이 파일이 함께 있다.
