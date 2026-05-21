package com.gem.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ====================================================================
// 1. Data Models
// ====================================================================

@Serializable
data class GeminiRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null
)

@Serializable
data class Content(
    val role: String,
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null,
    val thought: Boolean? = null,
    val thoughtSignature: String? = null
)

@Serializable
data class InlineData(
    val mimeType: String,
    val data: String // Base64
)

@Serializable
data class GenerationConfig(
    val thinkingConfig: ThinkingConfig? = null
)

@Serializable
data class ThinkingConfig(
    val thinkingLevel: String? = null, // "low" | "medium" | "high"
    val includeThoughts: Boolean? = null
)

@Serializable
data class GeminiResponseChunk(
    val candidates: List<Candidate>? = null,
    val error: ApiError? = null
)

@Serializable
data class ApiError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)

@Serializable
data class Candidate(
    val content: ResponseContent? = null,
    val finishReason: String? = null
)

@Serializable
data class ResponseContent(
    val parts: List<Part>? = null,
    val role: String? = null
)

@Serializable
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: List<Message> = emptyList()
)

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" | "model"
    val timestamp: Long = System.currentTimeMillis(),
    val text: String = "",
    val thought: String? = null,
    val attachments: List<Attachment> = emptyList()
)

@Serializable
data class Attachment(
    val fileName: String,
    val filePath: String,
    val mimeType: String
)

// ====================================================================
// 2. Settings & Storage (Encrypted API keys + JSON history)
// ====================================================================

@Singleton
class SettingsStorage @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "gemini_v3_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback to plain prefs if encrypted prefs unavailable (rare, e.g. corrupt keystore)
        context.getSharedPreferences("gemini_v3_settings", Context.MODE_PRIVATE)
    }

    fun getKeys(): List<String> =
        (0..8).map { index -> prefs.getString("api_key_$index", "") ?: "" }

    fun saveKeys(keys: List<String>) {
        prefs.edit().apply {
            keys.forEachIndexed { index, key -> putString("api_key_$index", key.trim()) }
            apply()
        }
    }

    fun getActiveIndex(): Int = prefs.getInt("active_key_index", 0).coerceIn(0, 8)

    fun saveActiveIndex(index: Int) {
        prefs.edit().putInt("active_key_index", index.coerceIn(0, 8)).apply()
    }

    fun getActiveKey(): String {
        val idx = getActiveIndex()
        return prefs.getString("api_key_$idx", "") ?: ""
    }
}

