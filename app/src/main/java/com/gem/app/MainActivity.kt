package com.gem.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

// ====================================================================
// 1. Состояния и режимы
// ====================================================================
enum class SessionState { IDLE, CONNECTING, ACTIVE, ERROR }

enum class TranslateMode(val label: String) {
    AUTO("DE \u21C4 RU"),   // двунаправленный авто-режим (две сессии)
    TO_DE("\u2192 DE"),     // всё переводить на немецкий
    TO_RU("\u2192 RU")      // всё переводить на русский
}

// ====================================================================
// 2. DI-модуль
// ====================================================================
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // keep-alive для длительных сессий
        .build()
}

// ====================================================================
// 3. ViewModel
// ====================================================================
@HiltViewModel
class TranslatorLiveViewModel @Inject constructor(
    private val client: OkHttpClient
) : ViewModel() {

    var apiKey by mutableStateOf("")
        private set
    var mode by mutableStateOf(TranslateMode.AUTO)
        private set
    var sessionState by mutableStateOf(SessionState.IDLE)
        private set
    var inputTranscript by mutableStateOf("")
        private set
    var outputTranscript by mutableStateOf("")
        private set
    var errorMsg by mutableStateOf<String?>(null)
        private set

    private val prefsName = "translator_live_prefs"
    private val keyApiKey = "api_key"

    // В AUTO-режиме открываются ДВЕ сессии: target=ru и target=de.
    // echoTargetLanguage=false => сессия молчит, если речь уже на её целевом языке.
    // Немецкая речь озвучивается ru-сессией, русская — de-сессией.
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val readyCount = AtomicInteger(0)
    private var expectedSessions = 1

    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        apiKey = prefs.getString(keyApiKey, "") ?: ""
    }

    fun saveApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        apiKey = trimmed
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString(keyApiKey, trimmed).apply()
    }

    fun selectMode(newMode: TranslateMode) {
        mode = newMode
        if (sessionState == SessionState.ACTIVE || sessionState == SessionState.CONNECTING) {
            stopSession()
            startLiveSession()
        }
    }

    fun startLiveSession() {
        if (apiKey.isBlank()) {
            errorMsg = "Пожалуйста, укажите API-ключ"
            sessionState = SessionState.ERROR
            return
        }

        sessionState = SessionState.CONNECTING
        errorMsg = null
        inputTranscript = ""
        outputTranscript = ""
        readyCount.set(0)

        audioPlayer = AudioPlayer().also { it.start() }

        when (mode) {
            TranslateMode.AUTO -> {
                expectedSessions = 2
                // primary-сессия (ru) отвечает за input-субтитры, чтобы не дублировать текст
                openTranslationSocket(target = "ru", isPrimary = true)
                openTranslationSocket(target = "de", isPrimary = false)
            }
            TranslateMode.TO_DE -> {
                expectedSessions = 1
                openTranslationSocket(target = "de", isPrimary = true)
            }
            TranslateMode.TO_RU -> {
                expectedSessions = 1
                openTranslationSocket(target = "ru", isPrimary = true)
            }
        }
    }

    private fun openTranslationSocket(target: String, isPrimary: Boolean) {
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // ВАЖНО: для gemini-3.5-live-translate-preview транскрипции
                // лежат ВНУТРИ generationConfig (в отличие от обычного Live API)
                val setupJson = """
                    {
                      "setup": {
                        "model": "models/gemini-3.5-live-translate-preview",
                        "generationConfig": {
                          "responseModalities": ["AUDIO"],
                          "inputAudioTranscription": {},
                          "outputAudioTranscription": {},
                          "translationConfig": {
                            "targetLanguageCode": "$target",
                            "echoTargetLanguage": false
                          }
                        }
                      }
                    }
                """.trimIndent()
                webSocket.send(setupJson)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text, isPrimary)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // Сервер может слать сообщения бинарными фреймами
                handleServerMessage(bytes.utf8(), isPrimary)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                viewModelScope.launch(Dispatchers.Main) {
                    val httpInfo = response?.let { " (HTTP ${it.code})" } ?: ""
                    errorMsg = "Ошибка: ${t.localizedMessage ?: "Сбой подключения"}$httpInfo"
                    sessionState = SessionState.ERROR
                    stopSession()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    if (code != 1000 && reason.isNotBlank()) {
                        errorMsg = "Сессия закрыта: $reason"
                        sessionState = SessionState.ERROR
                    }
                    webSocket.close(1000, null)
                }
            }
        })
        sockets.add(socket)
    }

    private fun handleServerMessage(jsonText: String, isPrimary: Boolean) {
        try {
            val root = JSONObject(jsonText)

            // Сервер подтверждает setup; начинаем запись, когда готовы все сессии
            if (root.has("setupComplete")) {
                val ready = readyCount.incrementAndGet()
                if (ready >= expectedSessions) {
                    viewModelScope.launch(Dispatchers.Main) {
                        sessionState = SessionState.ACTIVE
                        startRecording()
                    }
                }
                return
            }

            if (!root.has("serverContent")) return
            val serverContent = root.getJSONObject("serverContent")

            // 1. Переведённое аудио -> сразу в плеер (без переключения потоков, ради задержки)
            serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    val b64 = inline.optString("data", "")
                    if (b64.isNotEmpty()) {
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        audioPlayer?.write(bytes)
                    }
                }
            }

            // 2. Транскрипт входной речи — только от primary-сессии (иначе дубли в AUTO)
            if (isPrimary) {
                serverContent.optJSONObject("inputTranscription")?.let { tr ->
                    val newText = tr.optString("text", "")
                    if (newText.isNotBlank()) {
                        viewModelScope.launch(Dispatchers.Main) { inputTranscript += newText }
                    }
                }
            }

            // 3. Транскрипт перевода — от любой сессии (молчащая ничего не присылает)
            serverContent.optJSONObject("outputTranscription")?.let { tr ->
                val newText = tr.optString("text", "")
                if (newText.isNotBlank()) {
                    viewModelScope.launch(Dispatchers.Main) { outputTranscript += newText }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startRecording() {
        if (audioRecorder != null) return
        audioRecorder = AudioRecorder { pcmChunk ->
            val base64 = android.util.Base64.encodeToString(pcmChunk, android.util.Base64.NO_WRAP)
            // Новый формат realtimeInput: поле "audio" + mimeType с указанием rate
            val jsonMsg = """
                {
                  "realtimeInput": {
                    "audio": {
                      "mimeType": "audio/pcm;rate=16000",
                      "data": "$base64"
                    }
                  }
                }
            """.trimIndent()
            sockets.forEach { it.send(jsonMsg) }
        }
        audioRecorder?.start()
    }

    fun stopSession() {
        audioRecorder?.stop()
        audioRecorder = null

        audioPlayer?.stop()
        audioPlayer = null

        sockets.forEach { runCatching { it.close(1000, "User stopped") } }
        sockets.clear()
        readyCount.set(0)

        if (sessionState != SessionState.ERROR) {
            sessionState = SessionState.IDLE
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}

// ====================================================================
// 4. Аудио: захват и воспроизведение
// ====================================================================

class AudioRecorder(private val onAudioChunk: (ByteArray) -> Unit) {
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var isRecording = false
    private var recordingThread: Thread? = null

    fun start() {
        val sampleRate = 16000 // Вход: raw 16-bit PCM, 16 кГц, моно
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            // VOICE_COMMUNICATION включает аппаратное эхоподавление (AEC):
            // без него динамик с переводом "слышен" микрофону и в AUTO-режиме
            // вторая сессия начинает переводить собственный перевод по кругу
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                maxOf(minBuf, 3200 * 2)
            )

            audioRecord?.audioSessionId?.let { sid ->
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(sid)?.apply { enabled = true }
                }
            }

            isRecording = true
            audioRecord?.startRecording()

            recordingThread = Thread {
                // Чанки по 100 мс, как требует документация: 16000 Гц * 2 байта * 0.1 с
                val buffer = ByteArray(3200)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        onAudioChunk(buffer.copyOf(read))
                    }
                }
            }
            recordingThread?.start()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isRecording = false
        try {
            echoCanceler?.release()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        echoCanceler = null
        audioRecord = null
        recordingThread = null
    }
}

