---
issue: 417
module: common
summary: 비동기 작업이 Boot 기본 실행기 하나를 공유하던 것을, 이름 붙인 전용 가상 스레드 실행기를 yml로 설정하는 구조로 나눈다
status: 확정
date: 2026-11-05
paths: [common/src/main/java/com/ok/common/config/AsyncConfig.java, common/src/main/java/com/ok/common/config/AsyncExecutorFactory.java, common/src/main/java/com/ok/common/config/AsyncProperties.java, common/src/main/java/com/ok/common/config/MdcTaskDecorator.java, common/src/main/resources/common-async.yaml]
related: []
---

> 이 문서는 #417 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

Spring Modulith가 앱에 `@EnableAsync`가 없으면 자동으로 켜 주므로(`EventPublicationAutoConfiguration`의 `@EnableAsync` 설정을 jar에서 확인) `@ApplicationModuleListener`는 원래 비동기로 실행된다. 그런데 이름 없는 `@Async`는 모두 Boot의 기본 실행기(`applicationTaskExecutor`, 코어 8·대기열 무제한. Boot 메타데이터로 확인) 하나를 공유한다. 느린 작업이 다른 비동기 작업을 지연시키고, 밀린 작업이 대기열에 메모리로 쌓인다. (미확인) 실제 앱에서 리스너가 이 풀의 스레드에서 도는지는 앱을 띄워 확인하지 않았다.

공유 풀을 용도별 전용 실행기로 나누고, 실행기 설정을 코드에 박지 않고 yml과 환경변수로 바꿀 수 있게 하는 것이 목표다. 모듈마다 비동기 실행기가 늘어날 예정이라 실행기를 추가하는 비용도 낮춰야 했다.

범위 밖: 미완료 이벤트의 주기적 재발행, TraceID를 만들어 MDC에 넣는 필터와 로그 패턴, 이벤트 재발행·Kafka 경계의 MDC 전파, `SecurityContext` 전파, 리스너별 실행기 지정.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 가상 스레드 적용 범위 | `virtual` 실행기에만 적용하고 `spring.threads.virtual.enabled`는 쓰지 않는다 | 전역 설정은 Tomcat 요청 스레드와 `@Scheduled`까지 바꾼다 (사용자 결정) |
| 설정 받는 방법 | 실행기 빈을 직접 등록하고 `app.async.*`를 자체 `@ConfigurationProperties`로 받는다 | 실행기에만 가상 스레드를 쓰려면 직접 만들어야 하고, Boot의 `applicationTaskExecutor`가 물러나 `spring.task.execution.*`는 적용되지 않는다. (미확인) 실제 앱에서 Boot 기본 실행기가 물러나는지는 앱을 띄워 확인하지 않았다 |
| 설정 구조 | `executors`를 이름을 키로 하는 Map으로 두고 `default-executor`를 따로 둔다 | List는 모듈 yml로 덮어쓸 때 통째로 바뀌고 환경변수를 순서 번호로 지정해야 한다. Map은 같은 이름의 항목만 필드 단위로 합쳐진다 (테스트로 확인) |
| 실행기 이름과 빈 | 하이브리드: 이름은 코드(`@Bean` 한 줄, 상수), 값은 yml, 만드는 로직은 공용 `AsyncExecutorFactory` (사용자 결정) | 모듈마다 늘어날 예정이라 설정을 읽고 옵션을 채우는 로직의 중복을 없앤다. 이름이 코드에 있어 IDE로 추적되고, 선언한 이름이 yml에 없으면 기동 때 실패한다 |
| 실행기 종류 | 실행기별 `type`: `platform`(대기열이 있는 일반 스레드 풀, `pool-size`·`queue-capacity`)과 `virtual`(작업마다 가상 스레드, `concurrency-limit`). `event`는 `platform` (사용자 결정) | 대기열이 없는 `virtual`은 상한이 차면 작업을 넘긴 요청 스레드가 기다린다(테스트로 확인). 대기열이 있는 풀은 가득 차기 전까지 즉시 반환한다(임시 테스트로 확인, 저장소에는 남기지 않음). 리스너가 요청 스레드를 막지 않게 하려고 `event`는 `platform`으로 한다 |
| 가득 찼을 때 | 거절하거나 버리지 않고 작업을 넘긴 스레드가 자리가 날 때까지 기다린다. 거절 옵션은 두지 않는다 (사용자 결정) | 거절하면 커밋 뒤 콜백에서 호출한 쪽으로 `TaskRejectedException`이 올라가 이미 커밋된 요청이 오류가 되고 같은 커밋의 다른 콜백이 실행되지 않는다(`RejectedAfterCommitTest`로 확인). 재시도는 비즈니스 로직과 무관하게 별도로 한다. 대기열 크기 1000은 측정 근거 없는 임시값이다 |
| 가상 스레드 되돌리기 | 별도 `virtual-threads` 토글 없이 `type`을 `platform`으로 바꾼다 | 작업마다 일반 스레드를 만드는 방식은 상한이 크면 스레드 OOM 위험이 있어, 되돌릴 때는 풀을 쓴다 |
| MDC | `MdcTaskDecorator`를 모든 실행기에 적용하고, MDC에 있는 값을 그대로 복사하기만 한다 (사용자 결정) | 값을 넣는 쪽(TraceID 필터)이 아직 없어 지금은 전파되는 값이 없다. 통로를 먼저 만들어 두고 TraceID 구현에서 채운다 |
| `event` 값 변경 | 환경변수(`ASYNC_EVENT_*`) 또는 `common-async.yaml` | `spring.config.import`한 파일의 값이 import한 앱 yml보다 우선한다 (임시 테스트로 확인, 저장소에는 남기지 않음) |

