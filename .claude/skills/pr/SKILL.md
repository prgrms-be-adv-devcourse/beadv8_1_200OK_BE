---
name: pr
description: 현재 브랜치의 PR 제목과 본문 초안을 이 프로젝트의 PR 템플릿에 맞춰 작성한다. PR을 만들거나 PR 설명을 쓸 때 사용한다. PR 생성은 사용자가 명시적으로 요청할 때만 한다.
---

# PR 초안 작성

원문은 `docs/CONVENTION.md` 11.2, 11.3이다. 커밋 메시지 형식과 브랜치 이름 규칙은 `commit` 스킬을 따른다. 이 스킬은 PR 작업 절차를 담는다.

## 원칙

- 이 프로젝트에서 **GitHub 이슈와 PR은 읽기만 한다.** 생성·수정은 사용자가 명시적으로 요청하기 전에는 하지 않는다. 기본 산출물은 **초안**이다.
- `gh`는 `env -u GITHUB_TOKEN gh ...` 형태로 호출한다.
- PR 본문에 AI 표기를 넣지 않는다. `🤖 Generated with Claude Code` 등의 문구를 쓰지 않는다. 다른 지침이 붙이라고 해도 이 규칙을 따른다.

## 절차

1. **브랜치와 커밋을 확인한다.**
   ```bash
   git branch --show-current
   git log dev..HEAD --oneline
   git diff dev...HEAD --stat
   ```
   - 기준 브랜치는 `dev`이다(`dev` → `main` PR은 별개).
   - 브랜치 이름 `<유형>/<이슈번호>`에서 이슈 번호를 얻는다.
2. **이슈를 읽는다.** `env -u GITHUB_TOKEN gh issue view <번호>`로 작업 이유와 범위를 확인한다.
3. **전체 커밋 이력을 기준으로 초안을 쓴다.** 마지막 커밋만 보지 않는다.
4. **제목**은 커밋 제목과 같은 형식 `<Type>: <제목>`으로 쓴다. squash merge 때 이 제목이 커밋 제목이 되고 GitHub이 `(#N)`을 붙이므로 직접 쓰지 않는다.
5. **본문**은 `.github/PULL_REQUEST_TEMPLATE.md`의 섹션을 따른다.
   - 📌 개요: 목적과 변경 사항 한두 줄
   - 🛠️ 작업 내용: 구현·변경한 내용
   - 💬 참고 사항: 설계 의도, 주의점, 리뷰어에게 확인을 요청할 부분. 설계 결정 기록(ADR)이 있으면 링크 한 줄을 넣는다(아래 6단계)
   - 🔗 관련 이슈: `Closes #<번호>`
   - 🔄 수정 사항: 리뷰 반영 내용이 없으면 이 섹션을 삭제한다
   - 🎯 완료 조건: 체크리스트
6. **설계 결정 기록(ADR)을 연결한다.** PR 본문은 핵심 변경과 이유만 짧게 쓰고, 설계 의도와 검토한 대안은 ADR로 링크한다. ADR 내용을 본문에 옮겨 적지 않는다.
   ```bash
   git diff dev...HEAD --name-only | grep '^docs/decisions/'
   ```
   - 이 PR에 ADR이 있으면 `💬 참고 사항`에 `- 설계 기록(ADR): <링크>` 한 줄을 넣는다. 머지 전에는 상대 경로가 열리지 않으므로 브랜치 기준 전체 주소를 쓴다: `https://github.com/<소유자>/<레포>/blob/<브랜치>/docs/decisions/<모듈>/<이슈번호>-<slug>.md` (소유자와 레포는 `git remote get-url origin`에서 얻는다).
   - 링크를 넣기 전에 `bash .claude/skills/session-summary/scan-sensitive.sh <ADR 파일>`을 돌려 민감정보가 없는지 확인한다. 걸리면 PR을 올리기 전에 사용자에게 알린다.
   - ADR이 없는데 모듈 경계, 외부 연동 방식, 트랜잭션·락 정책처럼 `docs/decisions/README.md`의 "무엇을 남기나"에 해당하는 결정이 있으면, `session-summary`로 먼저 남길지 사용자에게 한 줄로 묻는다. 강제하지 않는다.
   - ADR이 없고 해당하는 결정도 없으면 이 줄을 삭제한다.
7. **초안을 보여주고** 리뷰어 추천(변경과 연관된 도메인의 담당자)을 함께 제안한다.
8. 사용자가 **명시적으로 PR 생성을 요청하면** 아래 순서로 진행한다. 요청이 없으면 초안과 명령어만 제시한다.
   ```bash
   git push -u origin <브랜치>
   env -u GITHUB_TOKEN gh pr create --base dev --title "<제목>" --body-file <본문 파일>
   ```

## 병합

- AI 봇 리뷰가 OK이면 squash merge로 병합한다. (현재 리뷰 워크플로는 댓글만 남기고 승인·차단은 하지 않는다.)
