package com.example.assistant

import android.Manifest
import android.app.AlarmClock
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        when (action.optString("type")) {
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
                val pm = packageManager
                val launcherApps = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER),
                    0
                )
                val match = launcherApps.firstOrNull { info ->
                    val label = info.loadLabel(pm).toString()
                    label.contains(query, true) ||
                        info.activityInfo.packageName.contains(query, true)
                }
                val launchIntent = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                    ?: pm.getLaunchIntentForPackage(query)

                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this, "Không tìm thấy ứng dụng: $query", Toast.LENGTH_SHORT).show()
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
    onMic: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Trợ lý ảo") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message -> MessageBubble(message) }

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
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
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
            Text(
                text = message.text,
                color = if (message.isUser) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(12.dp)
            )
        }
    }
}