## 버린 대안

- **설정 방식:** Boot의 `spring.task.execution.*`는 실행기 하나만 설정할 수 있어 모듈별 여러 실행기를 못 만든다.
- **이름 등록:** yml만으로 이름까지 정해 `BeanRegistrar`와 `Binder`로 동적 등록하는 방식(Micronaut 방식)은 이름이 문자열로만 남아 `@Async` 오타가 처음 호출될 때 발견되고, 직접 만든 등록 코드와 Boot 자동 구성의 순서를 팀이 관리해야 한다. 실행기가 크게 늘면 다시 검토한다.
- **모듈별 코드 복사:** 실행기마다 설정 클래스와 빈 코드를 복사하면 중복이 쌓인다.
- **작업마다 일반 스레드(`virtual-threads: false`):** 풀 없이 작업마다 일반 스레드를 만들면 상한이 클 때 스레드 OOM 위험이 있어 `type: platform`(풀)으로 대체했다.
- **거절 옵션(`reject-tasks-when-limit-reached`):** 켜면 위 "가득 찼을 때"의 문제가 생겨 없앴다. 거절한 작업의 재시도가 필요하면 리스너가 아니라 별도 작업으로 한다.
- **서킷 브레이커로 흐름 제어:** 열렸을 때 새 작업을 어떻게 할지 다시 정해야 하고(거절이면 같은 문제), 이 프로젝트에 라이브러리가 없다. 포화의 원인인 느린 외부 호출을 막는 용도로 따로 검토한다.
- **가득 차면 버리고 주기적으로 재전송:** 요청 스레드가 막히지 않지만 재전송 스케줄러와 리스너 멱등성이 필요하다. 이후 작업으로 둔다.

## 건드릴 때 주의

- `AsyncConfigurer`는 기본 실행기를 `Executor` 타입이 아니라 **이름**으로 찾는다. `@EnableScheduling`이 만드는 `taskScheduler`도 `Executor`라서 타입으로 찾으면 후보가 둘이 되어 앱이 기동하지 못한다.
- `common`의 테스트 클래스는 다른 모듈의 테스트 클래스패스에도 올라간다. 테스트용 도우미 클래스에 `@Configuration`을 붙이면 각 모듈의 통합 테스트가 컴포넌트 스캔으로 그 빈을 등록해 기동이 실패한다.
- DB를 쓰는 실행기들의 `pool-size`·`concurrency-limit` 합계는 Hikari 풀 크기(기본 10)보다 작아야 한다. 앱이 검사하지 않는다.
- `platform`의 대기열이 가득 차면 작업을 넘긴 스레드가 기다린다. 요청 스레드가 직접 부르는 `@Async`에는 대기열이 없는 `virtual`을 쓰지 않는다. `ThreadPoolExecutor`는 대기열이 가득 차야 코어를 넘어 스레드를 늘리므로 최대 크기는 코어와 같게 둔다.
- `@Async` 이름은 문자열이 아니라 상수로 쓴다. 틀리면 처음 호출될 때 실패한다. 실행기 이름은 모듈 접두사를 붙인다(`monolith`는 빈 이름 공간이 하나다).
- `monolith`는 `application-<모듈>.yaml`을 읽지 않으므로, 모듈이 추가한 실행기 항목은 `monolith`의 `application.yaml`에도 있어야 한다. 빠지면 그 앱이 기동할 때 실패한다.
- 값은 앱 시작 때 한 번 읽는다. 바꾸면 재시작해야 한다.
