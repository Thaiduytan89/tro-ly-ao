# Trợ lý ảo tiếng Việt (kiểu Bixby)

App Android: nói/gõ tiếng Việt → gửi lên backend → LLM trả lời → đọc to bằng TTS.
Có tool calling cho: báo giờ, ngày tháng, tính toán, đặt báo thức, mở ứng dụng.

## Cấu trúc

```
vietnamese-assistant/
├── android/          # App Kotlin + Jetpack Compose
└── backend/          # API FastAPI + Gemini/OpenAI (+ chế độ demo)
```

## 1. Chạy backend

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# Sửa .env: điền GEMINI_API_KEY (https://aistudio.google.com/apikey)
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Không có API key vẫn chạy được ở **chế độ demo** (trả lời có kịch bản + vẫn chạy tool).

Test nhanh:

```bash
curl -X POST http://localhost:8000/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"bây giờ mấy giờ rồi?"}'
```

## 2. Chạy app Android

### Cách A: Android Studio (khuyến nghị)

1. Mở Android Studio → **Open** → chọn thư mục `android/`.
2. Đợi Gradle sync (lần đầu tải khá lâu). Nếu thiếu `gradlew`, Android Studio sẽ tạo.
3. Sửa IP backend trong `android/app/build.gradle.kts`:
   - Giả lập: giữ `http://10.0.2.2:8000`
   - Điện thoại thật: đổi thành IP LAN của máy chạy backend, ví dụ `http://192.168.1.10:8000`
4. Chạy trên emulator/device → cấp quyền microphone → nói "bây giờ mấy giờ rồi?".

### Cách B: Build APK tự động bằng GitHub Actions (nhanh & tiện nhất)

Chỉ cần 1 token GitHub (classic, scope `repo`) rồi chạy **1 lệnh** trong thư mục `vietnamese-assistant/`:

```bash
export GITHUB_TOKEN="ghp_..."
bash deploy-github.sh
```

Script sẽ: tạo repo → đẩy code → chờ GitHub build xong → tải APK → gửi thẳng file `tro-ly-ao-debug.apk` vào **Downloads trên máy bạn** qua Android bridge. Bạn chỉ việc mở file đó và bấm cài (cho phép "cài từ nguồn không rõ").

Nếu muốn tự làm từng bước, set `AUTO_FETCH=0` rồi chạy riêng `python3 fetch-apk-to-device.py` khi build xong. Theo dõi tiến độ tại `https://github.com/<user>/tro-ly-ao/actions`.

> APK debug đã được ký bằng debug key, cài được bình thường. Không nên phát hành lên Store.

## 3. Lộ trình nâng cấp kiểu Bixby

- [ ] Wake word "Hey trợ lý" bằng foreground service + Porcupine
- [ ] Lịch sử hội thoại lưu bằng Room (hiện lưu tạm theo session trong RAM)
- [ ] Thêm "capsule": thời tiết, tin tức, nhắn tin, điều khiển Wi-Fi/Bluetooth
- [ ] TTS cloud tiếng Việt tự nhiên hơn (Google Cloud TTS / ElevenLabs)
- [ ] Streaming response để giảm độ trễ khi nói

## Lưu ý bảo mật

- Không bao giờ nhúng API key vào app Android. Backend đóng vai trò proxy giấu key.
- `usesCleartextTraffic="true"` chỉ dùng cho dev HTTP local. Khi deploy thật dùng HTTPS và bỏ cấu hình này.
