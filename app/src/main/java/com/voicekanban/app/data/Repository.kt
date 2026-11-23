package com.voicekanban.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Single source of truth for boards, settings and the assistant chat.
 * State lives in memory as a [StateFlow] and is written to `state.json`
 * (atomically, debounced). Secrets live in encrypted preferences.
 */
@OptIn(FlowPreview::class)
class Repository private constructor(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val file = File(context.filesDir, "state.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    init {
        _state.drop(1).debounce(250).onEach { save(it) }.launchIn(scope)
    }

    private fun load(): AppState = try {
        if (file.exists()) json.decodeFromString(AppState.serializer(), file.readText()).sanitized() else default()
    } catch (e: Exception) {
        Log.w(TAG, "State unreadable, starting fresh", e)
        file.copyTo(File(context.filesDir, "state.corrupt.json"), overwrite = true)
        default()
    }

    private fun default(): AppState {
        val board = Presets.sprint()
        return AppState(Workspace(listOf(board), board.id))
    }

    private fun AppState.sanitized(): AppState {
        var s = if (workspace.boards.isEmpty()) default().copy(settings = settings, chat = chat, sessions = sessions, notes = notes, voiceChat = voiceChat) else this
        if (s.workspace.boards.none { it.id == s.workspace.activeBoardId }) s = s.copy(workspace = s.workspace.copy(activeBoardId = s.workspace.boards.first().id))
        // Only Gemini, ChatGPT and Claude are offered now: retired engines move to Gemini.
        if (s.settings.engine !in Engine.selectable) s = s.copy(settings = s.settings.copy(engine = Engine.GEMINI))
        // The old single conversation becomes the first chat session.
        if (s.chat.isNotEmpty()) {
            val typed = s.chat.filterNot { it.fromVoice }
            val session = ChatSession(
                newId("sess"),
                title = sessionTitle(typed.firstOrNull { it.fromUser }?.text),
                messages = typed,
                createdAt = typed.firstOrNull()?.createdAt ?: System.currentTimeMillis(),
                updatedAt = typed.lastOrNull()?.createdAt ?: System.currentTimeMillis(),
            )
            s = s.copy(
                chat = emptyList(),
                sessions = if (typed.isNotEmpty()) listOf(session) + s.sessions else s.sessions,
                activeSessionId = if (typed.isNotEmpty()) session.id else s.activeSessionId,
                voiceChat = s.voiceChat + s.chat.filter { it.fromVoice },
            )
        }
        if (s.activeSessionId != null && s.sessions.none { it.id == s.activeSessionId }) s = s.copy(activeSessionId = s.sessions.firstOrNull()?.id)
        return s
    }

    private fun save(s: AppState) {
        try {
            val tmp = File(file.parentFile, "state.json.tmp")
            val trimmed = s.copy(
                sessions = s.sessions.take(60).map { it.copy(messages = it.messages.takeLast(200)) },
                voiceChat = s.voiceChat.takeLast(80),
            )
            tmp.writeText(json.encodeToString(AppState.serializer(), trimmed))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save state", e)
        }
    }

    /** Replaces everything with restored data (from a backup file) and saves at once. */
    fun restore(state: AppState, vault: List<PasswordEntry>?) {
        _state.value = state.sanitized().copy(settings = state.settings.copy(onboarded = true, biometricLock = false))
        vault?.let { v -> updateVault { v } }
        flush()
    }

    /** Save immediately (e.g. before the process may be killed). */
    fun flush() = save(_state.value)

    fun update(transform: (AppState) -> AppState) = _state.update(transform)

    fun updateWorkspace(transform: (Workspace) -> Workspace) = _state.update { it.copy(workspace = transform(it.workspace)) }

    fun updateSettings(transform: (Settings) -> Settings) = _state.update { it.copy(settings = transform(it.settings)) }

    fun updateBoard(boardId: String, transform: (Board) -> Board) = updateWorkspace { ws ->
        ws.copy(boards = ws.boards.map { if (it.id == boardId) transform(it) else it })
    }

    // --- Secrets -------------------------------------------------------------

    private val secrets: SharedPreferences by lazy {
        try {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, "secrets", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Keystore problems on some devices: fall back to private prefs rather than crash.
            Log.e(TAG, "Encrypted prefs unavailable", e)
            context.getSharedPreferences("secrets_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _keys = MutableStateFlow(readKeys())
    /** API keys by engine name, plus the ElevenLabs key. */
    val keys: StateFlow<Map<String, String>> = _keys.asStateFlow()

    private fun readKeys(): Map<String, String> =
        (Engine.entries.filter { it.needsKey }.map { it.name } + ELEVENLABS).associateWith { secrets.getString(it, "") ?: "" }

    fun setKey(name: String, value: String) {
        secrets.edit().apply { if (value.isBlank()) remove(name) else putString(name, value.trim()) }.apply()
        _keys.value = readKeys()
    }

    fun key(engine: Engine): String = _keys.value[engine.name].orEmpty()

    // --- Password vault (encrypted preferences only) -------------------------

    private val vaultSerializer = kotlinx.serialization.builtins.ListSerializer(PasswordEntry.serializer())
    private val _vault = MutableStateFlow(readVault())
    val vault: StateFlow<List<PasswordEntry>> = _vault.asStateFlow()

    private fun readVault(): List<PasswordEntry> = try {
        secrets.getString(VAULT, null)?.let { json.decodeFromString(vaultSerializer, it) } ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Vault unreadable", e); emptyList()
    }

    @Synchronized
    fun updateVault(transform: (List<PasswordEntry>) -> List<PasswordEntry>) {
        val next = transform(_vault.value)
        secrets.edit().putString(VAULT, json.encodeToString(vaultSerializer, next)).apply()
        _vault.value = next
    }

    companion object {
        private const val TAG = "Repository"
        const val ELEVENLABS = "ELEVENLABS"
        private const val VAULT = "VAULT_V1"

        fun sessionTitle(firstMessage: String?): String =
            firstMessage?.trim()?.replace(Regex("""\s+"""), " ")?.let { if (it.length > 42) it.take(40).trimEnd() + "…" else it }?.ifBlank { null } ?: "New chat"

        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository =
            instance ?: synchronized(this) { instance ?: Repository(context.applicationContext).also { instance = it } }
    }
}
