#!/usr/bin/env python3
"""Đẩy toàn bộ source lên GitHub bằng REST API (không cần git/curl).

Dùng khi git CLI hoặc curl bị lỗi trên máy. Tạo 1 commit duy nhất để chỉ chạy 1 workflow.
"""
from __future__ import annotations

import base64
import json
import os
import urllib.error
import urllib.request
from pathlib import Path

TOKEN = os.environ["GITHUB_TOKEN"].strip()
OWNER = os.environ.get("GITHUB_USERNAME", "Thaiduytan89").strip()
REPO = os.environ.get("REPO_NAME", "tro-ly-ao").strip()
ROOT = Path(__file__).resolve().parent

HEADERS = {
    "Authorization": f"Bearer {TOKEN}",
    "Accept": "application/vnd.github+json",
    "User-Agent": "apk-push/1.0",
    "X-GitHub-Api-Version": "2022-11-28",
}


def api(method: str, path: str, payload: dict | None = None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        "https://api.github.com" + path,
        data=data,
        headers=HEADERS,
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as res:
            body = res.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as err:
        detail = err.read().decode(errors="replace")[:600]
        raise SystemExit(f"❌ {method} {path} -> HTTP {err.code}\n{detail}") from err


def collect_files() -> dict[str, Path]:
    skip_dirs = {".git", "__pycache__", ".venv", ".deps", "build", ".gradle"}
    files: dict[str, Path] = {}
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        if any(part in skip_dirs for part in path.relative_to(ROOT).parts):
            continue
        if path.name.endswith((".pyc", ".apk", ".keystore")) or path.name in {"local.properties", ".env"}:
            continue
        if ".github/workflows/" in path.as_posix() and os.environ.get("ALLOW_WORKFLOW") != "1":
            continue
        files[path.relative_to(ROOT).as_posix()] = path
    return files


def main() -> None:
    repo = api("GET", f"/repos/{OWNER}/{REPO}")
    branch = repo.get("default_branch", "main")
    print(f"→ Repo: {OWNER}/{REPO} (branch: {branch})")

    files = collect_files()
    print(f"→ Tìm thấy {len(files)} file để đẩy")

    # Bootstrap: repo vừa tạo chưa có commit nào -> tạo commit đầu bằng 1 file.
    bootstrap = "README.md"
    content = files[bootstrap].read_text(encoding="utf-8")
    existing = api("GET", f"/repos/{OWNER}/{REPO}/contents/{bootstrap}?ref={branch}")
    bootstrap_payload = {
        "message": "Bootstrap repository",
        "content": base64.b64encode(content.encode()).decode(),
        "branch": branch,
    }
    if existing.get("sha"):
        bootstrap_payload["sha"] = existing["sha"]
    api("PUT", f"/repos/{OWNER}/{REPO}/contents/{bootstrap}", bootstrap_payload)
    print("→ Đã tạo commit khởi tạo")

    ref = api("GET", f"/repos/{OWNER}/{REPO}/git/ref/heads/{branch}")
    base_commit_sha = ref["object"]["sha"]

    tree_entries = []
    for name, path in files.items():
        raw = path.read_bytes()
        try:
            content = raw.decode("utf-8")
            entry = {"content": content}
        except UnicodeDecodeError:
            entry = {
                "content": base64.b64encode(raw).decode(),
                "encoding": "base64",
            }
        tree_entries.append(
            {
                "path": name,
                "mode": "100644",
                "type": "blob",
                **entry,
            }
        )
        print(f"   + {name}")

    tree = api(
        "POST",
        f"/repos/{OWNER}/{REPO}/git/trees",
        {"tree": tree_entries},
    )
    commit = api(
        "POST",
        f"/repos/{OWNER}/{REPO}/git/commits",
        {
            "message": "Bản đầu: trợ lý ảo tiếng Việt (Android + FastAPI)",
            "tree": tree["sha"],
            "parents": [base_commit_sha],
        },
    )
    api(
        "PATCH",
        f"/repos/{OWNER}/{REPO}/git/refs/heads/{branch}",
        {"sha": commit["sha"], "force": False},
    )
    print(f"✅ Đã đẩy xong, commit {commit['sha'][:8]}")
    print(f"   https://github.com/{OWNER}/{REPO}/actions")


if __name__ == "__main__":
    main()
