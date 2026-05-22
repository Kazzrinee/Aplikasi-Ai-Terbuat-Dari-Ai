package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.AppDatabase
import com.example.data.ChatMessage
import com.example.data.ChatRepository
import com.example.data.ChatSession
import com.example.networking.Content
import com.example.networking.GeminiRequest
import com.example.networking.Part
import com.example.networking.RetrofitClient
import com.example.networking.SystemInstruction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ChatRepository

    val sessions: StateFlow<List<ChatSession>>
    
    private val _currentSessionId = MutableStateFlow<Int?>(null)
    val currentSessionId: StateFlow<Int?> = _currentSessionId.asStateFlow()

    private val _currentMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val currentMessages: StateFlow<List<ChatMessage>> = _currentMessages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _systemPrompt = MutableStateFlow(
        "Anda adalah Tanya AI, asisten virtual super pintar, bijaksana, ramah, dan serba tahu. " +
        "Jawablah seluruh pertanyaan pengguna dengan format yang rapi (gunakan poin-poin atau Markdown jika relevan), " +
        "jelas, solutif, detail, dan dalam bahasa Indonesia yang luwes, santun, serta mengasyikkan."
    )
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private var messagesJob: Job? = null

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ChatRepository(database.chatDao())

        sessions = repository.allSessions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Observe when currentSessionId changes, load messages
        viewModelScope.launch {
            _currentSessionId.collectLatest { sessionId ->
                messagesJob?.cancel()
                if (sessionId != null) {
                    messagesJob = launch {
                        repository.getMessagesForSession(sessionId).collect { messages ->
                            _currentMessages.value = messages
                        }
                    }
                } else {
                    _currentMessages.value = emptyList()
                }
            }
        }

        // Auto-select or create first session if sessions is loaded and empty
        viewModelScope.launch {
            sessions.collect { list ->
                if (_currentSessionId.value == null && list.isNotEmpty()) {
                    _currentSessionId.value = list.first().id
                }
            }
        }
    }

    fun selectSession(sessionId: Int) {
        _currentSessionId.value = sessionId
    }

    fun startNewChat(title: String = "Obrolan Baru") {
        viewModelScope.launch {
            val newId = repository.createNewSession(title)
            _currentSessionId.value = newId
        }
    }

    fun updateSessionTitle(sessionId: Int, title: String) {
        viewModelScope.launch {
            repository.updateSessionTitle(sessionId, title)
        }
    }

    fun deleteSession(sessionId: Int) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = sessions.value.firstOrNull { it.id != sessionId }?.id
            }
        }
    }

    fun clearHistory(sessionId: Int) {
        viewModelScope.launch {
            repository.clearSessionMessages(sessionId)
        }
    }

    fun sendMessage(userPrompt: String) {
        val prompt = userPrompt.trim()
        if (prompt.isEmpty()) return

        viewModelScope.launch {
            var activeSessionId = _currentSessionId.value
            if (activeSessionId == null) {
                // Determine a good short title from first 3 words of prompt
                val words = prompt.split(" ")
                val shortTitle = if (words.size > 3) words.take(3).joinToString(" ") + "..." else prompt
                activeSessionId = repository.createNewSession(shortTitle)
                _currentSessionId.value = activeSessionId
            }

            val sessionId = activeSessionId!!

            // Insert User Message into Database
            repository.insertMessage(
                sessionId = sessionId,
                role = "user",
                content = prompt
            )

            // Auto-update title if it was default "Obrolan Baru" or empty
            val currentSession = sessions.value.find { it.id == sessionId }
            if (currentSession != null && (currentSession.title == "Obrolan Baru" || currentSession.title.trim().isEmpty())) {
                val words = prompt.split(" ")
                val updatedTitle = if (words.size > 4) words.take(4).joinToString(" ") + "..." else prompt
                repository.updateSessionTitle(sessionId, updatedTitle)
            }

            _isGenerating.value = true
            _error.value = null

            try {
                // Fetch full chat history for the API call context
                val chatHistory = _currentMessages.value.filter { it.role == "user" || it.role == "model" }
                val apiContents = chatHistory.map { msg ->
                    Content(
                        parts = listOf(Part(text = msg.content)),
                        role = msg.role
                    )
                } + Content(
                    parts = listOf(Part(text = prompt)),
                    role = "user"
                )

                // Build full Gemini Request
                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                    throw IllegalStateException(
                        "API Key Gemini belum diatur. Silakan masukkan GEMINI_API_KEY Anda di Secrets panel di AI Studio sebelah kanan bawah."
                    )
                }

                val systemInstruction = SystemInstruction(parts = listOf(Part(text = _systemPrompt.value)))
                val request = GeminiRequest(
                    contents = apiContents,
                    systemInstruction = systemInstruction
                )

                val response = RetrofitClient.service.generateContent(apiKey, request)
                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

                if (responseText != null) {
                    repository.insertMessage(
                        sessionId = sessionId,
                        role = "model",
                        content = responseText
                    )
                } else {
                    repository.insertMessage(
                        sessionId = sessionId,
                        role = "error",
                        content = "Mohon maaf, AI tidak mengembalikan respons teks."
                    )
                }

            } catch (e: Exception) {
                val errorMessage = e.message ?: "Terjadi kesalahan tidak dikenal saat menghubungi Gemini API."
                _error.value = errorMessage
                repository.insertMessage(
                    sessionId = sessionId,
                    role = "error",
                    content = "Kesalahan: $errorMessage"
                )
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun updateSystemPrompt(newPrompt: String) {
        _systemPrompt.value = newPrompt
    }
}
