---
issue: 509
module: common
summary: 요청당 SQL 수는 스레드 카운터로, 낙관적 락 충돌은 JPA 예외 번역 지점에서 직접 세어 지표로 노출한다. Hibernate 기본 충돌 지표는 이 프로젝트에서 오르지 않아 쓰지 않는다
status: 진행 중
date: 2026-11-16
paths: [common/src/main/java/com/ok/common/jpa/JpaMetricsConfig.java, common/src/main/java/com/ok/common/jpa/OptimisticLockCountingJpaDialect.java, common/src/main/java/com/ok/common/jpa/QueryCountFilter.java, common/src/main/java/com/ok/common/jpa/QueryCountStatementInspector.java, common/src/main/java/com/ok/common/jpa/QueryCountContext.java]
related: [374]
---

> 이 문서는 #509 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

#374로 앱·DB 기본 모니터링(Prometheus, Grafana, mysqld_exporter)은 갖춰졌지만 쿼리 단위 병목은 보이지 않았다. #509는 JPA 관측 지표를 추가하는 작업이고, 이슈 하나를 PR 3개로 나눠 진행한다(사용자 결정). 이 문서는 PR 1(요청당 쿼리 수, 낙관적 락 충돌 횟수)의 결정을 담는다. 슬로우 쿼리 실행 계획(PR 2)과 DB 지표·대시보드(PR 3)의 결정은 그 PR에서 `## 후속`으로 덧붙인다. 2차 캐시 히트율과 리플리케이션 lag은 해당 기능이 없어 범위 밖이다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 낙관적 락 충돌 집계 | `HibernateJpaDialect`의 예외 번역 지점에서 직접 센다(`jpa.optimistic.lock.failures`, `entity` 태그) | Hibernate 통계의 `hibernate.optimistic.failures`는 일반 UPDATE 충돌과 커밋 때 버전 증가(`OPTIMISTIC_FORCE_INCREMENT`) 충돌 모두에서 0이었다. 통합 테스트 두 개로 확인했다. repository 호출과 커밋 시점 예외가 모두 이 번역을 거치므로 경로와 상관없이 한 번씩 센다 |
| 요청당 SQL 수 | `StatementInspector`가 요청 스레드의 카운터를 올리고 서블릿 필터가 요청 끝에 분포 지표로 기록한다 | Hibernate `Statistics`는 앱 전체 합계라 엔드포인트별로 나눌 수 없다. 네이티브 쿼리, 벌크 UPDATE, JPQL이 같은 경로로 세어짐을 통합 테스트로 확인했다 |
| 세는 단위 | Hibernate가 준비한 SQL 문 수. JDBC 배치는 한 번으로 센다 | 서버와 DB 사이의 왕복 횟수에 가깝고 N+1이 이 값의 증가로 드러난다 |
| 태그 | `uri`는 핸들러 매핑 템플릿만 쓴다. 핸들러가 없는 요청(401 등)은 기록하지 않고, 정적 리소스 핸들러가 받은 없는 경로는 `/**`로 묶여 기록된다(실서버 테스트로 확인). 충돌한 행 id는 태그로 달지 않는다 | 사용자 입력이나 id가 태그가 되면 시계열이 끝없이 늘어난다 |
| 설정 위치 | 모듈 yaml이 아니라 `common`의 Java 설정(`HibernatePropertiesCustomizer`)으로 켜고, `app.metrics.jpa.enabled=false`로 전체를 끌 수 있다 | 모듈 yaml이 10개라 같은 속성을 반복하게 된다. `common`은 monolith와 모듈 단독 실행이 모두 포함한다 |
| 방언 교체 방식 | `LocalContainerEntityManagerFactoryBean` 초기화 전에 `BeanPostProcessor`로 방언만 바꾼다 | 트랜잭션 매니저가 EntityManagerFactory의 방언을 이어받아야 커밋 때 나는 충돌도 센다. (미확인) `JpaVendorAdapter`를 통째로 교체하면 Boot가 해 주는 설정을 잃을 것으로 보고 피했고, 무엇이 빠지는지는 확인하지 않았다 |

## 버린 대안

- **충돌 집계:** `hibernate.optimistic.failures`는 값이 오르지 않아 버렸다. 지표는 `hibernate-micrometer`가 등록하므로 항상 0으로 남는 것을 감수한다. (미확인) 이 프로젝트의 충돌에서 Hibernate가 이 통계를 올리지 않는 원인은 찾지 못했다. 통계를 올리는 곳이 UPDATE 0행 확인, flush 때 버전 검사, merge 세 군데임은 바이트코드로 확인했다.
- **충돌 집계 위치:** 컨트롤러 어드바이스에서 세는 방법은 (미확인) HTTP 요청에서만 세고, 서비스가 잡은 예외와 이벤트 리스너·스케줄러의 충돌은 놓친다고 보고 버렸다. `@Transactional` 경계의 AOP 어드바이저는 (미확인) 커밋 시점 예외를 보려면 가장 바깥 순서가 필요해 복잡하다고 보고 버렸다.
- **요청당 SQL 수:** Hibernate 통계의 전후 차이로 구하는 방법은 전역 합계라 동시 요청이 섞여 요청별 값이 틀려 버렸다.