@Singleton
class ChatRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val file = File(context.filesDir, "chat_history.json")

    @Synchronized
    fun getSessions(): List<ChatSession> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveSessions(sessions: List<ChatSession>) {
        try {
            file.writeText(json.encodeToString(sessions))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Remove cached attachment files that are no longer referenced anywhere. */
    fun cleanupOrphanedCache(activeSessions: List<ChatSession>) {
        try {
            val referenced = activeSessions
                .flatMap { it.messages }
                .flatMap { it.attachments }
                .map { it.filePath }
                .toSet()
            context.cacheDir.listFiles { f -> f.name.startsWith("gemini_att_") }
                ?.forEach { f ->
                    if (f.absolutePath !in referenced) f.delete()
                }
        } catch (_: Exception) { /* best-effort */ }
    }
}

// ====================================================================
// 3. Network API Layer (proper SSE streaming)
// ====================================================================

@Singleton
class GeminiService @Inject constructor(
    private val client: OkHttpClient,
    private val settingsStorage: SettingsStorage
) {
    // CRITICAL: explicitNulls = false so we don't send `"inlineData": null` etc. to the API.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** In-memory base64 cache: file path -> encoded data. Survives across turns in same session. */
    private val base64Cache = ConcurrentHashMap<String, String>()

    fun encodeFileBase64(path: String): String? {
        base64Cache[path]?.let { return it }
        return try {
            val bytes = File(path).takeIf { it.exists() }?.readBytes() ?: return null
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            base64Cache[path] = b64
            b64
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Starts a streaming request. Returns the Call so the caller can cancel.
     * The provided [scope] (typically viewModelScope) bounds the lifecycle of the read loop.
     */
    fun generateStream(
        scope: CoroutineScope,
        history: List<Content>,
        onChunk: (text: String, isThought: Boolean) -> Unit,
        onComplete: (fullText: String, fullThought: String) -> Unit,
        onError: (Throwable) -> Unit
    ): Call? {
        val apiKey = settingsStorage.getActiveKey()
        if (apiKey.isBlank()) {
            onError(IllegalStateException("Выбранный API-ключ пуст. Откройте настройки и заполните его."))
            return null
        }

        // alt=sse is REQUIRED for proper server-sent-events streaming.
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:streamGenerateContent?alt=sse&key=$apiKey"

        val payload = GeminiRequest(
            contents = history,
            generationConfig = GenerationConfig(
                thinkingConfig = ThinkingConfig(
                    thinkingLevel = "high",
                    includeThoughts = true
                )
            )
        )

        val body = json.encodeToString(payload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Accept", "text/event-stream")
            .build()
        val call = client.newCall(request)

        scope.launch(Dispatchers.IO) {
            var fullText = ""
            var fullThought = ""
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string().orEmpty()
                        throw IOException("HTTP ${response.code}: ${err.take(500)}")
                    }
                    val stream = response.body?.byteStream()
                        ?: throw IOException("Пустое тело ответа")
                    parseSseStream(stream) { text, isThought ->
                        if (isThought) {
                            fullThought += text
                        } else {
                            fullText += text
                        }
                        onChunk(text, isThought)
                    }
                }
                onComplete(fullText, fullThought)
            } catch (e: Exception) {
                if (call.isCanceled()) {
                    // User cancellation: surface whatever was streamed so far.
                    onComplete(fullText, fullThought)
                } else {
                    onError(e)
                }
            }
        }
        return call
    }

    /**
     * Reads a Server-Sent-Events response line by line and parses each `data: { ... }` chunk.
     * Format reference: data: {"candidates":[{"content":{"parts":[{"text":"..."}]}}]}\n
     */
    private fun parseSseStream(
        inputStream: InputStream,
        onChunk: (String, Boolean) -> Unit
    ) {
        inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data:")) continue
                // Strip "data:" prefix and one optional space.
                var payload = line.substring(5)
                if (payload.startsWith(" ")) payload = payload.substring(1)
                payload = payload.trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val chunk = try {
                    json.decodeFromString<GeminiResponseChunk>(payload)
                } catch (e: Exception) {
                    continue
                }
                chunk.error?.let { apiErr ->
                    throw IOException("API ${apiErr.code ?: "?"}: ${apiErr.message ?: "unknown"}")
                }
                chunk.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                    val text = part.text ?: return@forEach
                    if (text.isNotEmpty()) {
                        onChunk(text, part.thought == true)
                    }
                }
            }
        }
    }
}

