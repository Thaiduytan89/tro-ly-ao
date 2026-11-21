#!/usr/bin/env python3
"""Cập nhật file backend-url.txt trên GitHub để app tự lấy URL backend mới.

Dùng: GITHUB_TOKEN=... python3 update-backend-url.py https://...pinggy.net
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

OWNER = "Thaiduytan89"
REPO = "tro-ly-ao"
BRANCH = "main"
PATH = "backend-url.txt"


def api(method: str, url: str, payload: dict | None = None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "update-backend-url/1.0",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def main() -> None:
    if len(sys.argv) != 2 or not sys.argv[1].startswith("https://"):
        print("Dùng: python3 update-backend-url.py https://...", file=sys.stderr)
        sys.exit(1)
    url = sys.argv[1].strip()
    base = f"https://api.github.com/repos/{OWNER}/{REPO}/contents/{PATH}"

    existing = {}
    try:
        existing = api("GET", f"{base}?ref={BRANCH}")
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise

    payload = {
        "message": "Update backend URL",
        "content": base64.b64encode((url + "\n").encode()).decode(),
        "branch": BRANCH,
    }
    if existing.get("sha"):
        payload["sha"] = existing["sha"]

    api("PUT", base, payload)
    print(f"✅ Đã cập nhật {PATH} -> {url}")


if __name__ == "__main__":
    main()
