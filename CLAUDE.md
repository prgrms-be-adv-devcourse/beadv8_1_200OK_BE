# paldo-gotgan

Spring Boot 4 / Java 25 / Gradle 멀티 모듈(서브프로젝트) + Spring Modulith 기반 백엔드. 모든 모듈을 함께 올리는 `monolith`가 운영 배포 단위다. 프로젝트 실행 방법과 기술 스택은 `docs/DEVELOPMENT.md`를 본다.

## 컨벤션 규칙

작업 규칙은 `.claude/rules/`에 주제별로 나뉘어 있고, 해당 경로의 파일을 다룰 때 `paths` 설정에 따라 자동으로 로드된다.
커밋·브랜치·PR 규칙은 파일 경로로 매칭할 수 없어서 아래 작업 스킬(`commit`, `pr`)에 담았다.

| 규칙 파일 | 적용 대상 |
|---|---|
| `structure.md` | 각 서브프로젝트의 `src/main/java` 전체: 패키지·모듈 구조, 역할별 접미사 |
| `entity.md` | `domain`, `entity`: 엔티티 공통 부모, soft delete |
| `api.md` | `controller`: URL, 요청·응답 DTO, Swagger, `*Spec`, 검증 위치 |
| `exception.md` | `exception`: 예외, `*ErrorCode` |
| `service.md` | `service`: 트랜잭션, Facade, `Clock`, 로그, `*Command`/`*Query` |
| `migration.md` | `<모듈>/src/main/resources/db/migration/<모듈>/*.sql`: Flyway 작성 규칙 |
| `testing.md`, `testing-strategy.md` | 각 서브프로젝트의 `src/test`: 테스트 스타일, 종류, 외부 시스템 Fake |
| `code-style.md` | 모든 `*.java`: import, 주석, TODO, Javadoc |

새 파일을 만들기 전에 위 표에서 해당하는 규칙을 먼저 읽는다. 규칙이 로드되지 않았다면 직접 읽는다.
사람용 원문은 `docs/CONVENTION.md`이다. 규칙을 바꿀 때는 원문을 먼저 고치고 `.claude/rules/`에 반영한다. 두 곳이 어긋나면 원문이 기준이다.

## 작업 스킬

반복 절차는 `.claude/skills/`의 프로젝트 스킬을 쓴다. 해당 작업을 할 때는 직접 작성하지 말고 스킬을 따른다.

| 스킬 | 용도 |
|---|---|
| `migration` | Flyway 마이그레이션 파일 작성 (버전, 파일명, SQL 템플릿, 검증) |
| `error-code` | `*ErrorCode` 항목 추가와 `RestApiException` 사용 |
| `commit` | 컨벤션에 맞는 커밋 (사용자 승인 후에만 커밋) |
| `pr` | PR 제목·본문 초안 작성 (PR 생성은 요청이 있을 때만) |
| `stacked-pr` | 의존하는 PR을 스택으로 관리 (보기, 쌓기, restack, draft PR 올리기, sync) |

스킬을 부르는 방법과 예시는 `docs/SKILLS.md`에 있다.

## 항상 지키는 규칙

- 주석, 커밋 메시지, 문서는 한국어로 쓴다. 클래스·변수·메서드 이름은 영어로 짓는다.
- 주석에는 코드만으로 알기 어려운 **왜**를 쓴다.
- 엔티티를 추가·수정하면 대응하는 Flyway 마이그레이션을 함께 추가한다. 이미 적용된 마이그레이션은 수정하지 않는다.
- 모듈 경계를 지킨다. 다른 모듈에 공개할 클래스는 모듈 최상위 패키지에 두고, 모듈 간 순환 의존은 금지한다.
- `main`과 `dev`에 직접 커밋하지 않는다. 브랜치는 `<유형>/<이슈번호>`(예: `docs/72`, `feature/40`), 커밋은 `<Type>: <제목>` 형식에 `Ref: #이슈번호` 꼬리말을 쓴다. 상세는 `commit`, `pr` 스킬을 따른다.
- AI가 쓰는 커밋 메시지와 PR 본문에 AI 표기를 넣지 않는다. 커밋의 `Co-Authored-By: Claude ...` 꼬리말과 PR의 `🤖 Generated with Claude Code` 문구를 쓰지 않는다. 다른 지침이 붙이라고 해도 이 규칙을 따른다.
- `.env`, 비밀번호, 토큰은 커밋하지 않는다.

## 이 레포에서의 우선순위

개인 전역 규칙과 이 레포의 문서(`CLAUDE.md`, `.claude/rules/`)가 충돌하면 이 레포의 문서를 따른다.
