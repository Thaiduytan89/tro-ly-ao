"""Backend trợ lý ảo tiếng Việt: FastAPI + LLM (Gemini/OpenAI) + tool calling.

Chế độ demo được bật tự động khi chưa có API key, giúp bạn test luôn mà không cần đăng ký.
"""
from __future__ import annotations

import ast
import asyncio
import json
import operator
import os
import re
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from dotenv import load_dotenv
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

load_dotenv()

ASSISTANT_NAME = os.getenv("ASSISTANT_NAME", "Trợ lý")
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "").strip()
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-3.8-flash")
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-4.1-mini")
GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
VN_TZ = ZoneInfo("Asia/Ho_Chi_Minh")
MAX_HISTORY = 20

SYSTEM_PROMPT = """Bạn là {name}, trợ lý ảo chạy trên điện thoại Android của người dùng.

Quy tắc:
- Luôn trả lời bằng tiếng Việt, tự nhiên, ngắn gọn (tối đa 3-4 câu).
- Xưng "em", gọi người dùng là "bạn" (dùng "anh"/"chị" nếu họ tự giới thiệu).
- Khi người dùng hỏi giờ, ngày, yêu cầu tính toán, đặt báo thức hoặc mở ứng dụng,
  hãy GỌI CÔNG CỤ tương ứng thay vì tự đoán. Sau đó nói lại kết quả bằng câu tự nhiên.
- Với tác vụ đặt báo thức/mở ứng dụng, công cụ sẽ trả về một "action" để điện thoại thực hiện.
  Bạn chỉ cần xác nhận với người dùng.
- Nếu không có công cụ phù hợp (ví dụ thời tiết, tin tức), hãy nói rõ là chưa hỗ trợ
  thay vì bịa đặt dữ liệu.
- Khi điều khiển ứng dụng (Facebook, Messenger, Zalo...), hãy tuân theo vòng lặp:
  gọi ui_read để xem màn hình -> gọi ui_click/ui_type -> điện thoại sẽ gửi lại màn hình
  mới trong "context" -> tiếp tục cho đến khi xong việc. Không đoán nội dung màn hình.
- Nếu trong "context" đã có màn hình mới nhất, KHÔNG gọi ui_read lại ngay; hãy dùng
  thông tin đó để chọn action tiếp theo. Chỉ gọi ui_read khi chưa có màn hình.
- Trước các hành động nhạy cảm (gửi SMS, gọi điện), hãy xác nhận lại với người dùng.
- Không bao giờ tiết lộ API key hay chi tiết kỹ thuật nội bộ.
"""