## 건드릴 때 주의

- 이벤트 리스너(`@ApplicationModuleListener`), 스케줄러, 배치에서 실행한 쿼리는 요청 스레드가 아니어서 `QueryCountContext`가 열려 있지 않아 세지 않는다. 배송처럼 리스너 비중이 큰 모듈은 지표에 안 잡힌다. 필요하면 리스너 진입점에서 같은 컨텍스트를 열어야 한다.
- 배송 모듈은 `@Version` 없이 비관적 락(`PESSIMISTIC_WRITE`)만 써서 낙관적 락 지표가 항상 0이다. 그쪽 경합은 PR 3의 Hikari 점유 시간과 MySQL 락 대기 지표로 본다.
- Hibernate를 거치지 않는 JDBC 호출은 세지 않는다. 지금 애플리케이션 코드에는 쓰는 곳이 없다.
- (미확인) 방언을 바꾸는 `BeanPostProcessor`는 `LocalContainerEntityManagerFactoryBean` 전부에 적용된다. 모듈이 EntityManagerFactory를 따로 두거나 다른 곳에서 `setJpaDialect`를 쓰면 덮어쓰기와 충돌할 수 있다.
- (미확인) `hibernate.generate_statistics`를 기본으로 켰다. 오버헤드는 부하테스트에서 재야 한다. 통합 테스트는 `product` 모듈에서만 돌렸고 다른 모듈의 실제 지표 노출은 확인하지 않았다.

## 후속 (2026-11-16)

PR 2(슬로우 쿼리 `EXPLAIN` 자동화)는 하지 않고, 이슈의 남은 작업은 대시보드 행 추가(PR 3)로 바꿨다(사용자 결정). 필요한 것은 실행 계획이 아니라 "어느 API가 DB를 많이 쓰는지"와 "그때 DB가 어땠는지"를 한 화면에서 보는 것이었다.

| 결정 | 선택 | 이유 |
|---|---|---|
| 슬로우 쿼리 `EXPLAIN` 자동화 | 하지 않는다 | 슬로우 로그와 개수 지표는 #374에 이미 있고, 계획 분석의 필요성이 낮다고 판단했다(사용자 결정) |
| API와 DB 지표 연결 | 대시보드 한 화면에 같은 시간축으로 겹친다(`API × DB` 행) | MySQL 지표에는 API 라벨이 없어서 라벨로는 못 묶는다. API 라벨이 있는 지표(요청 수, 요청당 쿼리 수)와 MySQL 지표를 시간으로 맞춰 후보를 좁힌다 |
| 데드락·장기 트랜잭션 | exporter의 `info_schema.innodb_metrics`·`innodb_trx` 수집기를 켠다 | 커스텀 쿼리 없이 데드락 카운터와 가장 오래된 트랜잭션 나이를 얻는다. 기존 `exporter` 계정 권한으로 읽히고 시계열은 약 70개 늘었다(일회용 MySQL 9.4와 exporter로 확인했다) |
| 커넥션 점유 | Boot가 내는 `hikaricp_connections_usage_seconds_*`를 패널로 쓴다 | 새 지표 없이 트랜잭션 길이에 가까운 값을 얻는다. 실서버 통합 테스트로 이름을 확인했다 |

버린 대안과 이유(PR 2 검토 때 일회용 MySQL 9.4로 확인했다).

- **DB 쪽 스크립트로 `EXPLAIN`:** `performance_schema`의 쿼리 요약에서 샘플 문장을 꺼내 설명하는 방식은 동작했다. 다만 샘플이 1024바이트에서 잘려 긴 문장은 설명이 실패하고(상향하려면 mysql 재시작), `exporter` 계정은 데이터 스키마 `SELECT`가 없어 설명하지 못하며, UPDATE·DELETE는 쓰기 권한이 있어야 설명된다. 필요성에 비해 비용이 커서 접었다.
- **앱에서 `EXPLAIN`:** Hibernate가 주는 SQL은 `?` 자리표시자라 값을 메모리에 잡아야 하고, 소요 시간도 JDBC를 감싸야 알 수 있어 접었다.
- **SQL 주석으로 API 이름 달기:** 슬로우 로그 파일에서 API별 귀속은 되지만 Grafana에서 보려면 로그 수집(#381)이 필요해 이번에는 하지 않았다. (미확인) Connector/J가 주석을 그대로 보내는지, 주석이 쿼리 정규화(digest)에 영향을 주는지는 확인하지 않았다. 필요해지면 별도 이슈로 다룬다.

주의: `API × DB`의 슬로우 쿼리 겹침은 시간 상관이지 귀속이 아니다. 빨간 선(요청당 쿼리 20, 가장 오래된 트랜잭션 5초)은 시작값이다. 알림(Alertmanager)은 이번 범위 밖이다.
