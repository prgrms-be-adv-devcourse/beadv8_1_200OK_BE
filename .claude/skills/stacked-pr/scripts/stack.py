#!/usr/bin/env python3
"""스택(부모-자식으로 쌓은 브랜치와 PR)의 구조를 트리로 보여 준다. 읽기 전용이다.

부모 관계는 PR의 base 브랜치를 우선하고, PR이 없으면 `git config branch.<이름>.stackParent`를 쓴다.
gh를 못 쓰면(미설치, 인증 실패) PR 정보 없이 로컬 설정만으로 그린다. 표준 라이브러리만 쓴다.
"""
import json
import os
import subprocess
import sys


def git(*args, check=False):
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        sys.exit(f"git {' '.join(args)} 실패: {result.stderr.strip()}")
    return result.returncode, result.stdout.strip()


def load_prs():
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


def find_trunk(local_branches):
    code, configured = git("config", "stack.trunk")
    if code == 0 and configured:
        return configured
    code, head = git("symbolic-ref", "--short", "refs/remotes/origin/HEAD")
    if code == 0 and head.startswith("origin/"):
        return head[len("origin/"):]
    for name in ("dev", "main", "master"):
        if name in local_branches:
            return name
    return None


def pr_label(pr):
    if pr is None:
        return "PR 없음"
    state = {"OPEN": "draft" if pr["isDraft"] else "open", "MERGED": "merged", "CLOSED": "closed"}[pr["state"]]
    return f"#{pr['number']} {state}"


def main():
    code, _ = git("rev-parse", "--show-toplevel")
    if code != 0:
        sys.exit("git 저장소 안에서 실행해야 한다")
    _, listing = git("for-each-ref", "--format=%(refname:short)", "refs/heads")
    branches = listing.split("\n") if listing else []
    trunk = find_trunk(branches)
    if trunk is None:
        sys.exit("trunk 브랜치를 알 수 없다. `git config stack.trunk <브랜치>`로 지정한다")
    prs = load_prs()
    _, current = git("branch", "--show-current")

    parents = {}
    for branch in branches:
        if branch == trunk:
            continue
        pr = (prs or {}).get(branch)
        if pr and pr["state"] == "OPEN":
            parents[branch] = pr["baseRefName"]
            continue
        code, configured = git("config", f"branch.{branch}.stackParent")
        if code == 0 and configured:
            parents[branch] = configured

    children = {}
    for branch, parent in parents.items():
        children.setdefault(parent, []).append(branch)

    def render(branch, prefix, is_last, depth, stale_above=False):
        lines = []
        marker = "*" if branch == current else " "
        if depth == 0:
            lines.append(f"{marker} {branch}")
        else:
            notes = [pr_label((prs or {}).get(branch))]
            parent = parents[branch]
            _, ahead = git("rev-list", "--count", f"{parent}..{branch}")
            notes.append(f"+{ahead or '?'}")
            code, _ = git("merge-base", "--is-ancestor", parent, branch)
            stale = code != 0
            if stale:
                notes.append("restack 필요")
            elif stale_above:
                notes.append("상위 restack 뒤 필요")
            connector = "└─" if is_last else "├─"
            lines.append(f"{prefix}{connector}{marker}{branch}  ({', '.join(notes)})")
        kids = sorted(children.get(branch, []))
        for i, kid in enumerate(kids):
            child_prefix = prefix + ("   " if is_last or depth == 0 else "│  ")
            lines += render(kid, child_prefix, i == len(kids) - 1, depth + 1, stale_above or (depth > 0 and stale))
        return lines

    print("\n".join(render(trunk, "", True, 0)))
    orphans = sorted(b for b, p in parents.items() if p != trunk and p not in parents and p not in branches)
    for branch in orphans:
        print(f"! {branch}: 부모 {parents[branch]} 브랜치를 찾을 수 없다")
    if prs is None:
        print("(gh를 쓸 수 없어 PR 정보 없이 로컬 설정만으로 그렸다)")


if __name__ == "__main__":
    main()