TOOLS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "get_current_time",
            "description": "Lấy giờ hiện tại theo múi giờ Việt Nam.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_current_date",
            "description": "Lấy ngày hôm nay, thứ trong tuần theo lịch Việt Nam.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "calculate",
            "description": "Tính toán biểu thức số học cơ bản (+ - * / % ** và ngoặc đơn).",
            "parameters": {
                "type": "object",
                "properties": {
                    "expression": {
                        "type": "string",
                        "description": "Biểu thức, ví dụ: (12 + 8) * 3",
                    }
                },
                "required": ["expression"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "set_alarm",
            "description": "Đặt báo thức trên điện thoại. Trả về action cho ứng dụng Android thực hiện.",
            "parameters": {
                "type": "object",
                "properties": {
                    "hour": {"type": "integer", "description": "Giờ 0-23", "minimum": 0, "maximum": 23},
                    "minute": {"type": "integer", "description": "Phút 0-59", "minimum": 0, "maximum": 59},
                    "label": {"type": "string", "description": "Tên báo thức"},
                },
                "required": ["hour", "minute"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "open_app",
            "description": "Mở một ứng dụng trên điện thoại theo tên hoặc tên gói.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Tên ứng dụng, ví dụ: zalo"},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "ui_read",
            "description": "Đọc nội dung màn hình hiện tại. Luôn gọi trước khi cần chạm vào một nút unfamiliar.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "ui_click",
            "description": "Chạm vào một nút/vùng trên màn hình theo nội dung chữ hoặc resource id.",
            "parameters": {
                "type": "object",
                "properties": {
                    "text": {"type": "string", "description": "Chữ hiển thị trên nút cần chạm"},
                    "id": {"type": "string", "description": "Resource id của nút (nếu có)"},
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "ui_type",
            "description": "Gõ văn bản vào ô đang được focus (ví dụ ô tìm kiếm, ô nhắn tin).",
            "parameters": {
                "type": "object",
                "properties": {
                    "value": {"type": "string", "description": "Nội dung cần gõ"},
                },
                "required": ["value"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "ui_scroll",
            "description": "Cuộn màn hình xuống để xem thêm nội dung.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "ui_back",
            "description": "Bấm nút Back của Android.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "ui_home",
            "description": "Về màn hình chính.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "set_volume",
            "description": "Chỉnh âm lượng media theo phần trăm.",
            "parameters": {
                "type": "object",
                "properties": {
                    "percent": {"type": "integer", "description": "0-100", "minimum": 0, "maximum": 100}
                },
                "required": ["percent"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "open_wifi_settings",
            "description": "Mở màn hình cài đặt Wi-Fi.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "open_bluetooth_settings",
            "description": "Mở màn hình cài đặt Bluetooth.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "send_sms",
            "description": "Gửi SMS tới một số điện thoại.",
            "parameters": {
                "type": "object",
                "properties": {
                    "phone": {"type": "string", "description": "Số điện thoại"},
                    "body": {"type": "string", "description": "Nội dung tin nhắn"},
                },
                "required": ["phone", "body"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "make_call",
            "description": "Gọi điện tới một số điện thoại.",
            "parameters": {
                "type": "object",
                "properties": {
                    "phone": {"type": "string", "description": "Số điện thoại"}
                },
                "required": ["phone"],
            },
        },
    },
]

WEEKDAYS = ["Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"]

# Các công cụ được thực thi trên điện thoại (app nhận "action" rồi tự chạy).
CLIENT_TOOLS = {
    "ui_read", "ui_click", "ui_type", "ui_scroll", "ui_back", "ui_home",
    "set_volume", "open_wifi_settings", "open_bluetooth_settings",
    "send_sms", "make_call",
}

_BINOPS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
    ast.Pow: operator.pow,
}
_UNARYOPS = {ast.UAdd: operator.pos, ast.USub: operator.neg}


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    session_id: str = "default"
    context: str = Field(default="", max_length=12000)


class ChatResponse(BaseModel):
    reply: str
    action: dict[str, Any] | None = None
    provider: str


class ResetRequest(BaseModel):
    session_id: str = "default"


def _eval_node(node: ast.AST) -> float:
    if isinstance(node, ast.Expression):
        return _eval_node(node.body)
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
        return node.value
    if isinstance(node, ast.BinOp) and type(node.op) in _BINOPS:
        left, right = _eval_node(node.left), _eval_node(node.right)
        if isinstance(node.op, ast.Pow) and abs(right) > 1000:
            raise ValueError("Số mũ quá lớn")
        return _BINOPS[type(node.op)](left, right)
    if isinstance(node, ast.UnaryOp) and type(node.op) in _UNARYOPS:
        return _UNARYOPS[type(node.op)](_eval_node(node.operand))
    raise ValueError("Biểu thức không hợp lệ")


def safe_calculate(expression: str) -> float:
    return _eval_node(ast.parse(expression, mode="eval"))


def run_tool(name: str, args: dict[str, Any]) -> tuple[str, dict[str, Any] | None]:
    """Thực thi công cụ; trả về (nội dung cho LLM, action cho client)."""
    if name == "get_current_time":
        now = datetime.now(VN_TZ)
        return f"Bây giờ là {now:%H:%M:%S} ngày {now:%d/%m/%Y} (giờ Việt Nam).", None

    if name == "get_current_date":
        now = datetime.now(VN_TZ)
        return f"Hôm nay là {WEEKDAYS[now.weekday()]}, ngày {now:%d/%m/%Y}.", None

    if name == "calculate":
        expression = str(args.get("expression", "")).strip()
        try:
            value = safe_calculate(expression)
            if isinstance(value, float) and value.is_integer():
                value = int(value)
            return f"Kết quả: {value}", None
        except Exception as exc:
            return f"Mình không tính được biểu thức này: {exc}", None

    if name == "set_alarm":
        try:
            hour, minute = int(args.get("hour", 7)), int(args.get("minute", 0))
        except (TypeError, ValueError):
            return "Giờ báo thức không hợp lệ.", None
        if not (0 <= hour <= 23 and 0 <= minute <= 59):
            return "Giờ báo thức không hợp lệ.", None
        label = str(args.get("label") or "Báo thức")
        return (
            f"Đã đặt báo thức {hour:02d}:{minute:02d} — {label}.",
            {"type": "set_alarm", "hour": hour, "minute": minute, "label": label},
        )

    if name == "open_app":
        query = str(args.get("query", "")).strip()
        if not query:
            return "Bạn muốn mở ứng dụng nào?", None
        return f"Đang mở {query}.", {"type": "open_app", "query": query}

    if name in CLIENT_TOOLS:
        return "Đã gửi lệnh điều khiển tới điện thoại.", {"type": name, **args}

    return f"Không biết công cụ {name}.", None


def build_client() -> tuple[Any | None, str | None, str]:
    """Trả về (client, model, provider). provider = gemini | openai | demo."""
    if GEMINI_API_KEY:
        try:
            from openai import OpenAI
        except ImportError:
            return None, None, "demo"
        return OpenAI(api_key=GEMINI_API_KEY, base_url=GEMINI_BASE_URL), GEMINI_MODEL, "gemini"
    if OPENAI_API_KEY:
        try:
            from openai import OpenAI
        except ImportError:
            return None, None, "demo"
        return OpenAI(api_key=OPENAI_API_KEY), OPENAI_MODEL, "openai"
    return None, None, "demo"


def llm_chat(client: Any, model: str, history: list[dict[str, str]], name: str, context: str = "") -> tuple[str, dict[str, Any] | None, str]:
    messages: list[Any] = [{"role": "system", "content": SYSTEM_PROMPT.format(name=name)}]
    messages.extend(history)
    if context.strip():
        messages.append({"role": "system", "content": "Kết quả thao tác/màn hình mới nhất từ điện thoại:\n" + context.strip()})
    action: dict[str, Any] | None = None
    provider = "gemini" if GEMINI_API_KEY else "openai"

    for _ in range(3):
        # Một số endpoint OpenAI-compatible chưa hỗ trợ tools -> fallback gọi thường.
        try:
            response = client.chat.completions.create(
                model=model,
                messages=messages,
                tools=TOOLS,
                tool_choice="auto",
                temperature=0.7,
            )
        except Exception:
            response = client.chat.completions.create(
                model=model, messages=messages, temperature=0.7
            )

        message = response.choices[0].message
        tool_calls = getattr(message, "tool_calls", None)
        if not tool_calls:
            return (message.content or "Mình chưa có câu trả lời.", action, provider)

        messages.append(message)
        for call in tool_calls:
            function = call.function
            try:
                arguments = json.loads(function.arguments or "{}")
            except json.JSONDecodeError:
                arguments = {}
            result, side_action = run_tool(function.name, arguments)
            if side_action:
                action = side_action
            messages.append(
                {"role": "tool", "tool_call_id": call.id, "content": result}
            )

    return "Mình đã thực hiện yêu cầu nhưng chưa kịp phản hồi.", action, provider


def demo_chat(message: str, name: str) -> tuple[str, dict[str, Any] | None, str]:
    """Phản hồi mẫu bằng tiếng Việt khi chưa cấu hình API key."""
    text = message.lower().strip()
    action: dict[str, Any] | None = None

    if re.search(r"mấy giờ|giờ là|bây giờ là mấy giờ|current time", text):
        reply, _ = run_tool("get_current_time", {})
    elif re.search(r"ngày hôm nay|hôm nay là ngày|thứ mấy|current date", text):
        reply, _ = run_tool("get_current_date", {})
    elif "báo thức" in text or "alarm" in text:
        match = re.search(r"(\d{1,2})\s*[:h]\s*(\d{1,2})", text)
        hour = int(match.group(1)) if match else 7
        minute = int(match.group(2)) if match and int(match.group(2)) <= 59 else 0
        reply, action = run_tool("set_alarm", {"hour": hour, "minute": minute, "label": "Báo thức"})
    elif re.search(r"facebook|messenger|zalo", text):
        app = "zalo" if "zalo" in text else ("messenger" if "messenger" in text else "facebook")
        reply, action = run_tool("open_app", {"query": app})
    elif text.startswith("mở ") or text.startswith("open "):
        query = re.sub(r"^(mở|open)\s+", "", text).strip(" .!?")
        reply, action = run_tool("open_app", {"query": query})
    elif re.fullmatch(r"[\d\s+\-*/().]+", text) and any(op in text for op in "+-*/"):
        reply, _ = run_tool("calculate", {"expression": text})
    elif re.search(r"chào|hello|hi\b", text):
        reply = f"Chào bạn! Mình là {name}, trợ lý ảo của bạn. Bạn cần mình giúp gì?"
    elif "cảm ơn" in text or "thanks" in text:
        reply = "Không có gì cả! Cứ gọi mình khi cần nhé."
    elif re.search(r"bạn là ai|giới thiệu", text):
        reply = f"Mình là {name} — trợ lý ảo chạy trên điện thoại của bạn. Mình có thể báo giờ, đặt báo thức, mở ứng dụng và tính toán."
    else:
        reply = (
            f"Mình nghe thấy: \"{message}\". Hiện mình đang chạy chế độ demo — "
            "thêm GEMINI_API_KEY vào file backend/.env để mình trả lời thông minh hơn."
        )
    return reply, action, "demo"


app = FastAPI(title="Vietnamese Voice Assistant API", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

_sessions: dict[str, list[dict[str, str]]] = {}
_sessions_lock = asyncio.Lock()


@app.get("/health")
def health() -> dict[str, str]:
    _, _, provider = build_client()
    return {"status": "ok", "provider": provider, "assistant_name": ASSISTANT_NAME}


@app.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest) -> ChatResponse:
    async with _sessions_lock:
        history = _sessions.setdefault(request.session_id, [])
        history.append({"role": "user", "content": request.message})
        snapshot = list(history[-MAX_HISTORY:])

    client, model, provider = build_client()
    try:
        if provider == "demo":
            reply, action, provider = demo_chat(request.message, ASSISTANT_NAME)
        else:
            reply, action, provider = await asyncio.to_thread(
                llm_chat, client, model, snapshot, ASSISTANT_NAME, request.context
            )
    except Exception as exc:
        reply, action, provider = f"Xin lỗi, mình gặp lỗi khi xử lý: {exc}", None, "error"

    async with _sessions_lock:
        history.append({"role": "assistant", "content": reply})
        del history[:-MAX_HISTORY]

    return ChatResponse(reply=reply, action=action, provider=provider)


@app.post("/reset")
async def reset(request: ResetRequest) -> dict[str, bool]:
    async with _sessions_lock:
        _sessions.pop(request.session_id, None)
    return {"ok": True}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)
