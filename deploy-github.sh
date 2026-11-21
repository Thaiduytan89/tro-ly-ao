#!/usr/bin/env bash
# Đẩy source lên GitHub -> build APK -> tự tải APK về Downloads qua bridge.
# Cách dùng:
#   export GITHUB_TOKEN="ghp_..."   # token classic, scope "repo"
#   export REPO_NAME="tro-ly-ao"    # tùy chọn
#   bash deploy-github.sh
set -euo pipefail

if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  echo "❌ Cần đặt GITHUB_TOKEN. Tạo tại: https://github.com/settings/tokens (scope: repo)"
  exit 1
fi

echo "→ Lấy thông tin tài khoản GitHub..."
eval "$(python3 - <<'PY'
import json, os, urllib.request, urllib.error
token = os.environ["GITHUB_TOKEN"]
headers = {
    "Authorization": f"Bearer {token}",
    "Accept": "application/vnd.github+json",
    "User-Agent": "apk-build/1.0",
}

def get(path):
    req = urllib.request.Request("https://api.github.com" + path, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as res:
        return json.load(res)

def create_repo(name):
    req = urllib.request.Request(
        "https://api.github.com/user/repos",
        data=json.dumps({"name": name, "private": False}).encode(),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return res.status
    except urllib.error.HTTPError as err:
        if err.code == 422:  # repo đã tồn tại
            return 422
        raise

username = get("/user")["login"]
repo = os.environ.get("REPO_NAME", "tro-ly-ao").strip() or "tro-ly-ao"
status = create_repo(repo)
print("USERNAME=" + json.dumps(username))
print("REPO_NAME=" + json.dumps(repo))
print("REPO_STATUS=" + json.dumps(status))
PY
)"

echo "→ Repo: $USERNAME/$REPO_NAME (HTTP $REPO_STATUS)"

cd "$(dirname "$0")"

echo "→ Commit source..."
git init -q
git add .
git -c user.name="Assistant Builder" -c user.email="builder@local" \
  commit -qm "Bản đầu: trợ lý ảo tiếng Việt" || true

echo "→ Push lên GitHub..."
git remote remove origin 2>/dev/null || true
git remote add origin "https://x-access-token:${GITHUB_TOKEN}@github.com/${USERNAME}/${REPO_NAME}.git"
git branch -M main
git push -q -u origin main
# Xóa token khỏi .git/config sau khi push
git remote set-url origin "https://github.com/${USERNAME}/${REPO_NAME}.git"

echo "✅ Đã push. GitHub Actions đang build APK..."
echo "   Theo dõi tại: https://github.com/$USERNAME/$REPO_NAME/actions"

if [[ "${AUTO_FETCH:-1}" == "1" ]]; then
  python3 "$(dirname "$0")/fetch-apk-to-device.py"
else
  echo "Khi build xong chạy: python3 fetch-apk-to-device.py"
fi