class AudioPlayer {
    private var audioTrack: AudioTrack? = null

    fun start() {
        val sampleRate = 24000 // Выход модели: 24 кГц PCM
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // VOICE_COMMUNICATION направляет звук в "голосовой" тракт,
                    // где работает системное эхоподавление
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    @Synchronized
    fun write(pcmData: ByteArray) {
        audioTrack?.write(pcmData, 0, pcmData.size)
    }

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}

// ====================================================================
// 5. Интерфейс
// ====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranslatorScreen(viewModel: TranslatorLiveViewModel = hiltViewModel()) {
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.init(context) }

    var showKeyInput by remember { mutableStateOf(viewModel.apiKey.isBlank()) }
    var keyInputValue by remember { mutableStateOf(viewModel.apiKey) }

    LaunchedEffect(viewModel.apiKey) {
        keyInputValue = viewModel.apiKey
        if (viewModel.apiKey.isNotBlank()) showKeyInput = false
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startLiveSession()
        } else {
            Toast.makeText(context, "Необходимо разрешение на использование микрофона", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Live Translate DE \u21C4 RU",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Black
                    )
                },
                actions = {
                    IconButton(onClick = { showKeyInput = !showKeyInput }) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = "Ключ API",
                            tint = if (viewModel.apiKey.isNotBlank()) Color(0xFF4CAF50) else Color.Red
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF9F9F9)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(visible = showKeyInput) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "API Ключ Google AI Studio",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = keyInputValue,
                            onValueChange = { keyInputValue = it },
                            placeholder = { Text("Вставьте AIzaSy...", color = Color.LightGray) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color.Black,
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color.LightGray
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showKeyInput = false }) {
                                Text("Отмена", color = Color.Gray)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    viewModel.saveApiKey(context, keyInputValue)
                                    showKeyInput = false
                                    Toast.makeText(context, "Ключ сохранен", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                            ) {
                                Text("Сохранить", color = Color.White)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Выбор режима перевода
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Режим перевода",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TranslateMode.entries.forEach { m ->
                            val isSelected = viewModel.mode == m
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                            .background(
                                        if (isSelected) Color.Black else Color(0xFFF0F0F0),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { viewModel.selectMode(m) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    m.label,
                                    color = if (isSelected) Color.White else Color.Gray,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    if (viewModel.mode == TranslateMode.AUTO) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Авто: немецкая речь озвучивается по-русски, русская — по-немецки.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            // Зона управления
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val state = viewModel.sessionState

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(160.dp)
                    ) {
                        if (state == SessionState.ACTIVE) {
                            val infiniteTransition = rememberInfiniteTransition()
                            val scale by infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 1.35f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1200, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .scale(scale)
                                    .clip(CircleShape)
                                    .background(Color(0x224CAF50))
                            )
                        }

                        IconButton(
                            onClick = {
                                if (state == SessionState.ACTIVE || state == SessionState.CONNECTING) {
                                    viewModel.stopSession()
                                } else {
                                    val permissionCheck = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    )
                                    if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                                        viewModel.startLiveSession()
                                    } else {
                                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(
                                    when (state) {
                                        SessionState.ACTIVE -> Color(0xFF4CAF50)
                                        SessionState.CONNECTING -> Color(0xFFFF9800)
                                        else -> Color.Black
                                    }
                                )
                        ) {
                            Icon(
                                imageVector = if (state == SessionState.ACTIVE) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = "Микрофон",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = when (state) {
                            SessionState.ACTIVE -> "Трансляция активна. Говорите..."
                            SessionState.CONNECTING -> "Соединение с Live API..."
                            SessionState.ERROR -> "Ошибка"
                            SessionState.IDLE -> "Нажмите, чтобы говорить"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = when (state) {
                            SessionState.ACTIVE -> Color(0xFF4CAF50)
                            SessionState.CONNECTING -> Color(0xFFFF9800)
                            SessionState.ERROR -> Color.Red
                            SessionState.IDLE -> Color.Gray
                        }
                    )

                    viewModel.errorMsg?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            it,
                            color = Color.Red,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Субтитры
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.5f)
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Синхронные субтитры",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        if (viewModel.inputTranscript.isNotEmpty() || viewModel.outputTranscript.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.stopSession(); viewModel.startLiveSession() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Сброс", tint = Color.Gray)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (viewModel.inputTranscript.isNotBlank()) {
                            Column {
                                Text(
                                    "Распознанная речь:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = viewModel.inputTranscript,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.DarkGray
                                )
                            }
                        }

                        if (viewModel.outputTranscript.isNotBlank()) {
                            Column {
                                Text(
                                    "Перевод:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4CAF50),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = viewModel.outputTranscript,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Black
                                )
                            }
                        }

                        if (viewModel.inputTranscript.isBlank() && viewModel.outputTranscript.isBlank()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Тут появятся распознанный текст и перевод в реальном времени.",
                                    color = Color.LightGray,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================
// 6. Activity
// ====================================================================
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LiveTranslatorScreen()
            }
        }
    }
}