// ====================================================================
// 4. Chat ViewModel
// ====================================================================

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val apiService: GeminiService,
    private val repository: ChatRepository,
    private val settingsStorage: SettingsStorage
) : ViewModel() {

    var currentSession by mutableStateOf<ChatSession?>(null)
        private set
    var sessions by mutableStateOf<List<ChatSession>>(emptyList())
        private set
    var isGenerating by mutableStateOf(false)
        private set
    var activeKeyIndex by mutableStateOf(settingsStorage.getActiveIndex())
        private set

    private var activeCall: Call? = null
    private var streamJob: Job? = null

    init {
        loadSessions()
    }

    private fun loadSessions() {
        val list = repository.getSessions()
        sessions = list
        currentSession = list.firstOrNull() ?: createNewSession()
        // Background cleanup of orphaned cache files
        viewModelScope.launch(Dispatchers.IO) {
            repository.cleanupOrphanedCache(sessions)
        }
    }

    fun createNewSession(): ChatSession {
        val newSession = ChatSession(title = "Диалог ${sessions.size + 1}")
        val newList = listOf(newSession) + sessions
        sessions = newList
        currentSession = newSession
        persist()
        return newSession
    }

    fun selectSession(session: ChatSession) {
        currentSession = session
    }

    fun deleteSession(session: ChatSession) {
        val newList = sessions.filter { it.id != session.id }
        sessions = newList
        if (currentSession?.id == session.id) {
            currentSession = newList.firstOrNull() ?: createNewSession()
        }
        persist()
        viewModelScope.launch(Dispatchers.IO) {
            repository.cleanupOrphanedCache(sessions)
        }
    }

    fun updateActiveKeyIndex(index: Int) {
        val safe = index.coerceIn(0, 8)
        settingsStorage.saveActiveIndex(safe)
        activeKeyIndex = safe
    }

    fun getKeys(): List<String> = settingsStorage.getKeys()

    fun saveKeys(keys: List<String>) {
        settingsStorage.saveKeys(keys)
    }

    fun stopGeneration() {
        activeCall?.cancel()
        activeCall = null
        streamJob?.cancel()
        streamJob = null
        isGenerating = false
    }

    fun sendMessage(
        prompt: String,
        attachment: Attachment?,
        onValidationError: (String) -> Unit
    ) {
        if (settingsStorage.getActiveKey().isBlank()) {
            onValidationError("Пожалуйста, укажите API-ключ в настройках.")
            return
        }
        if (prompt.isBlank() && attachment == null) {
            onValidationError("Напишите сообщение или прикрепите файл.")
            return
        }
        val session = currentSession ?: return

        // For text-like files, inline the content into the prompt (much smaller than base64).
        var finalUserPrompt = prompt
        val binaryAttachments = mutableListOf<Attachment>()

        if (attachment != null) {
            val f = File(attachment.filePath)
            if (f.exists()) {
                if (isTextFile(attachment.mimeType, attachment.fileName)) {
                    try {
                        val contentText = f.readText()
                        finalUserPrompt = buildString {
                            if (prompt.isNotBlank()) {
                                append(prompt).append("\n\n")
                            }
                            append("[Прикрепленный файл: ${attachment.fileName}]\n```\n")
                            append(contentText)
                            append("\n```")
                        }
                    } catch (e: Exception) {
                        onValidationError("Не удалось прочитать текстовый файл: ${e.localizedMessage}")
                        return
                    }
                } else {
                    binaryAttachments.add(attachment)
                }
            } else {
                onValidationError("Файл больше не доступен на диске.")
                return
            }
        }

        val userMsg = Message(
            role = "user",
            text = finalUserPrompt,
            attachments = binaryAttachments
        )

        val updatedMessages = session.messages + userMsg
        val newTitle = if (session.messages.isEmpty() && prompt.isNotBlank()) {
            prompt.take(40).trim()
        } else session.title
        val updatedSession = session.copy(title = newTitle, messages = updatedMessages)
        updateSessionInList(updatedSession)

        // Build API history (skip empty model placeholders).
        val apiHistory = updatedMessages.mapNotNull { msg ->
            val parts = mutableListOf<Part>()
            if (msg.text.isNotBlank()) parts.add(Part(text = msg.text))
            if (msg.role == "user") {
                msg.attachments.forEach { att ->
                    val b64 = apiService.encodeFileBase64(att.filePath)
                    if (b64 != null) {
                        parts.add(Part(inlineData = InlineData(att.mimeType, b64)))
                    }
                }
            }
            if (parts.isEmpty()) null else Content(role = msg.role, parts = parts)
        }

        // Insert placeholder assistant message.
        val assistantMsgId = UUID.randomUUID().toString()
        val placeholder = Message(id = assistantMsgId, role = "model", text = "", thought = null)
        updateSessionInList(updatedSession.copy(messages = updatedSession.messages + placeholder))

        isGenerating = true
        var accText = ""
        var accThought = ""

        activeCall = apiService.generateStream(
            scope = viewModelScope,
            history = apiHistory,
            onChunk = { chunk, isThought ->
                viewModelScope.launch(Dispatchers.Main) {
                    if (isThought) accThought += chunk else accText += chunk
                    updateAssistantMessageText(assistantMsgId, accText, accThought)
                }
            },
            onComplete = { finalText, finalThought ->
                viewModelScope.launch(Dispatchers.Main) {
                    isGenerating = false
                    activeCall = null
                    val text = if (finalText.isNotEmpty()) finalText else accText
                    val thought = if (finalThought.isNotEmpty()) finalThought else accThought
                    updateAssistantMessageText(assistantMsgId, text, thought)
                    persist()
                }
            },
            onError = { error ->
                viewModelScope.launch(Dispatchers.Main) {
                    isGenerating = false
                    activeCall = null
                    val errText = buildString {
                        if (accText.isNotBlank()) append(accText).append("\n\n")
                        append("[Ошибка: ${error.localizedMessage ?: error.javaClass.simpleName}]")
                    }
                    updateAssistantMessageText(assistantMsgId, errText, accThought)
                    persist()
                }
            }
        )

        if (activeCall == null) {
            // Initial validation failed inside service (e.g. blank key) — undo placeholder.
            isGenerating = false
            val msgs = (currentSession?.messages ?: emptyList()).filter { it.id != assistantMsgId }
            currentSession?.let { updateSessionInList(it.copy(messages = msgs)) }
        }
    }

    private fun persist() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveSessions(sessions)
        }
    }

    private fun updateSessionInList(updatedSession: ChatSession) {
        sessions = sessions.map { if (it.id == updatedSession.id) updatedSession else it }
        if (currentSession?.id == updatedSession.id) currentSession = updatedSession
    }

    private fun updateAssistantMessageText(msgId: String, text: String, thought: String) {
        val session = currentSession ?: return
        val updatedMsgs = session.messages.map { msg ->
            if (msg.id == msgId) msg.copy(text = text, thought = thought.ifBlank { null }) else msg
        }
        updateSessionInList(session.copy(messages = updatedMsgs))
    }

    private fun isTextFile(mimeType: String?, fileName: String): Boolean {
        if (mimeType?.startsWith("text/") == true) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    override fun onCleared() {
        super.onCleared()
        activeCall?.cancel()
    }

    companion object {
        private val TEXT_EXTENSIONS = setOf(
            "txt", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
            "html", "css", "scss", "json", "xml", "md", "rs", "go",
            "gradle", "properties", "yml", "yaml", "toml", "sh", "bash",
            "bat", "ps1", "c", "cpp", "h", "hpp", "cs", "swift", "m", "mm",
            "rb", "php", "sql", "csv", "tsv", "log", "ini", "conf", "env"
        )
    }
}

