package com.example.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ChatMessage(val id: Long, val text: String, val isUser: Boolean)

class AssistantViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(ChatMessage(0L, "Xin chào! Bạn có thể nói hoặc gõ câu hỏi cho mình nhé.", false))
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _lastAssistantReply = MutableStateFlow<ChatMessage?>(null)
    val lastAssistantReply: StateFlow<ChatMessage?> = _lastAssistantReply.asStateFlow()

    private val _lastAction = MutableStateFlow<JSONObject?>(null)
    val lastAction: StateFlow<JSONObject?> = _lastAction.asStateFlow()

    private val sessionId = "android-" + UUID.randomUUID().toString().take(8)
    private var nextId = 1L

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun send(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || _isThinking.value) return

        _messages.value = _messages.value + ChatMessage(nextId++, text, true)
        _isThinking.value = true

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { callBackend(text) }
                val reply = ChatMessage(nextId++, result.reply, false)
                _messages.value = _messages.value + reply
                _lastAssistantReply.value = reply
                result.action?.let { _lastAction.value = it }
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    nextId++,
                    "Không kết nối được backend: ${e.message}. Kiểm tra IP trong BuildConfig.BACKEND_URL.",
                    false
                )
            } finally {
                _isThinking.value = false
            }
        }
    }

    /** Gửi kết quả thao tác/màn hình cho LLM để nó quyết định bước tiếp theo. */
    fun sendContext(context: String) {
        if (context.isBlank()) return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { callBackend("", context) }
                val reply = ChatMessage(nextId++, result.reply, false)
                _messages.value = _messages.value + reply
                _lastAssistantReply.value = reply
                result.action?.let { _lastAction.value = it }
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    nextId++, "Lỗi agent: ${e.message}", false
                )
            }
        }
    }

    fun clearAction() {
        _lastAction.value = null
    }

    private fun callBackend(text: String, context: String = ""): BackendResult {
        val payload = JSONObject()
            .put("message", text.ifBlank { "(agent step)" })
            .put("session_id", sessionId)
            .put("context", context)
            .toString()

        val request = Request.Builder()
            .url("${BuildConfig.BACKEND_URL}/chat")
            .post(payload.toRequestBody(jsonMedia))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            return BackendResult(
                reply = json.optString("reply", "Mình chưa có câu trả lời."),
                action = json.optJSONObject("action")
            )
        }
    }

    private data class BackendResult(val reply: String, val action: JSONObject?)
}
