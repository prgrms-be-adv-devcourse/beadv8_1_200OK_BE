---
issue: 376
module: common
summary: 모든 흐름의 진입점(HTTP, 스케줄러, 배치)에서 Snowflake traceId를 발급해 MDC에 두는 추적 컨텍스트를, 모듈 코드를 바꾸지 않고 common에 만들었다.
status: 확정
date: 2026-11-06
paths: [common/src/main/java/com/ok/common/trace, common/build.gradle.kts, docs/DEVELOPMENT.md]
related: [375, 377, 378]
---

> 이 문서는 #376 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

에픽 #375(`docs/design/order-flow-tracking.md` §5)의 첫 단계다. 흐름이 HTTP뿐 아니라 `@Scheduled`와 Spring Batch에서도 시작하므로, 모든 수집 지점이 읽을 공통 MDC 컨텍스트가 필요하다. 완료 조건은 "모듈 코드를 바꾸지 않고 동작한다"이다.

범위 밖: 모듈 간 전파(#377), JSON 인코딩·`HTTP_IN` 로그·`module` 값 채우기(#378). 이번에는 MDC 키 상수만 둔다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 패키지 | `com.ok.common.trace`, `com.ok.common.config`를 참조하지 않음 | `security`·`aws`가 자기 `*Config`를 두는 선례가 있다. ArchitectureTest의 패키지 순환 규칙은 예외 없이 실패하므로 `config`와 양방향 참조를 만들지 않는다 |
| `@Scheduled` 진입 | Micrometer `ObservationHandler<ScheduledTaskObservationContext>`의 `onScopeOpened`/`onScopeClosed` | Spring이 작업 실행 스레드에서 범위를 열고 닫아 MDC(스레드별) 짝이 맞고, `@Scheduled`에만 걸린다 |
| Batch 진입 | common의 `BeanPostProcessor`가 `AbstractJob`에 `JobExecutionListener`를 등록 | Boot 4와 Batch 6에는 전역 리스너 설정이 없다(6.0.5 소스에서 찾지 못했다). `BatchObservabilityBeanPostProcessor`가 같은 방식이다 |
| Batch 의존 | common에 `compileOnly`, 설정은 `@ConditionalOnClass` 중첩 클래스 | Batch는 payout에만 있다. 다른 모듈에 끌어오지 않고, Batch가 없는 앱의 기동을 막지 않는다 |
| Batch 범위 | 실행 id를 스레드별 스택에 두고 `afterJob`은 id가 맞을 때만 닫음 | Job은 호출 스레드(스케줄러·HTTP)에서 동기로 돈다. `AbstractJob`은 `beforeJob` 없이 `afterJob`만 부르는 경로가 있다 |
| 범위 닫을 때 | 진입 전 MDC 전체로 복원 | 스레드 재사용에서 값이 새지 않고, 기존 `MdcTaskDecorator`의 되돌리기와 겹치지 않는다 |
| 범위 열 때 | 이전 MDC를 비우고 traceId만 넣음 | 새 흐름이 이전 흐름의 orderId를 물려받으면 로그가 엉뚱한 주문에 묶인다 |
| `TraceContext` 형태 | 정적 유틸 | 상태가 스레드 전역 MDC에 있고 주입할 협력 객체가 없다 |
| 필터 순서 | Spring Security 필터(-100)보다 앞인 -200 | 401·403은 Security 필터에서 끝나 뒤에 두면 거부 응답에 traceId가 없다. 순서를 0으로 바꾸면 테스트가 실패하는 것을 확인했다 |
| `X-Trace-Id` 이어받기 | 숫자 1~18자리만 받고 나머지는 무시해 새로 발급 | 외부 값으로 로그가 오염되는 것을 막는다. 18자리는 long에 항상 들어가 범위 검사가 필요 없다 |
| 발급기 | 41비트 시각(기준 2026-01-01Z) + 10비트 노드 + 12비트 시퀀스, 공용 `Clock` 주입 | `Clock` 빈이 둘이면 앱이 시작되지 않는다. 시각이 앞이라 나중 값이 더 크고 값으로 발급 시각을 역산한다 |
| 시계 역행 | 예외 없이 마지막 시각을 유지하고 시퀀스를 올림. 소진되면 시각을 1ms 앞당김 | traceId는 로그용이라 발급이 멈추는 것보다 시각이 잠시 어긋나는 쪽이 낫다 |
| 설정 | `trace.node-id`, 기본값 0, 새 yaml 없음 | 새 yaml은 `spring.config.import` 10곳을 고쳐야 한다. 인스턴스가 하나라는 가정이다 |

## 버린 대안

- **`@Scheduled`:** `TaskDecorator` 빈은 사용자 작업이 아니라 예약 콜백을 감싸 대상 클래스를 모르고, `taskScheduler`의 다른 작업에도 걸린다. cron 재예약이 실행 스레드에서 `decorate`를 다시 불러 `MdcTaskDecorator`가 MDC를 다음 실행으로 넘길 위험이 있다(추론, 테스트하지 않았다). AOP는 aspectjweaver가 클래스패스에 없을 가능성이 높아 의존성을 더해야 한다. `ScheduledAnnotationBeanPostProcessor`를 바꾸는 방법은 내부 빈 교체라 깨지기 쉬워 버렸다.
- **Batch:** `JobOperator`를 감싸면 `start` 호출 단위라 `restart`를 놓친다. payout에 한 줄 추가하면 "모듈 코드 무수정" 조건을 어긴다.
- **필터 안의 `/internal` 복원:** #377에서 한다. 이번에는 필터가 traceId만 다룬다.

## 건드릴 때 주의

- 범위를 열고 닫는 코드는 `TraceContext.open()`과 `Scope.close()`만 쓴다. MDC를 직접 `clear()`하면 바깥 흐름의 값이 사라진다.
- `ScheduledTraceObservationHandler`는 `ObservationRegistry`가 있어야 불린다. Observation을 끄거나 레지스트리가 없는 슬라이스 테스트에서는 `@Scheduled`에 traceId가 붙지 않는다.
- `TraceFilter`는 `OncePerRequestFilter`라 `/error` 디스패치에서는 다시 실행되지 않는다. (미확인) 처리되지 않은 예외로 `/error`로 가는 응답에 헤더가 남는지는 테스트하지 않았다.
- 발급값은 2033년 중반부터 19자리가 되어 헤더 검증(18자리)에 걸린다. 그 전에 `{1,19}`와 long 파싱 검사로 넓힌다.
- (미확인) 실제 Tomcat에 monolith를 띄워 필터 순서를 확인하지 않았다. MockMvc와 순서 변이로만 확인했다.
- common 밖에서 `AbstractJob`을 직접 참조하는 설정을 만들면 Batch가 없는 모듈이 기동하지 못한다. `@ConditionalOnClass` 중첩 클래스 안에서만 쓴다.
- 새 traceId를 발급하는 진입점을 추가하면(예: 외부 콜백) 같은 `TraceContext.open()`을 쓴다. 키 이름은 로그 스키마(§6.2)와 같아야 한다.
