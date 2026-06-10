package com.gem.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
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
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ====================================================================
// 1. Состояния сессии
// ====================================================================
enum class SessionState {
    IDLE, CONNECTING, ACTIVE, ERROR
}

// ====================================================================
// 2. DI-модуль (Предоставление OkHttpClient)
// ====================================================================
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES) // Большой таймаут для длительных сессий
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
}

// ====================================================================
// 3. ViewModel для Live-переводчика
// ====================================================================
@HiltViewModel
class TranslatorLiveViewModel @Inject constructor(
    private val client: OkHttpClient
) : ViewModel() {

    var apiKey by mutableStateOf("")
        private set
    var targetLanguage by mutableStateOf("de") // BCP-47 код целевого языка. "de" - немецкий, "ru" - русский
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

    private var webSocket: WebSocket? = null
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
            .edit()
            .putString(keyApiKey, trimmed)
            .apply()
    }

    fun setTargetLang(lang: String) {
        targetLanguage = lang
        // Если сессия была активна, перезапускаем с новыми настройками
        if (sessionState == SessionState.ACTIVE) {
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

        // Инициализируем плеер для воспроизведения переведенного аудио
        audioPlayer = AudioPlayer()
        audioPlayer?.start()

        // Создаем подключение к WebSocket API Gemini Live (используется v1beta)
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                viewModelScope.launch(Dispatchers.Main) {
                    sessionState = SessionState.ACTIVE
                    
                    // 1. Отправляем первоначальный конфигурационный JSON для Live Translate
                    val setupJson = """
                        {
                          "setup": {
                            "model": "models/gemini-3.5-live-translate-preview",
                            "inputAudioTranscription": {},
                            "outputAudioTranscription": {},
                            "generationConfig": {
                              "responseModalities": ["AUDIO"],
                              "translationConfig": {
                                "targetLanguageCode": "$targetLanguage",
                                "echoTargetLanguage": true
                              }
                            }
                          }
                        }
                    """.trimIndent()
                    webSocket.send(setupJson)

                    // 2. Начинаем запись микрофона
                    startRecording()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    parseServerResponse(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                viewModelScope.launch(Dispatchers.Main) {
                    errorMsg = "Ошибка: ${t.localizedMessage ?: "Сбой подключения"}"
                    sessionState = SessionState.ERROR
                    stopSession()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    sessionState = SessionState.IDLE
                    stopSession()
                }
            }
        })
    }

    private fun startRecording() {
        audioRecorder = AudioRecorder { pcmChunk ->
            val base64 = android.util.Base64.encodeToString(pcmChunk, android.util.Base64.NO_WRAP)
            val jsonMsg = """
                {
                  "realtimeInput": {
                    "mediaChunks": [
                      {
                        "mimeType": "audio/pcm",
                        "data": "$base64"
                      }
                    ]
                  }
                }
            """.trimIndent()
            webSocket?.send(jsonMsg)
        }
        audioRecorder?.start()
    }

    private fun parseServerResponse(jsonText: String) {
        try {
            val root = org.json.JSONObject(jsonText)
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                // 1. Получаем аудио-ответ от модели и пишем его в плеер
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                if (inlineData.has("data")) {
                                    val b64 = inlineData.getString("data")
                                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                    audioPlayer?.write(bytes)
                                }
                            }
                        }
                    }
                }

                // 2. Получаем оригинальные субтитры (то, что сказали вы)
                if (serverContent.has("inputTranscription")) {
                    val transcript = serverContent.getJSONObject("inputTranscription")
                    if (transcript.has("text")) {
                        val newText = transcript.getString("text")
                        if (newText.isNotBlank()) {
                            inputTranscript += " $newText"
                        }
                    }
                }

                // 3. Получаем переведенные субтитры (то, что говорит переводчик)
                if (serverContent.has("outputTranscription")) {
                    val transcript = serverContent.getJSONObject("outputTranscription")
                    if (transcript.has("text")) {
                        val newText = transcript.getString("text")
                        if (newText.isNotBlank()) {
                            outputTranscript += " $newText"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopSession() {
        audioRecorder?.stop()
        audioRecorder = null

        audioPlayer?.stop()
        audioPlayer = null

        webSocket?.close(1000, "User stopped")
        webSocket = null

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
// 4. Компоненты аудио захвата и воспроизведения
// ====================================================================

class AudioRecorder(private val onAudioChunk: (ByteArray) -> Unit) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    fun start() {
        val sampleRate = 16000 // Для Gemini Live API требуется 16 кГц
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            isRecording = true
            audioRecord?.startRecording()

            recordingThread = Thread {
                val buffer = ByteArray(1600) // Отправка кусочками ~50 мс для минимизации задержки
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
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        recordingThread = null
    }
}

class AudioPlayer {
    private var audioTrack: AudioTrack? = null

    fun start() {
        val sampleRate = 24000 // Аудиовыход от модели идет на частоте 24 кГц
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

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
// 5. Интерфейс Live-Переводчика
// ====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranslatorScreen(viewModel: TranslatorLiveViewModel = hiltViewModel()) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.init(context)
    }

    var showKeyInput by remember { mutableStateOf(viewModel.apiKey.isBlank()) }
    var keyInputValue by remember { mutableStateOf(viewModel.apiKey) }

    LaunchedEffect(viewModel.apiKey) {
        keyInputValue = viewModel.apiKey
        if (viewModel.apiKey.isNotBlank()) {
            showKeyInput = false
        }
    }

    // Запрос разрешений для записи микрофона
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
                        "Gemini 3.5 Live Translate",
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
            // Секция настройки ключа
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

            // Панель выбора целевого языка
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Язык перевода (Куда переводить)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val languages = listOf("de" to "Немецкий (DE)", "ru" to "Русский (RU)", "en" to "Английский (EN)")
                        languages.forEach { (code, name) ->
                            val isSelected = viewModel.targetLanguage == code
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (isSelected) Color.Black else Color(0xFFF0F0F0),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { viewModel.setTargetLang(code) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    name,
                                    color = if (isSelected) Color.White else Color.Gray,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            // Интерактивная зона стриминга
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
                        // Анимация пульсации во время активного перевода
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
                                    val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
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

            // Субтитры в реальном времени
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
                                    "Вы говорите:",
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
                                    "Перевод (${viewModel.targetLanguage.uppercase()}):",
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
// 6. Точка входа в Activity
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