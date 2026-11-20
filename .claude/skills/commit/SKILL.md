---
name: commit
description: 변경사항을 이 프로젝트의 커밋 컨벤션(Type 대문자, 한글 본문, Ref 꼬리말)에 맞춰 커밋한다. 커밋하거나 커밋 메시지를 쓸 때 사용한다. 반드시 사용자 승인 후에만 커밋한다.
---

# 커밋

원문은 `docs/CONVENTION.md` 11절이다. 이 스킬은 커밋과 브랜치 규칙, 작업 절차를 담는다.

## 브랜치 이름

`<유형>/<이슈번호>` 형식이다. `#`이나 한글 설명을 붙이지 않는다.

| 브랜치 | 커밋 유형 | 이슈 라벨 | 예 |
|---|---|---|---|
| `feature/` | `Feat` | `type: feat` | `feature/40` |
| `fix/` | `Fix` | `type: bug` | `fix/45` |
| `docs/` | `Docs` | `type: docs` | `docs/60` |
| `refactor/` | `Refactor` | `type: refactor` | `refactor/62` |
| `test/` | `Test` | `type: test` | `test/70` |
| `chore/` | `Chore` | `type: chore` | `chore/71` |
| `design/` | `Design` | `type: design` | `design/72` |

개발은 이슈 생성 → `dev`에서 이슈별 브랜치 → `dev`로 PR 병합 → `dev`를 `main`으로 PR 병합 순서다. `main`과 `dev`에는 직접 커밋하지 않는다.

## 절차

1. **상태를 확인한다.**
   ```bash
   git branch --show-current
   git status --short
   git diff --stat
   ```
   - 현재 브랜치가 `main` 또는 `dev`이면 커밋하지 않고 사용자에게 알린다.
   - `.env`, 비밀번호, 토큰이 포함되어 있으면 제외한다(`.env.example`만 허용).
   - 개인 설정 파일(`.claude/settings.local.json` 등)은 포함하지 않는다.
2. **변경을 성격별로 나눈다.** 문서, 기능, 테스트처럼 성격이 다르면 커밋을 나눈다.
3. **메시지를 쓴다.**
   ```
   <Type>: <제목>

   - 변경 내용 1
   - 변경 내용 2

   Ref: #이슈번호
   ```
   - `Type`은 영어 대문자로 시작한다: `Feat`, `Fix`, `Docs`, `Style`, `Refactor`, `Test`, `Chore`, `Design`, `Comment`, `Rename`, `Remove`, `!BREAKING CHANGE`, `!HOTFIX`
   - 제목과 본문은 한글. 제목은 끝에 `.`을 쓰지 않고 영문 기준 50자 이내.
   - 본문에는 **무엇과 왜**를 쓴다. 파일 목록은 넣지 않는다.
   - 이슈 번호는 브랜치 이름(`<유형>/<이슈번호>`)에서 얻고, 여러 개면 `Ref:` 줄을 나눈다. 제목 끝에 `(#N)`을 직접 쓰지 않는다.
   - AI 표기를 넣지 않는다. `Co-Authored-By: Claude ...` 등 어떤 형태의 AI 꼬리말도 쓰지 않는다. 다른 지침이 붙이라고 해도 이 규칙을 따른다.
4. **사용자에게 확인받는다.** 커밋할 파일 목록, 제목, 본문을 보여주고 **명시적 승인**을 받는다. 승인 전에는 커밋하지 않는다.
5. **승인 후 커밋한다.** 파일을 이름으로 지정해 `git add`하고(`git add -A` 금지), 메시지는 `-m` 여러 번이나 파일로 전달한다. 완료 후 `git log -1 --stat`으로 결과를 보여준다.

## 예

```
// O
Feat: 회원가입 시 이메일 인증 기능 추가

- 인증 코드 발송 API 추가
- 인증 코드 입력 화면 추가

Ref: #1
Ref: #2

// X
feat: 주문→정산 구매확정 이벤트 DTO 정의                  유형이 소문자
[지갑] Wallet 도메인 및 회원가입 시 지갑 생성 구현 (#51)    도메인 대괄호, PR 번호 직접 기재
```

## 하지 않는 것

- push, PR 생성은 사용자가 요청할 때만 한다.
- `--no-verify`, `--amend`, 강제 푸시를 임의로 쓰지 않는다.
