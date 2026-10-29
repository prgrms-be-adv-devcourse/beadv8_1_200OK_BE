#!/usr/bin/env python3
"""스택(부모-자식으로 쌓은 브랜치와 PR)의 구조를 트리로 보여 준다. 읽기 전용이다.

기준 브랜치를 이름으로 추측하지 않는다. 부모 관계만으로 스택의 뿌리를 정한다.
- 브랜치의 부모는 열린 PR의 base 브랜치를 우선하고, PR이 없으면 `git config branch.<이름>.stackParent`를 쓴다.
- 부모가 없는 부모(어느 브랜치의 부모로만 나오는 브랜치)가 뿌리다. 뿌리가 여럿이면 트리를 여러 개 그린다.
gh를 못 쓰면(미설치, 인증 실패) PR 정보 없이 로컬 설정만으로 그린다. 표준 라이브러리만 쓴다.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys


def git(*args: str) -> tuple[int, str]:
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    return result.returncode, result.stdout.strip()


def load_prs() -> dict[str, dict] | None:
    """gh로 PR 목록을 읽는다. 인증이 안 되면 GITHUB_TOKEN을 뺀 환경으로 한 번 더 시도한다."""
    command = ["gh", "pr", "list", "--state", "all", "--limit", "200",
               "--json", "number,headRefName,baseRefName,state,isDraft"]
    environments = [os.environ, {k: v for k, v in os.environ.items() if k not in ("GITHUB_TOKEN", "GH_TOKEN")}]
    for env in environments:
        try:
            result = subprocess.run(command, capture_output=True, text=True, env=env)
        except FileNotFoundError:
            return None
        if result.returncode == 0:
            try:
                return {pr["headRefName"]: pr for pr in json.loads(result.stdout)}
            except ValueError:
                return None
    return None


def resolve_ref(name: str, local_branches: list[str]) -> str | None:
    """비교에 쓸 ref. 로컬 브랜치를 우선하고, 없으면 origin 쪽 브랜치를 쓴다. 둘 다 없으면 None."""
    if name in local_branches:
        return name
    code, _ = git("rev-parse", "--verify", "--quiet", f"refs/remotes/origin/{name}")
    return f"origin/{name}" if code == 0 else None


def pr_label(pr: dict | None) -> str:
    if pr is None:
        return "PR 없음"
    state = {"OPEN": "draft" if pr["isDraft"] else "open", "MERGED": "merged", "CLOSED": "closed"}[pr["state"]]
    return f"#{pr['number']} {state}"


def find_parents(branches: list[str], prs: dict[str, dict] | None) -> dict[str, str]:
    """{브랜치: 부모}. 열린 PR의 base가 우선이고, 없으면 stackParent 설정을 쓴다."""
    parents = {}
    for branch in branches:
        pr = (prs or {}).get(branch)
        if pr and pr["state"] == "OPEN":
            parents[branch] = pr["baseRefName"]
            continue
        code, configured = git("config", f"branch.{branch}.stackParent")
        if code == 0 and configured and configured != branch:
            parents[branch] = configured
    return parents


def main() -> None:
    code, _ = git("rev-parse", "--show-toplevel")
    if code != 0:
        sys.exit("git 저장소 안에서 실행해야 한다")
    _, listing = git("for-each-ref", "--format=%(refname:short)", "refs/heads")
    branches = listing.split("\n") if listing else []
    prs = load_prs()
    _, current = git("branch", "--show-current")

    parents = find_parents(branches, prs)
    children = {}
    for branch, parent in parents.items():
        children.setdefault(parent, []).append(branch)
    roots = sorted(set(parents.values()) - set(parents))

    def render(branch: str, prefix: str, is_last: bool, depth: int, stale_above: bool, seen: set[str]) -> list[str]:
        marker = "*" if branch == current else " "
        if depth == 0:
            note = "" if resolve_ref(branch, branches) else "  (브랜치를 찾을 수 없다)"
            lines = [f"{marker} {branch}{note}"]
            stale = False
        else:
            notes = [pr_label((prs or {}).get(branch))]
            parent_ref = resolve_ref(parents[branch], branches)
            stale = False
            if parent_ref is None:
                notes.append("부모 브랜치를 찾을 수 없다")
            else:
                _, ahead = git("rev-list", "--count", f"{parent_ref}..{branch}")
                notes.append(f"+{ahead or '?'}")
                code, _ = git("merge-base", "--is-ancestor", parent_ref, branch)
                stale = code != 0
                if stale:
                    notes.append("restack 필요")
                elif stale_above:
                    notes.append("상위 restack 뒤 필요")
            connector = "└─" if is_last else "├─"
            lines = [f"{prefix}{connector}{marker}{branch}  ({', '.join(notes)})"]
        kids = sorted(k for k in children.get(branch, []) if k not in seen)
        for i, kid in enumerate(kids):
            child_prefix = prefix + ("   " if is_last or depth == 0 else "│  ")
            lines += render(kid, child_prefix, i == len(kids) - 1, depth + 1,
                            stale_above or stale, seen | {branch})
        return lines

    if not roots:
        print("스택이 없다. 브랜치를 쌓으면 여기에 나타난다.")
    for root in roots:
        print("\n".join(render(root, "", True, 0, False, set())))
    if prs is None:
        print("(gh를 쓸 수 없어 PR 정보 없이 로컬 설정만으로 그렸다)")


if __name__ == "__main__":
    main()
