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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Single source of truth for boards, settings and the assistant chat.
 * State lives in memory as a [StateFlow] and is written to `state.json`
 * (atomically, debounced) with a rolling `state.backup.json` and section-by-section
 * migration recovery so app updates never reset user data to dummy entries.
 */
@OptIn(FlowPreview::class)
class Repository private constructor(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        isLenient = true
        coerceInputValues = true
    }
    private val file = File(context.filesDir, "state.json")
    private val backupFile = File(context.filesDir, "state.backup.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    init {
        _state.drop(1).debounce(250).onEach { save(it) }.launchIn(scope)
    }

    private fun load(): AppState {
        // 1. Primary state.json (full decode -> section-by-section recovery)
        if (file.exists()) {
            val raw = runCatching { file.readText() }.getOrNull()
            if (!raw.isNullOrBlank()) {
                runCatching { json.decodeFromString(AppState.serializer(), raw).sanitized() }
                    .getOrNull()
                    ?.let { return it }

                Log.w(TAG, "Primary state.json needed resilient migration; preserving user data")
                runCatching { file.copyTo(File(context.filesDir, "state.pre-migration.json"), overwrite = true) }
                recoverStateFromJson(raw)?.sanitized()?.let { recovered ->
                    save(recovered)
                    return recovered
                }
            }
        }
        // 2. Rolling backup fallback
        if (backupFile.exists()) {
            val raw = runCatching { backupFile.readText() }.getOrNull()
            if (!raw.isNullOrBlank()) {
                runCatching { json.decodeFromString(AppState.serializer(), raw).sanitized() }
                    .getOrNull()
                    ?.let { return it }
                recoverStateFromJson(raw)?.sanitized()?.let { return it }
            }
        }
        return default()
    }

    /** Clean empty workspace for first-time installs (no dummy tasks injected on updates). */
    private fun default(): AppState {
        val board = Presets.empty()
        return AppState(Workspace(listOf(board), board.id))
    }

    /** Recovers each top-level AppState section independently so a single schema tweak never wipes user data. */
    private fun recoverStateFromJson(raw: String): AppState? {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        var recovered = default()
        var anyRecovered = false

        root["workspace"]?.let { el ->
            runCatching { json.decodeFromJsonElement(Workspace.serializer(), el) }.getOrNull()?.let {
                recovered = recovered.copy(workspace = it); anyRecovered = true
            }
        }
        root["settings"]?.let { el ->
            runCatching { json.decodeFromJsonElement(Settings.serializer(), el) }.getOrNull()?.let {
                recovered = recovered.copy(settings = it); anyRecovered = true
            }
        }
        root["sessions"]?.let { el ->
            runCatching { json.decodeFromJsonElement(ListSerializer(ChatSession.serializer()), el) }.getOrNull()?.let {
                recovered = recovered.copy(sessions = it); anyRecovered = true
            }
        }
        root["activeSessionId"]?.let { el ->
            recovered = recovered.copy(activeSessionId = el.jsonPrimitive.contentOrNull)
        }
        root["voiceChat"]?.let { el ->
            runCatching { json.decodeFromJsonElement(ListSerializer(ChatMessage.serializer()), el) }.getOrNull()?.let {
                recovered = recovered.copy(voiceChat = it); anyRecovered = true
            }
        }
        root["notes"]?.let { el ->
            runCatching { json.decodeFromJsonElement(ListSerializer(Note.serializer()), el) }.getOrNull()?.let {
                recovered = recovered.copy(notes = it); anyRecovered = true
            }
        }
        root["transactions"]?.let { el ->
            runCatching { json.decodeFromJsonElement(ListSerializer(Transaction.serializer()), el) }.getOrNull()?.let {
                recovered = recovered.copy(transactions = it); anyRecovered = true
            }
        }
        root["budgets"]?.let { el ->
            runCatching { json.decodeFromJsonElement(ListSerializer(Budget.serializer()), el) }.getOrNull()?.let {
                recovered = recovered.copy(budgets = it); anyRecovered = true
            }
        }
        root["workoutTypes"]?.let { el ->
            runCatching { json.decodeFromJsonElement(ListSerializer(WorkoutType.serializer()), el) }.getOrNull()?.let {
                recovered = recovered.copy(workoutTypes = it); anyRecovered = true
            }
        }
        root["workouts"]?.let { el ->
            runCatching { json.decodeFromJsonElement(ListSerializer(WorkoutSession.serializer()), el) }.getOrNull()?.let {
                recovered = recovered.copy(workouts = it); anyRecovered = true
            }
        }
        root["workoutReminder"]?.let { el ->
            runCatching { json.decodeFromJsonElement(WorkoutReminder.serializer(), el) }.getOrNull()?.let {
                recovered = recovered.copy(workoutReminder = it); anyRecovered = true
            }
        }
        root["focusSessions"]?.let { el ->
            runCatching { json.decodeFromJsonElement(ListSerializer(FocusSession.serializer()), el) }.getOrNull()?.let {
                recovered = recovered.copy(focusSessions = it); anyRecovered = true
            }
        }
        root["calendarReminders"]?.let { el ->
            runCatching { json.decodeFromJsonElement(ListSerializer(CalendarReminder.serializer()), el) }.getOrNull()?.let {
                recovered = recovered.copy(calendarReminders = it); anyRecovered = true
            }
        }
        root["savedWorkoutTracks"]?.let { el ->
            runCatching { json.decodeFromJsonElement(ListSerializer(WorkoutTrack.serializer()), el) }.getOrNull()?.let {
                recovered = recovered.copy(savedWorkoutTracks = it); anyRecovered = true
            }
        }
        root["downloadedWorkoutTracks"]?.let { el ->
            runCatching { json.decodeFromJsonElement(ListSerializer(DownloadedTrack.serializer()), el) }.getOrNull()?.let {
                recovered = recovered.copy(downloadedWorkoutTracks = it); anyRecovered = true
            }
        }
        root["supportTickets"]?.let { el ->
            runCatching { json.decodeFromJsonElement(ListSerializer(SupportTicket.serializer()), el) }.getOrNull()?.let {
                recovered = recovered.copy(supportTickets = it); anyRecovered = true
            }
        }
        return if (anyRecovered) recovered else null
    }

    private fun AppState.sanitized(): AppState {
        var s = if (workspace.boards.isEmpty()) default().copy(settings = settings, chat = chat, sessions = sessions, notes = notes, voiceChat = voiceChat) else this
        if (s.workspace.boards.none { it.id == s.workspace.activeBoardId }) s = s.copy(workspace = s.workspace.copy(activeBoardId = s.workspace.boards.first().id))
        // Keep supported engines (Gemini, Gemma 4 On-Device, ChatGPT, Claude); retired engines move to Gemini.
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
            if (file.exists()) {
                runCatching { file.copyTo(backupFile, overwrite = true) }
            }
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

    // --- One-time password share replay protection ---------------------------

    @Synchronized
    fun isPasswordShareConsumed(shareId: String): Boolean {
        val set = secrets.getStringSet(CONSUMED_SHARES, emptySet()) ?: emptySet()
        return shareId in set
    }

    @Synchronized
    fun markPasswordShareConsumed(shareId: String) {
        val cur = (secrets.getStringSet(CONSUMED_SHARES, emptySet()) ?: emptySet()).toMutableSet()
        if (cur.size > 200) cur.clear()
        cur += shareId
        secrets.edit().putStringSet(CONSUMED_SHARES, cur).apply()
    }

    companion object {
        private const val TAG = "Repository"
        const val ELEVENLABS = "ELEVENLABS"
        private const val VAULT = "VAULT_V1"
        private const val CONSUMED_SHARES = "CONSUMED_PW_SHARES_V1"

        fun sessionTitle(firstMessage: String?): String =
            firstMessage?.trim()?.replace(Regex("""\s+"""), " ")?.let { if (it.length > 42) it.take(40).trimEnd() + "…" else it }?.ifBlank { null } ?: "New chat"

        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository =
            instance ?: synchronized(this) { instance ?: Repository(context.applicationContext).also { instance = it } }
    }
}