// ====================================================================
// 5. DI module
// ====================================================================

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // no overall timeout — streams can be long
        .build()
}

// ====================================================================
// 6. Activity & UI
// ====================================================================

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ChatAppMainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAppMainScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showSessionsDrawer by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("") }
    var selectedAttachment by remember { mutableStateOf<Attachment?>(null) }

    val activeSession = viewModel.currentSession
    val listState = rememberLazyListState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val att = handleFileSelection(it, context)
            if (att != null) {
                val file = File(att.filePath)
                if (file.length() > 15 * 1024 * 1024) {
                    Toast.makeText(context, "Файл слишком большой (максимум 15 МБ)", Toast.LENGTH_SHORT).show()
                } else {
                    selectedAttachment = att
                }
            } else {
                Toast.makeText(context, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(activeSession?.messages?.size, viewModel.isGenerating) {
        val size = activeSession?.messages?.size ?: 0
        if (size > 0) {
            listState.animateScrollToItem(size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Gemini 3.5 Flash",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { showSessionsDrawer = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "История", tint = Color.Black)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.createNewSession() }) {
                        Icon(Icons.Default.AddComment, contentDescription = "Новый диалог", tint = Color.Black)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = Color.Black)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color.White
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.White)
        ) {
            HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)

            KeySelectorPill(
                activeIndex = viewModel.activeKeyIndex,
                keys = viewModel.getKeys(),
                onKeySelected = { viewModel.updateActiveKeyIndex(it) }
            )

            HorizontalDivider(color = Color(0xFFF5F5F5), thickness = 1.dp)

            Box(modifier = Modifier.weight(1f)) {
                if (activeSession == null || activeSession.messages.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Начните диалог. Задайте вопрос или загрузите файл.",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(activeSession.messages, key = { it.id }) { message ->
                            MessageBubble(message = message, context = context)
                        }
                    }
                }
            }

            selectedAttachment?.let { att ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(Color(0xFFF9F9F9), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFFEEEEEE), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "File", tint = Color.Gray)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = att.fileName,
                        color = Color.Black,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { selectedAttachment = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Очистить", tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Прикрепить файл", tint = Color.Black)
                }

                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    placeholder = { Text("Введите промт или вставьте код...", color = Color.Gray) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = Color.LightGray,
                        unfocusedBorderColor = Color.LightGray.copy(alpha = 0.5f),
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black
                    ),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 6
                )

                IconButton(
                    onClick = {
                        if (viewModel.isGenerating) {
                            viewModel.stopGeneration()
                        } else {
                            val currentPrompt = promptText
                            val currentAtt = selectedAttachment
                            promptText = ""
                            selectedAttachment = null
                            viewModel.sendMessage(currentPrompt, currentAtt) { err ->
                                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black, CircleShape)
                ) {
                    Icon(
                        imageVector = if (viewModel.isGenerating) Icons.Default.Stop else Icons.Default.ArrowUpward,
                        contentDescription = if (viewModel.isGenerating) "Стоп" else "Отправить",
                        tint = Color.White
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            keys = viewModel.getKeys(),
            onDismiss = { showSettings = false },
            onSave = { viewModel.saveKeys(it) }
        )
    }

    if (showSessionsDrawer) {
        ModalBottomSheet(
            onDismissRequest = { showSessionsDrawer = false },
            containerColor = Color.White,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp)
            ) {
                Text(
                    "История диалогов",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(viewModel.sessions, key = { it.id }) { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (session.id == activeSession?.id) Color(0xFFF5F5F5) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    viewModel.selectSession(session)
                                    showSessionsDrawer = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = session.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.Black,
                                fontWeight = if (session.id == activeSession?.id) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.deleteSession(session) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить", tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KeySelectorPill(
    activeIndex: Int,
    keys: List<String>,
    onKeySelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color.LightGray),
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val key = keys.getOrNull(activeIndex).orEmpty()
                val label = if (key.isNotBlank()) {
                    val masked = if (key.length > 12) key.take(8) + "..." + key.takeLast(4) else "••••"
                    "🔑 Ключ ${activeIndex + 1} ($masked)"
                } else {
                    "🔑 Ключ ${activeIndex + 1} (не настроен)"
                }
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.Black)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Выбрать ключ", tint = Color.Black)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White)
        ) {
            keys.forEachIndexed { index, k ->
                DropdownMenuItem(
                    text = {
                        val label = if (k.isNotBlank()) {
                            val masked = if (k.length > 12) k.take(8) + "..." + k.takeLast(4) else "••••"
                            "Ключ ${index + 1} ($masked)"
                        } else "Ключ ${index + 1} (пусто)"
                        Text(
                            label,
                            fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == activeIndex) Color.Black else Color.Gray
                        )
                    },
                    onClick = {
                        onKeySelected(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message, context: Context) {
    val isUser = message.role == "user"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = if (isUser) "ВЫ" else "GEMINI 3.5 FLASH",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp, end = 4.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(0.95f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFFF0F0F0)),
            color = if (isUser) Color(0xFFFDFDFD) else Color.White
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (isUser && message.attachments.isNotEmpty()) {
                    message.attachments.forEach { att ->
                        Row(
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.Gray
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(att.fileName, style = MaterialTheme.typography.bodySmall, color = Color.Black)
                        }
                    }
                }

                if (!isUser && !message.thought.isNullOrBlank()) {
                    var thoughtsExpanded by remember(message.id) { mutableStateOf(false) }
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        Surface(
                            onClick = { thoughtsExpanded = !thoughtsExpanded },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
                            color = Color(0xFFFCFCFC)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Psychology, contentDescription = null, tint = Color.Gray)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Процесс рассуждения...", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                }
                                Icon(
                                    imageVector = if (thoughtsExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = Color.Gray
                                )
                            }
                        }
                        if (thoughtsExpanded) {
                            SelectionContainer {
                                Text(
                                    text = message.thought,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray,
                                    fontFamily = FontFamily.SansSerif,
                                    modifier = Modifier
                                        .padding(top = 8.dp, start = 8.dp)
                                        .border(BorderStroke(1.dp, Color(0xFFEFEFEF)), RoundedCornerShape(4.dp))
                                        .background(Color(0xFFFCFCFC))
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                }

                if (message.text.isNotBlank()) {
                    RenderMarkdownText(text = message.text, context = context)
                }

                if (!isUser && message.text.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(
                            onClick = { copyToClipboard(context, message.text) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Скопировать ответ", tint = Color.Black)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RenderMarkdownText(text: String, context: Context) {
    val parts = remember(text) { text.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                val lines = part.lines()
                val firstLine = lines.firstOrNull()?.trim().orEmpty()
                val language = firstLine.takeWhile { it.isLetterOrDigit() || it == '+' || it == '-' || it == '_' }
                val codeContent = if (language.isNotEmpty() && language == firstLine && lines.size > 1) {
                    lines.drop(1).joinToString("\n").trimEnd()
                } else {
                    part.trimEnd()
                }
                Surface(
                    color = Color(0xFFF8F8F8),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFE5E5E5)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (language.isNotEmpty()) language.uppercase() else "CODE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = { copyToClipboard(context, codeContent) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Скопировать код",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = codeContent,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF24292F)
                            )
                        }
                    }
                }
            } else {
                if (part.isNotEmpty()) {
                    SelectionContainer {
                        Text(text = part, style = MaterialTheme.typography.bodyLarge, color = Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(keys: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var tempKeys by remember { mutableStateOf(keys) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Настройки API-ключей",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(9) { index ->
                    OutlinedTextField(
                        value = tempKeys.getOrNull(index).orEmpty(),
                        onValueChange = { newVal ->
                            tempKeys = tempKeys.toMutableList().also { it[index] = newVal }
                        },
                        label = { Text("API-ключ ${index + 1}") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black,
                            focusedBorderColor = Color.Black,
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(tempKeys); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
            ) {
                Text("Сохранить", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = Color.Gray) }
        },
        containerColor = Color.White
    )
}

// ====================================================================
// 7. Helper functions (free functions — no Modifier.size(Int) extension!)
// ====================================================================

private fun handleFileSelection(uri: Uri, context: Context): Attachment? = try {
    val resolver = context.contentResolver
    val fileName = getFileName(uri, resolver) ?: "attached_file"
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val cacheFile = File(context.cacheDir, "gemini_att_${System.currentTimeMillis()}_$safeName")
    resolver.openInputStream(uri)?.use { input ->
        cacheFile.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IOException("Не удалось открыть InputStream для $uri")
    Attachment(fileName = fileName, filePath = cacheFile.absolutePath, mimeType = mimeType)
} catch (e: Exception) {
    e.printStackTrace()
    null
}

private fun getFileName(uri: Uri, resolver: ContentResolver): String? {
    if (uri.scheme == "content") {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) return cursor.getString(index)
            }
        }
    }
    val path = uri.path ?: return null
    val cut = path.lastIndexOf('/')
    return if (cut != -1) path.substring(cut + 1) else path
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Gemini Content", text))
    Toast.makeText(context, "Скопировано в буфер обмена", Toast.LENGTH_SHORT).show()
}
