---
name: stacked-pr
description: 서로 의존하는 PR을 스택(브랜치를 부모-자식으로 쌓은 구조)으로 관리한다. 스택 보기, 새 브랜치 쌓기, 앞 브랜치가 바뀐 뒤 뒤 브랜치 다시 쌓기(restack), PR 올리기(항상 draft), 앞 PR이 머지된 뒤 정리(sync)를 git과 gh만으로 한다. "스택 PR", "PR 쌓기", "restack", "스택 정리", "이 PR 위에 이어서 작업"이라는 요청에 쓴다.
---

# 스택 PR 관리

앞 작업이 있어야 뒤 작업을 할 수 있어서, 앞 PR의 리뷰를 기다리는 동안 그 위에 이어서 작업해야 할 때 쓴다. Graphite 같은 외부 서비스 없이 `git`과 `gh`만 쓰므로 GitHub 조직에 앱을 설치할 필요가 없다. 서로 독립인 작업은 스택으로 만들지 않고 각각 `dev`에서 시작한다.

원문 규칙은 `docs/CONVENTION.md` 11절이다. 브랜치 이름과 커밋은 `commit` 스킬, PR 제목·본문은 `pr` 스킬을 따른다. 이 스킬은 스택을 쌓고 유지하는 절차만 담는다.

## 이 프로젝트의 전제

- trunk(스택의 맨 아래 기준)는 `dev`다.
- 스택의 각 브랜치도 `<유형>/<이슈번호>` 이름을 쓰고 **자기 이슈 번호**를 가진다(예: `feature/278` → `feature/279`). 커밋 메시지의 `Ref:`도 자기 이슈를 가리킨다.
- 이 프로젝트는 PR을 **squash merge**한다. 그래서 앞 PR이 머지되면 뒤 브랜치에 앞 PR의 옛 커밋이 남아 있어 그냥 rebase하면 충돌한다. 5번 sync가 이것을 처리한다.
- GitHub 이슈와 PR은 읽기만 하는 것이 기본이다. PR 생성(4번)은 사용자가 명시적으로 요청할 때만 하고, **항상 draft**로 만든다. 이슈 생성은 이 스킬의 일이 아니다.

스택의 부모 관계는 **PR의 base 브랜치**가 진실의 원천이다. PR이 아직 없는 브랜치는 `git config`에 부모를 적어 보완한다.

| 설정 키 | 의미 |
|---|---|
| `branch.<이름>.stackParent` | 부모 브랜치(맨 아래는 `dev`) |
| `branch.<이름>.stackBase` | 마지막으로 쌓거나 restack한 시점의 부모 tip 커밋. restack 때 "내 커밋은 여기부터"의 기준이다 |
| `stack.trunk` | trunk 브랜치(선택). 없으면 `origin/HEAD`, 그다음 `dev`, `main`, `master` 순으로 찾는다 |

이 설정은 각자의 로컬 `.git/config`에만 저장되고 커밋되지 않는다.

## 지켜야 할 것

- 작업 전에 `git status --porcelain`이 비어 있어야 한다. 아니면 멈추고 사용자에게 알린다(커밋·stash는 사용자가 정한다).
- `main`과 `dev`에는 커밋·푸시·`reset`·`rebase`를 하지 않는다. 푸시는 스택 브랜치에만, `--force-with-lease`로만 한다(`--force` 금지).
- 커밋은 `commit` 스킬대로 사용자 승인 후에만 한다. 브랜치 이름과 커밋 메시지는 `commit` 스킬 규칙을 따른다.
- rebase가 충돌하면 풀려고 시도하지 말고 `git rebase --abort` 한 뒤 충돌 파일과 브랜치를 사용자에게 보고한다.
- `gh`는 `env -u GITHUB_TOKEN gh ...`로 호출한다.
- PR 본문은 `--body-file`로 넘기고 인라인 `--body`는 쓰지 않는다. PR 제목·본문 형식은 `pr` 스킬(`.github/PULL_REQUEST_TEMPLATE.md`)을 따르고 AI 표기를 넣지 않는다.
- ready 전환, 머지, 승인, 이슈·PR 닫기, 댓글, 라벨 변경은 하지 않는다. 사용자가 요청할 때만 한다.
- 명령을 실행하기 전에 무엇을 할지 짧게 알린다. 되돌리기 어려운 것(force-with-lease 푸시, 브랜치 삭제)은 대상 브랜치 목록을 보여 주고 진행한다.
- **머지는 항상 맨 아래 PR부터**다. 맨 아래가 아닌 PR을 먼저 머지하자는 요청이 오면 하지 말고, 그 PR의 base가 앞 브랜치인지 확인해 경고한다(base가 `dev`로 잘못 잡힌 PR을 먼저 머지하면 앞 PR의 변경이 리뷰 없이 `dev`에 함께 들어간다).

