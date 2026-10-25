---
paths:
  - "**/src/main/java/**/domain/**/*.java"
  - "**/src/main/java/**/entity/**/*.java"
---

# 엔티티

엔티티를 만들거나 고칠 때 따르는 규칙이다. 엔티티를 추가·수정하면 Flyway 마이그레이션도 함께 추가한다(`migration.md`).
사람용 원문: `docs/CONVENTION.md` 4절.

## 스키마

- 엔티티는 `@Table(name = "...", catalog = <모듈>Schema.NAME)`으로 자기 모듈의 MySQL 스키마를 지정한다(`@Table(schema = ...)`는 MySQL에서 무시된다). 모듈 간 조인·외래키는 두지 않는다. `ArchitectureTest`가 검사한다.
- 네이티브 SQL은 `catalog`가 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다(`"INSERT INTO " + WalletSchema.NAME + ".buyer_wallet ..."`). JPQL, Spring Data, QueryDSL은 그대로 쓴다.

**이유**: 모듈이 자기 스키마에 테이블을 두면 서비스로 떼어낼 때 데이터가 이미 나뉘어 있다. catalog를 빼먹으면 로컬 테스트는 통과해도 운영에서만 실패할 수 있다.

```java
// O
@Entity
@Table(name = "buyer_wallet", catalog = WalletSchema.NAME)
public class BuyerWallet extends BaseIdAndTime { ... }

// X: schema는 MySQL에서 무시된다
@Table(name = "buyer_wallet", schema = "paldogotgan_wallet")
```

## 공통 부모·삭제

- 공통 부모는 생성·수정 시각(`createdAt`, `updatedAt`)만 가진 `BaseTime`이다. ID는 부모에 두지 않고 엔티티마다 정의한다.
- 현재 코드는 ID와 시각을 함께 가진 `BaseIdAndTime`이다. `BaseTime` 전환은 별도 작업이므로, 기존 코드를 임의로 바꾸지 않는다.
- 삭제는 soft delete로 처리하고 삭제 시각은 `deleted_at` 컬럼에 기록한다. `repository.delete(...)`로 행을 지우지 않는다.

**이유**: 생성·수정 시각은 정책상 모든 엔티티에 두므로 공통 부모로 둔다. ID 전략은 성능이나 비즈니스 요구에 따라 엔티티마다 달라질 수 있어 부모에 고정하지 않는다. 행을 지우지 않고 삭제 시각만 남기면 삭제한 데이터를 추적할 수 있다.

```java
// O
@Entity
public class Wallet extends BaseTime {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}

@Column(name = "deleted_at")
private LocalDateTime deletedAt;
```

정적 팩토리 이름, 생성 시 검증, `@Column` 표기는 아직 규칙이 없다. 기존 모듈의 방식을 따르고 임의로 통일하지 않는다.
