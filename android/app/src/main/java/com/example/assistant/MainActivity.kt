package com.example.assistant

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.SmsManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vm: AssistantViewModel by viewModels()
    private val prefs by lazy { getSharedPreferences("assistant", MODE_PRIVATE) }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val listening = MutableStateFlow(false)
    private val partialText = MutableStateFlow("")

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else Toast.makeText(this, "Cần cấp quyền microphone để nói chuyện", Toast.LENGTH_SHORT).show()
    }

    private val requestDangerous = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { !it }) {
            Toast.makeText(this, "Một số quyền chưa được cấp", Toast.LENGTH_SHORT).show()
        }
    }

    private val appPackages = mapOf(
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "zalo" to "com.zing.zalo",
        "youtube" to "com.google.android.youtube",
        "camera" to "com.android.camera2",
        "clock" to "com.google.android.deskclock",
        "gmail" to "com.google.android.gm"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.setBackendUrl(prefs.getString("backend_url", "").orEmpty())

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("vi", "VN"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(this, "Thiết bị chưa có giọng tiếng Việt cho TTS", Toast.LENGTH_LONG).show()
                }
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val messages by vm.messages.collectAsState()
                val isThinking by vm.isThinking.collectAsState()
                val isListening by listening.collectAsState()
                val partial by partialText.collectAsState()

                AssistantScreen(
                    messages = messages,
                    isThinking = isThinking,
                    listening = isListening,
                    partialText = partial,
                    onSend = { vm.send(it) },
                    onMic = {
                        tts?.stop()
                        startListening()
                    },
                    onOpenSettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onCopy = { text -> copyToClipboard(text) },
                    onStartOverlay = { startOverlay() },
                    currentUrl = prefs.getString("backend_url", "").orEmpty(),
                    onSaveUrl = { url ->
                        prefs.edit().putString("backend_url", url.trim()).apply()
                        vm.setBackendUrl(url)
                        Toast.makeText(this, "Đã lưu địa chỉ backend", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        lifecycleScope.launch {
            var lastSpokenId = 0L
            vm.lastAssistantReply.collect { message ->
                if (message != null && message.id != 0L && message.id != lastSpokenId) {
                    lastSpokenId = message.id
                    tts?.speak(
                        message.text,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "reply-${message.id}"
                    )
                }
            }
        }

        lifecycleScope.launch {
            vm.lastAction.collect { action ->
                if (action != null) {
                    handleAction(action)
                    vm.clearAction()
                }
            }
        }
    }

    private fun startOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestDangerous.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(
                this,
                "Bật quyền Hiện trên các ứng dụng khác, rồi nhấn nút Popup lại",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        AssistantOverlayService.start(this)
        Toast.makeText(this, "Đã bật popup agent", Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Trợ lý ảo", text))
        Toast.makeText(this, "Đã copy vào bộ nhớ", Toast.LENGTH_SHORT).show()
    }

    private fun startListening() {
        if (listening.value) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Thiết bị không hỗ trợ nhận diện giọng nói", Toast.LENGTH_SHORT).show()
            return
        }

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(recognitionListener)
            }
        }

        listening.value = true
        partialText.value = ""

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Đang nghe...")
        }
        speechRecognizer?.startListening(intent)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            listening.value = false
        }

        override fun onError(error: Int) {
            listening.value = false
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "Không nghe rõ, bạn thử lại nhé"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Mình không nghe thấy gì"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chưa có quyền microphone"
                else -> "Lỗi nhận diện giọng nói ($error)"
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }

        override fun onResults(results: Bundle?) {
            listening.value = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            partialText.value = ""
            if (text.isNotBlank()) vm.send(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialText.value = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleAction(action: JSONObject) {
        val a11y = AssistantAccessibilityService.instance
        val type = action.optString("type")
        AssistantOverlayService.show("đang thực hiện: $type")
        when (type) {
            "set_alarm" -> {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, action.optInt("hour"))
                    putExtra(AlarmClock.EXTRA_MINUTES, action.optInt("minute"))
                    putExtra(AlarmClock.EXTRA_MESSAGE, action.optString("label", "Báo thức"))
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Không mở được ứng dụng đồng hồ", Toast.LENGTH_SHORT).show()
                }
            }

            "open_app" -> {
                val query = action.optString("query")
                val pkg = appPackages[query.lowercase()] ?: query
                val pm = packageManager
                val launcherApps = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER),
                    0
                )
                val match = launcherApps.firstOrNull { info ->
                    val label = info.loadLabel(pm).toString()
                    label.contains(query, true) ||
                        info.activityInfo.packageName.contains(pkg, true)
                }
                val launchIntent = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                    ?: pm.getLaunchIntentForPackage(pkg)

                if (launchIntent != null) {
                    startActivity(launchIntent)
                    vm.sendContext("Đã mở ứng dụng $query. Màn hình: ${a11y?.readScreen().orEmpty()}")
                } else {
                    Toast.makeText(this, "Không tìm thấy ứng dụng: $query", Toast.LENGTH_SHORT).show()
                    vm.sendContext("Không tìm thấy ứng dụng $query trên máy.")
                }
            }

            "ui_read" -> {
                val screen = a11y?.readScreen()
                    ?: "Chưa bật quyền Accessibility. Nhấn nút bánh răng trên thanh tiêu đề để bật."
                vm.sendContext("Màn hình hiện tại:\n$screen")
            }

            "ui_click" -> {
                val id = action.optString("id")
                val text = action.optString("text")
                val ok = when {
                    id.isNotBlank() -> a11y?.clickId(id) == true
                    text.isNotBlank() -> a11y?.clickText(text) == true
                    else -> false
                }
                vm.sendContext(
                    "Đã chạm ${if (ok) "thành công" else "thất bại"}. " +
                        "Màn hình sau: ${a11y?.readScreen().orEmpty()}"
                )
            }

            "ui_type" -> {
                val ok = a11y?.typeText(action.optString("value")) == true
                vm.sendContext(
                    "Đã gõ ${if (ok) "thành công" else "thất bại"}. " +
                        "Màn hình sau: ${a11y?.readScreen().orEmpty()}"
                )
            }

            "ui_scroll" -> {
                val ok = a11y?.scrollForward() == true
                vm.sendContext("Đã cuộn ${if (ok) "thành công" else "thất bại"}. " +
                    "Màn hình sau: ${a11y?.readScreen().orEmpty()}")
            }

            "ui_back" -> {
                a11y?.pressBack()
                vm.sendContext("Đã bấm Back. Màn hình sau: ${a11y?.readScreen().orEmpty()}")
            }

            "ui_home" -> {
                a11y?.pressHome()
                vm.sendContext("Đã về màn hình chính.")
            }

            "set_volume" -> {
                val percent = action.optInt("percent", 50).coerceIn(0, 100)
                val audio = getSystemService(AudioManager::class.java)
                val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                audio?.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (max * percent / 100f).toInt(),
                    0
                )
                vm.sendContext("Đã chỉnh âm lượng thành $percent%.")
            }

            "open_wifi_settings" -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            "open_bluetooth_settings" -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

            "send_sms" -> {
                val phone = action.optString("phone")
                val body = action.optString("body")
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestDangerous.launch(arrayOf(Manifest.permission.SEND_SMS))
                    vm.sendContext("Cần cấp quyền gửi SMS trước.")
                } else {
                    try {
                        SmsManager.getDefault().sendTextMessage(phone, null, body, null, null)
                        vm.sendContext("Đã gửi SMS tới $phone.")
                    } catch (e: Exception) {
                        vm.sendContext("Gửi SMS thất bại: ${e.message}")
                    }
                }
            }

            "make_call" -> {
                val phone = action.optString("phone")
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestDangerous.launch(arrayOf(Manifest.permission.CALL_PHONE))
                    vm.sendContext("Cần cấp quyền gọi điện trước.")
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")))
                        vm.sendContext("Đang gọi $phone.")
                    } catch (e: Exception) {
                        vm.sendContext("Gọi điện thất bại: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    messages: List<ChatMessage>,
    isThinking: Boolean,
    listening: Boolean,
    partialText: String,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onOpenSettings: () -> Unit,
    onCopy: (String) -> Unit,
    onStartOverlay: () -> Unit,
    currentUrl: String,
    onSaveUrl: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf(currentUrl) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, isThinking) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trợ lý ảo") },
                actions = {
                    FilledIconButton(onClick = { showUrlDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Đổi địa chỉ backend")
                    }
                    FilledIconButton(onClick = onStartOverlay) {
                        Icon(Icons.Filled.Visibility, contentDescription = "Bật popup agent")
                    }
                    FilledIconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Cấp quyền điều khiển")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message -> MessageBubble(message, onCopy) }

                if (isThinking) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text(" Đang suy nghĩ...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (listening) {
                Text(
                    text = if (partialText.isNotBlank()) "Đang nghe: $partialText" else "Đang nghe...",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Nhắn hoặc nói...") },
                    maxLines = 4,
                    enabled = !isThinking,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (input.isNotBlank()) {
                                onSend(input)
                                input = ""
                            }
                        }
                    )
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = onMic, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Mic, contentDescription = "Nói")
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            onSend(input)
                            input = ""
                        }
                    },
                    modifier = Modifier.size(56.dp),
                    enabled = input.isNotBlank() && !isThinking
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Gửi")
                }
            }
        }

        if (showUrlDialog) {
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = { Text("Địa chỉ backend") },
                text = {
                    Column {
                        Text("Dán URL tunnel/cloud mới (bắt đầu bằng https://). Để trống để dùng mặc định.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("https://...") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onSaveUrl(urlInput)
                        showUrlDialog = false
                    }) { Text("Lưu") }
                },
                dismissButton = {
                    TextButton(onClick = { showUrlDialog = false }) { Text("Hủy") }
                }
            )
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, onCopy: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            ),
            color = if (message.isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(12.dp)
            ) {
                Text(
                    text = message.text,
                    color = if (message.isUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (!message.isUser && message.id != 0L) {
                    IconButton(
                        onClick = { onCopy(message.text) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy nội dung",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