## 1. 스택 보기

```bash
python3 "$(git rev-parse --show-toplevel)/.claude/skills/stacked-pr/scripts/stack.py"
```

`dev`부터 부모-자식 트리를 PR 상태(`draft`, `open`, `merged`)와 함께 보여 준다. 부모보다 뒤처진 브랜치는 `restack 필요`, 그 아래 자식은 `상위 restack 뒤 필요`로 표시한다. 다른 작업 전에 먼저 실행해서 현재 상태를 파악한다.

## 2. 쌓기

현재 브랜치(부모) 위에 새 브랜치를 만든다.

```bash
git switch -c <새 브랜치> <부모 브랜치>
git config branch.<새 브랜치>.stackParent <부모 브랜치>
git config branch.<새 브랜치>.stackBase "$(git rev-parse <부모 브랜치>)"
```

맨 아래 브랜치의 부모는 `dev`다. 커밋은 `commit` 스킬로 사용자 승인 후에 따로 한다.

이미 있는 브랜치를 스택에 편입할 때는 같은 두 설정만 적는다. `stackBase`는 그 브랜치가 부모에서 갈라진 커밋(`git merge-base <부모> <브랜치>`)으로 한다.

## 3. restack (앞 브랜치가 바뀐 뒤)

부모에 커밋이 추가되거나 부모가 rebase된 뒤 자식들을 새 부모 위로 옮긴다. **부모 → 자식 순서**로 한 브랜치씩 한다.

```bash
p=$(git config branch.<b>.stackParent)
base=$(git config branch.<b>.stackBase)
git rebase --onto "$p" "$base" <b>
git config branch.<b>.stackBase "$(git rev-parse "$p")"
```

`stackBase`가 없으면 `git merge-base --fork-point <부모> <b>`, 그것도 없으면 `git merge-base <부모> <b>`로 대신하고 사용자에게 알린다. 끝나면 스택 보기로 `restack 필요` 표시가 사라졌는지 확인한다. 이미 올린 PR이 있으면 4번의 푸시가 필요하다고 알린다.

## 4. 올리기 (사용자가 요청할 때만)

**부모 → 자식 순서**로 한다.

```bash
git push --force-with-lease -u origin <b>
env -u GITHUB_TOKEN gh pr create --draft --base <부모 브랜치> --head <b> --title "<제목>" --body-file <본문 파일>
```

- 맨 아래 브랜치의 base는 `dev`, 나머지는 바로 앞 브랜치다. 그래서 PR의 diff에는 그 브랜치의 변경만 보인다.
- 이미 PR이 있으면 만들지 말고 base가 부모와 다른 경우에만 `env -u GITHUB_TOKEN gh pr edit <번호> --base <부모>`를 한다.
- PR 본문의 💬 참고 사항에 "#<앞 PR> 머지 후 base를 `dev`로 변경 필요" 같은 의존 순서를 적는다.
- 끝나면 PR URL 목록을 보여 준다. ready 전환은 사용자가 한다.

## 5. sync (앞 PR이 머지된 뒤)

```bash
git fetch origin --prune
env -u GITHUB_TOKEN gh pr list --state merged --json number,headRefName,baseRefName
```

머지된 PR의 브랜치 `m`이 스택 안에 있으면, `m`의 자식 `c`마다 부모 → 자식 순서로 진행한다. squash merge든 아니든 같다.

```bash
# m의 부모를 새 부모 np로 한다(보통 origin/dev)
git rebase --onto origin/dev "$(git config branch.<c>.stackBase)" <c>
git config branch.<c>.stackParent <np>
git config branch.<c>.stackBase "$(git rev-parse origin/dev)"
git push --force-with-lease origin <c>
env -u GITHUB_TOKEN gh pr edit <c의 PR 번호> --base <np>
```

`c`의 아래 자식들은 3번 restack으로 이어서 옮긴다. 머지된 브랜치 `m`은 삭제를 제안하되 사용자가 확인한 뒤에만 `git branch -d`로 지운다(원격 브랜치는 건드리지 않는다). 끝나면 스택 보기로 결과를 보여 준다.

## 막히면

- 부모 정보가 없는 브랜치: PR이 있으면 그 PR의 base를 부모로 보고, 없으면 사용자에게 묻는다.
- `gh`를 쓸 수 없으면 스택 보기는 로컬 설정만으로 그려진다. 올리기·sync는 `gh`가 필요하다고 알린다.
- 부모 브랜치가 로컬에 없으면(`!` 줄) `git fetch origin <부모>:<부모>`로 가져올지 사용자에게 묻는다.
