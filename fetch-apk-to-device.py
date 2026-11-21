#!/usr/bin/env python3
"""Chờ GitHub Actions build xong, tải APK về và đẩy thẳng vào Downloads qua bridge.

Cách dùng:
    export GITHUB_TOKEN="ghp_..."
    python3 fetch-apk-to-device.py

Biến tùy chọn:
    REPO_NAME=tro-ly-ao        Tên repo GitHub
    GITHUB_USERNAME=...        Tự lấy qua API nếu không set
    AUTO_FETCH=0               Không tự gọi (dùng khi chạy riêng lẻ)
    WAIT_FOR_BUILD=0           Không chờ, lấy artifact thành công gần nhất
    TIMEOUT_SECONDS=1800       Thời gian chờ tối đa
"""
from __future__ import annotations

import io
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from urllib.parse import urlparse

TOKEN = os.environ.get("GITHUB_TOKEN", "").strip()
OWNER = os.environ.get("GITHUB_USERNAME", "").strip()
REPO = os.environ.get("REPO_NAME", "tro-ly-ao").strip()
WORKFLOW = "build-apk.yml"
ARTIFACT_NAME = "tro-ly-ao-debug"
TIMEOUT = int(os.environ.get("TIMEOUT_SECONDS", "1800"))
POLL = 15


class AuthSafeRedirect(urllib.request.HTTPRedirectHandler):
    """Bỏ Authorization khi redirect sang host khác (signed URL của artifact)."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urlparse(newurl).netloc != urlparse(req.full_url).netloc:
            return urllib.request.Request(
                newurl, headers={"User-Agent": "apk-fetch/1.0"}
            )
        return super().redirect_request(req, fp, code, msg, headers, newurl)


OPENER = urllib.request.build_opener(AuthSafeRedirect)


def api(path: str):
    request = urllib.request.Request(
        "https://api.github.com" + path,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "apk-fetch/1.0",
        },
    )
    with OPENER.open(request, timeout=30) as response:
        return json.load(response)


def resolve_owner() -> str:
    if OWNER:
        return OWNER
    return api("/user")["login"]


def wait_for_success(owner: str):
    deadline = time.time() + TIMEOUT
    while time.time() < deadline:
        data = api(
            f"/repos/{owner}/{REPO}/actions/workflows/{WORKFLOW}/runs?per_page=5"
        )
        runs = data.get("workflow_runs", [])
        if not runs:
            print("⏳ Chưa có lần chạy nào — đẩy code lên main để kích hoạt build.")
        else:
            run = runs[0]
            status = run.get("status")
            conclusion = run.get("conclusion")
            if status == "completed" and conclusion == "success":
                print(f"✅ Build thành công (run #{run['id']})")
                return run
            if status == "completed":
                raise SystemExit(
                    f"❌ Build thất bại ({conclusion}). Xem log tại:\n"
                    f"   {run['html_url']}"
                )
            print(f"⏳ Đang build... ({status}/{conclusion}) — chờ {POLL}s")
        time.sleep(POLL)
    raise SystemExit("❌ Hết thời gian chờ build. Tăng TIMEOUT_SECONDS nếu cần.")


def latest_success(owner: str):
    data = api(
        f"/repos/{owner}/{REPO}/actions/workflows/{WORKFLOW}/runs?per_page=20"
    )
    for run in data.get("workflow_runs", []):
        if run.get("status") == "completed" and run.get("conclusion") == "success":
            print(f"✅ Dùng artifact của run #{run['id']}")
            return run
    raise SystemExit("❌ Chưa có lần build nào thành công.")


def download_artifact(owner: str, run_id: int) -> bytes:
    data = api(f"/repos/{owner}/{REPO}/actions/runs/{run_id}/artifacts")
    artifacts = data.get("artifacts", [])
    artifact = next(
        (item for item in artifacts if item["name"] == ARTIFACT_NAME), None
    ) or (artifacts[0] if artifacts else None)
    if artifact is None:
        raise SystemExit("❌ Không tìm thấy artifact APK trong lần build này.")

    request = urllib.request.Request(
        artifact["archive_download_url"],
        headers={"Authorization": f"Bearer {TOKEN}", "User-Agent": "apk-fetch/1.0"},
    )
    with OPENER.open(request, timeout=180) as response:
        payload = response.read()
    print(f"📦 Đã tải artifact '{artifact['name']}' ({len(payload):,} bytes)")
    return payload


def project_root() -> Path:
    configured = os.environ.get("OPENCODE_PROJECT_DIR", "").strip()
    if configured:
        return Path(configured).resolve()
    cwd = Path.cwd().resolve()
    return cwd.parent if cwd.name == "vietnamese-assistant" else cwd


def bridge_script() -> Path:
    configured = os.environ.get("BRIDGE_SCRIPT", "").strip()
    if configured:
        return Path(configured)
    config_home = Path(
        os.environ.get("OPENCODE_CONFIG_HOME", str(Path.home() / ".config/opencode"))
    )
    return config_home / "skills/android-device/android_device.py"


def main() -> None:
    if not TOKEN:
        raise SystemExit(
            "❌ Cần GITHUB_TOKEN (classic, scope 'repo').\n"
            "   export GITHUB_TOKEN='ghp_...'"
        )

    owner = resolve_owner()
    print(f"→ Repo: {owner}/{REPO}")

    if os.environ.get("WAIT_FOR_BUILD", "1") == "1":
        run = wait_for_success(owner)
    else:
        run = latest_success(owner)

    archive = download_artifact(owner, run["id"])

    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        apk_names = [name for name in bundle.namelist() if name.lower().endswith(".apk")]
        if not apk_names:
            raise SystemExit("❌ Trong artifact không có file .apk")
        apk_name = apk_names[0]
        destination = project_root() / "app-debug.apk"
        destination.write_bytes(bundle.read(apk_name))
        print(f"📄 Giải nén: {apk_name} -> {destination}")

    bridge = bridge_script()
    if not bridge.exists():
        raise SystemExit(f"❌ Không tìm thấy bridge: {bridge}")

    result = subprocess.run(
        [
            sys.executable,
            str(bridge),
            "file-save",
            "--path",
            destination.name,
            "--destination",
            "downloads",
            "--name",
            "tro-ly-ao-debug.apk",
        ],
        cwd=project_root(),
        text=True,
        capture_output=True,
    )
    print(result.stdout.strip())
    if result.returncode != 0:
        print(result.stderr.strip(), file=sys.stderr)
        raise SystemExit("❌ Bridge không đẩy được file xuống máy.")

    destination.unlink(missing_ok=True)
    print("🎉 Xong! Vào Downloads trên máy, mở tro-ly-ao-debug.apk để cài.")


if __name__ == "__main__":
    main()
